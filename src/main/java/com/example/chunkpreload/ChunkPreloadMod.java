package com.example.chunkpreload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ChunkPreloadMod implements ModInitializer {
	public static final String MOD_ID = "chunkpreload";
	public static final Logger LOGGER = LoggerFactory.getLogger("Chunk Preloader");
	private static final java.util.concurrent.atomic.AtomicBoolean preloadingAnnounced = new java.util.concurrent.atomic.AtomicBoolean(false);
	public static ChunkPreloadConfig CONFIG;

	// Ring-ordered (dx, dz) chunk offsets covering a circle of CONFIG.radius chunks, built once at startup.
	private static int[] offsetX;
	private static int[] offsetZ;
	private static int totalChunks;

	private static PreloadState state;

	// If C2ME is present, its parallelized async chunk pipeline is used instead of the
	// simple synchronous per-tick loop, since it's built specifically to make that safe and fast.
	private static final boolean USE_ASYNC = FabricLoader.getInstance().isModLoaded("c2me");
	private static final int MAX_CONCURRENT_ASYNC_CHUNKS = 32;

	// Async bookkeeping - only ever touched on the main server thread (async callbacks below
	// hop back onto it via server.execute before touching any of this).
	private static int nextRequestIndex = -1;
	private static final Set<Integer> inFlightIndices = new HashSet<>();
	private static final Set<Integer> completedIndices = new HashSet<>();
	// Async completion callbacks can run on a worker thread, or on the main thread at an
	// unpredictable point (not necessarily safe relative to vanilla's own tick code) - so they
	// only ever touch this thread-safe queue, never any actual chunk/ticket state directly.
	// All real mutation happens by draining this at the start of our own tick, a known-safe point.
	private static final java.util.concurrent.ConcurrentLinkedQueue<Integer> pendingCompletionQueue =
			new java.util.concurrent.ConcurrentLinkedQueue<>();

	@Override
	public void onInitialize() {
		CONFIG = ChunkPreloadConfig.load();
		buildSpiral(CONFIG.radius);

		if (USE_ASYNC) {
			LOGGER.info("C2ME detected - using async chunk loading.");
		}

		PayloadTypeRegistry.clientboundPlay().register(PreloadProgressPayload.TYPE, PreloadProgressPayload.CODEC);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ensureState(server);
			ServerPlayer player = handler.getPlayer();

			if (CONFIG.enabled && !state.completed && !state.started) {
				BlockPos pos = player.blockPosition();
				int centerX = pos.getX() >> 4;
				int centerZ = pos.getZ() >> 4;
				state.markStarted(centerX, centerZ);
				LOGGER.info("Starting chunk preload: {} chunks around ({}, {})", totalChunks, centerX, centerZ);
			}

			sendProgress(player);
		});

		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

		// A world can be closed and a different one opened without fully quitting the game
		// (singleplayer especially) - each is its own MinecraftServer instance, so all of
		// this needs to be reset or it'll incorrectly carry over into the next world.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			state = null;
			nextRequestIndex = -1;
			inFlightIndices.clear();
			completedIndices.clear();
			pendingCompletionQueue.clear();
			tickCounter = 0;
			lastBroadcastDone = -1;
			lastBroadcastActive = false;
			preloadingAnnounced.set(false);
		});
	}

	private static void ensureState(MinecraftServer server) {
		if (state == null) {
			state = PreloadState.get(server);
			nextRequestIndex = state.doneCount;
		}
	}

	private static int tickCounter = 0;
	private static final int BROADCAST_INTERVAL_TICKS = 5; // ~4 updates/sec is plenty for a progress bar
	private static final int TIME_CHECK_INTERVAL = 16; // check the clock every N chunks instead of every one
	private static final long BUSY_TICK_THRESHOLD_NANOS = 45_000_000L; // 45ms - back off if the server's already this busy

	private static int lastBroadcastDone = -1;
	private static boolean lastBroadcastActive = false;

	private void onServerTick(MinecraftServer server) {
		ensureState(server);

		if (!state.started || state.completed) {
			return;
		}

		boolean serverIsBusy = CONFIG.adaptiveThrottling
				&& server.getAverageTickTimeNanos() > BUSY_TICK_THRESHOLD_NANOS;
				LOGGER.info("Server Busy");

		if (CONFIG.enabled && !serverIsBusy) {
			ServerLevel overworld = server.getLevel(ServerLevel.OVERWORLD);

			if (overworld != null) {
				LOGGER.info("Generating Chunks");
				if (USE_ASYNC) {
					tickAsync(server, overworld);
				} else {
					tickSync(overworld);
				}

				if (state.doneCount >= totalChunks && !state.completed) {
					state.markCompleted();
					LOGGER.info("Chunk preload complete: {} chunks generated", totalChunks);
					
					if (preloadingAnnounced.compareAndSet(false, true)) {
						server.getPlayerList().broadcastSystemMessage(
							Component.literal("§aDone loading§r"), 
							false
						);
					}

					broadcastProgress(server);
					return;
				}
			}
		}

		if (++tickCounter >= BROADCAST_INTERVAL_TICKS) {
			tickCounter = 0;
			broadcastProgress(server);
		}
	}

	/** The original approach: block the tick thread generating chunks one at a time within a time budget. */
	private static void tickSync(ServerLevel overworld) {
		long deadlineNanos = System.nanoTime() + (long) CONFIG.maxMillisPerTick * 1_000_000L;
		int doneCount = state.doneCount;
		int startedThisTick = doneCount;

		while (doneCount < totalChunks) {
			int chunkX = state.centerX + offsetX[doneCount];
			int chunkZ = state.centerZ + offsetZ[doneCount];

			// Forces the chunk to load and generate up to FULL status, synchronously.
			// It is not force-kept-loaded: once nothing references it, it unloads normally.
			overworld.getChunk(chunkX, chunkZ);

			doneCount++;

			if (doneCount % TIME_CHECK_INTERVAL == 0 && System.nanoTime() >= deadlineNanos) {
				break;
			}
		}

		if (doneCount != startedThisTick) {
			state.setDoneCount(doneCount);
		}
	}

	/**
	 * The C2ME-aware approach: fire off up to MAX_CONCURRENT_ASYNC_CHUNKS chunk loads at once via
	 * tickets and let the engine's own async pipeline (parallelized by C2ME) handle them, instead
	 * of blocking the tick thread on one chunk at a time.
	 *
	 * Completions can arrive out of order, so doneCount only ever advances past a contiguous run
	 * starting from the current value - completedIndices holds any "ahead of the pack" completions
	 * until the gap in front of them closes.
	 */
	private static void tickAsync(MinecraftServer server, ServerLevel overworld) {
		// Drain anything that finished since the last tick. This is the only place ticket removal
		// or doneCount changes happen - always right here, at the start of our own tick, never from
		// inside the async callback itself (which may run at an unsafe point relative to vanilla's
		// own chunk-tracking code).
		Integer completedIndex;
		while ((completedIndex = pendingCompletionQueue.poll()) != null) {
			int chunkX = state.centerX + offsetX[completedIndex];
			int chunkZ = state.centerZ + offsetZ[completedIndex];

			// Release the forced-load ticket now that we're done with it, so it can
			// unload normally like any other chunk once nothing else references it.
			overworld.getChunkSource().removeTicketWithRadius(TicketType.FORCED, new ChunkPos(chunkX, chunkZ), 0);

			inFlightIndices.remove(completedIndex);
			completedIndices.add(completedIndex);
		}

		while (completedIndices.remove(state.doneCount)) {
			state.setDoneCount(state.doneCount + 1);
		}

		while (inFlightIndices.size() < MAX_CONCURRENT_ASYNC_CHUNKS && nextRequestIndex < totalChunks) {
			int index = nextRequestIndex;
			nextRequestIndex++;

			if (completedIndices.contains(index)) {
				// Already finished in a previous run before a restart - nothing to request.
				continue;
			}

			int chunkX = state.centerX + offsetX[index];
			int chunkZ = state.centerZ + offsetZ[index];
			ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);

			inFlightIndices.add(index);

			overworld.getChunkSource()
					.addTicketAndLoadWithRadius(TicketType.FORCED, chunkPos, 0)
					.whenComplete((result, throwable) -> {
						if (throwable != null) {
							LOGGER.warn("Async preload of chunk ({}, {}) failed, will not retry this session",
									chunkPos.x(), chunkPos.z(), throwable);
						}

						// Deliberately do nothing else here - see the drain loop above.
						pendingCompletionQueue.add(index);
					});
		}
	}

	private static void broadcastProgress(MinecraftServer server) {
		boolean active = CONFIG.enabled && state.started && !state.completed;

		// Skip the round of packets entirely if nothing has changed since the last one sent.
		if (state.doneCount == lastBroadcastDone && active == lastBroadcastActive) {
			return;
		}

		lastBroadcastDone = state.doneCount;
		lastBroadcastActive = active;

		PreloadProgressPayload payload = new PreloadProgressPayload(state.doneCount, totalChunks, active);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static void sendProgress(ServerPlayer player) {
		boolean active = CONFIG.enabled && state.started && !state.completed;
		PreloadProgressPayload payload = new PreloadProgressPayload(state.doneCount, totalChunks, active);
		ServerPlayNetworking.send(player, payload);
	}

	/**
	 * Rebuilds the ring-ordered offsets from the current CONFIG.radius.
	 * Call this after changing CONFIG.radius at runtime (e.g. from the settings
	 * screen) so a world that hasn't started preloading yet picks up the new value
	 * without needing a full game restart.
	 */
	public static void rebuildSpiral() {
		buildSpiral(CONFIG.radius);
	}

	/**
	 * Builds ring-ordered (dx, dz) offsets for every chunk within {@code radius} chunks
	 * of the origin, filtered to a circle. Ring order makes the preload visibly expand
	 * outward from the center rather than filling in an arbitrary order.
	 */
	private static void buildSpiral(int radius) {
		int side = 2 * radius + 1;
		int maxCells = side * side;
		int[] tmpX = new int[maxCells];
		int[] tmpZ = new int[maxCells];
		int count = 0;
		long radiusSquared = (long) radius * radius;

		tmpX[count] = 0;
		tmpZ[count] = 0;
		count++;

		for (int r = 1; r <= radius; r++) {
			for (int dx = -r; dx <= r; dx++) {
				if ((long) dx * dx + (long) r * r <= radiusSquared) {
					tmpX[count] = dx;
					tmpZ[count] = -r;
					count++;
				}
				if ((long) dx * dx + (long) r * r <= radiusSquared) {
					tmpX[count] = dx;
					tmpZ[count] = r;
					count++;
				}
			}

			for (int dz = -r + 1; dz <= r - 1; dz++) {
				if ((long) r * r + (long) dz * dz <= radiusSquared) {
					tmpX[count] = -r;
					tmpZ[count] = dz;
					count++;
				}
				if ((long) r * r + (long) dz * dz <= radiusSquared) {
					tmpX[count] = r;
					tmpZ[count] = dz;
					count++;
				}
			}
		}

		offsetX = Arrays.copyOf(tmpX, count);
		offsetZ = Arrays.copyOf(tmpZ, count);
		totalChunks = count;

		LOGGER.info("Chunk Preloader configured for a {}-chunk radius ({} chunks total)", radius, totalChunks);
	}
}