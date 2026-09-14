package com.example.chunkpreload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Holder;
import net.minecraft.server.permissions.Permissions;

import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.Commands.argument;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ChunkPreloadMod implements ModInitializer {
	public static final String MOD_ID = "chunkpreload";
	public static final Logger LOGGER = LoggerFactory.getLogger("Chunk Preloader");
	public static ChunkPreloadConfig CONFIG;

	// Ring-ordered (dx, dz) chunk offsets covering a circle of CONFIG.radius chunks, built once at startup.
	private static int[] offsetX;
	private static int[] offsetZ;
	private static int totalChunks;

	private static PreloadState state;

	// Async bookkeeping - only ever touched on the main server thread (async callbacks below
	// hop back onto it via server.execute before touching any of this).
	private static int nextRequestIndex = -1;
	private static final Set<Integer> inFlightIndices = new HashSet<>();
	private static final Set<Integer> completedIndices = new HashSet<>();
	// Async completion callbacks can run on a worker thread, or on the main thread at an
	// unpredictable point (not necessarily safe relative to vanilla's own tick code) - so they
	// only ever touch this thread-safe queue, never any actual chunk/ticket state directly.
	// All real mutation happens by draining this at the start of our own tick, a known-safe point.
	private static final ConcurrentLinkedQueue<Integer> pendingCompletionQueue =
			new ConcurrentLinkedQueue<>();

	@Override
	public void onInitialize() {
		CONFIG = ChunkPreloadConfig.load();
		buildSpiral(CONFIG.radius);

		PayloadTypeRegistry.clientboundPlay().register(PreloadProgressPayload.TYPE, PreloadProgressPayload.CODEC);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(literal("chunkpreload")
					.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
					.then(literal("start")
							.executes(context -> {
								MinecraftServer server = context.getSource().getServer();
								ensureState(server);
								ServerLevel level = context.getSource().getLevel();
								BlockPos pos = BlockPos.containing(context.getSource().getPosition());
								startPreload(level, pos.getX() >> 4, pos.getZ() >> 4);
								context.getSource().sendSuccess(() -> Component.literal("Started preloading around your position"), true);
								return 1;
							})
							.then(argument("radius", IntegerArgumentType.integer(1))
									.executes(context -> {
										int r = IntegerArgumentType.getInteger(context, "radius");
										CONFIG.radius = r;
										CONFIG.save();
										rebuildSpiral();
										MinecraftServer server = context.getSource().getServer();
										ensureState(server);
										ServerLevel level = context.getSource().getLevel();
										BlockPos pos = BlockPos.containing(context.getSource().getPosition());
										startPreload(level, pos.getX() >> 4, pos.getZ() >> 4);
										context.getSource().sendSuccess(() -> Component.literal("Started preloading with radius " + r), true);
										return 1;
									})))
					.then(literal("stop")
							.executes(context -> {
								ensureState(context.getSource().getServer());
								state.markCompleted();
								context.getSource().sendSuccess(() -> Component.literal("Preloading stopped"), true);
								return 1;
							}))
					.then(literal("turbo")
							.executes(context -> {
								CONFIG.turboMode = !CONFIG.turboMode;
								CONFIG.save();
								context.getSource().sendSuccess(() -> Component.literal("Turbo Mode: " + (CONFIG.turboMode ? "ON" : "OFF")), true);
								return 1;
							}))
					.then(literal("status")
							.executes(context -> {
								ensureState(context.getSource().getServer());
								if (!state.started) {
									context.getSource().sendSuccess(() -> Component.literal("Not started"), false);
								} else if (state.completed) {
									context.getSource().sendSuccess(() -> Component.literal("Completed: " + state.doneCount + "/" + totalChunks), false);
								} else {
									context.getSource().sendSuccess(() -> Component.literal("Progress: " + state.doneCount + "/" + totalChunks + " (Turbo: " + CONFIG.turboMode + ", Dim: " + state.dimension + ")"), false);
								}
								return 1;
							}))
			);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ensureState(server);
			ServerPlayer player = handler.getPlayer();

			if (CONFIG.enabled && !state.completed && !state.started) {
				ServerLevel level = server.getLevel(Level.OVERWORLD);
				if (level != null) {
					BlockPos pos = player.blockPosition();
					startPreload(level, pos.getX() >> 4, pos.getZ() >> 4);
				}
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

	private static int lastBroadcastDone = -1;
	private static boolean lastBroadcastActive = false;

	private static void startPreload(ServerLevel level, int chunkX, int chunkZ) {
		String dimId = level.dimension().identifier().toString();
		state.markStarted(chunkX, chunkZ, dimId);
		rebuildSpiral();
		LOGGER.info("Starting chunk preload: {} chunks in {} around ({}, {})", totalChunks, dimId, chunkX, chunkZ);
	}

	private void onServerTick(MinecraftServer server) {
		ensureState(server);

		if (!state.started || state.completed) {
			return;
		}

		Identifier dimId = Identifier.tryParse(state.dimension);
		if (dimId == null) dimId = Level.OVERWORLD.identifier();
		
		ServerLevel currentLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));

		if (currentLevel == null) return;

		processCompletions(currentLevel);

		if (CONFIG.enabled) {
			boolean serverIsBusy = !CONFIG.turboMode && CONFIG.adaptiveThrottling
					&& server.getAverageTickTimeNanos() > (long) (CONFIG.busyTickThresholdMs * 1_000_000L);

			boolean lowMemory = !CONFIG.turboMode && isLowMemory();

			if (!serverIsBusy && !lowMemory) {
				requestMoreChunks(currentLevel);
			}

			if (state.doneCount >= totalChunks && !state.completed) {
				LOGGER.info("Chunk preload complete for {}: {} chunks generated", state.dimension, totalChunks);
				
				// Check for next dimension
				boolean startedNext = false;
				if (state.dimension.equals(Level.OVERWORLD.identifier().toString()) && CONFIG.preloadNether) {
					ServerLevel next = server.getLevel(Level.NETHER);
					if (next != null) {
						startPreload(next, state.centerX, state.centerZ);
						startedNext = true;
					}
				} else if (state.dimension.equals(Level.NETHER.identifier().toString()) && CONFIG.preloadEnd) {
					ServerLevel next = server.getLevel(Level.END);
					if (next != null) {
						startPreload(next, state.centerX, state.centerZ);
						startedNext = true;
					}
				}

				if (!startedNext) {
					state.markCompleted();
					if (!CONFIG.onCompleteCommand.isEmpty()) {
						server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), CONFIG.onCompleteCommand);
					}
				}

				broadcastProgress(server);
				return;
			}
		}

		if (++tickCounter >= BROADCAST_INTERVAL_TICKS) {
			tickCounter = 0;
			broadcastProgress(server);
		}
	}

	private static boolean isLowMemory() {
		Runtime runtime = Runtime.getRuntime();
		double used = (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();
		return used > CONFIG.memoryUsageThreshold;
	}

	private static void processCompletions(ServerLevel overworld) {
		Integer completedIndex;
		boolean changed = false;
		while ((completedIndex = pendingCompletionQueue.poll()) != null) {
			int chunkX = state.centerX + offsetX[completedIndex];
			int chunkZ = state.centerZ + offsetZ[completedIndex];

			overworld.getChunkSource().removeTicketWithRadius(TicketType.FORCED, new ChunkPos(chunkX, chunkZ), 0);

			inFlightIndices.remove(completedIndex);
			completedIndices.add(completedIndex);
			changed = true;
		}

		if (changed) {
			int startDoneCount = state.doneCount;
			while (completedIndices.remove(state.doneCount)) {
				state.doneCount++;
			}
			if (state.doneCount != startDoneCount) {
				state.setDirty();
			}
		}
	}

	private static void requestMoreChunks(ServerLevel overworld) {
		int maxConcurrency = CONFIG.maxConcurrentAsyncChunks;
		ChunkStatus status = BuiltInRegistries.CHUNK_STATUS.get(Identifier.parse(CONFIG.targetStatus))
				.map(Holder.Reference::value).orElse(ChunkStatus.FULL);

		while (inFlightIndices.size() < maxConcurrency && nextRequestIndex < totalChunks) {
			int index = nextRequestIndex++;

			if (completedIndices.contains(index)) {
				continue;
			}

			int chunkX = state.centerX + offsetX[index];
			int chunkZ = state.centerZ + offsetZ[index];
			ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);

			inFlightIndices.add(index);

			// Add ticket to keep it loaded
			overworld.getChunkSource().addTicketWithRadius(TicketType.FORCED, chunkPos, 0);
			
			// Load to target status
			overworld.getChunkSource().getChunkFuture(chunkPos.x(), chunkPos.z(), status, true)
					.whenComplete((result, throwable) -> {
						if (throwable != null) {
							LOGGER.warn("Async preload of chunk ({}, {}) failed", chunkPos.x(), chunkPos.z(), throwable);
						}
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

		PreloadProgressPayload payload = new PreloadProgressPayload(state.doneCount, totalChunks, active, state.dimension);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static void sendProgress(ServerPlayer player) {
		boolean active = CONFIG.enabled && state.started && !state.completed;
		PreloadProgressPayload payload = new PreloadProgressPayload(state.doneCount, totalChunks, active, state.dimension);
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
				boolean inShape = (CONFIG.shape == ChunkPreloadConfig.Shape.SQUARE) || ((long) dx * dx + (long) r * r <= radiusSquared);
				if (inShape) {
					tmpX[count] = dx;
					tmpZ[count] = -r;
					count++;
				}
				if (inShape) {
					tmpX[count] = dx;
					tmpZ[count] = r;
					count++;
				}
			}

			for (int dz = -r + 1; dz <= r - 1; dz++) {
				boolean inShape = (CONFIG.shape == ChunkPreloadConfig.Shape.SQUARE) || ((long) r * r + (long) dz * dz <= radiusSquared);
				if (inShape) {
					tmpX[count] = -r;
					tmpZ[count] = dz;
					count++;
				}
				if (inShape) {
					tmpX[count] = r;
					tmpZ[count] = dz;
					count++;
				}
			}
		}

		offsetX = Arrays.copyOf(tmpX, count);
		offsetZ = Arrays.copyOf(tmpZ, count);
		totalChunks = count;

		LOGGER.info("Chunk Preloader configured for a {}-chunk {} ({} chunks total)", radius, CONFIG.shape, totalChunks);
	}
}