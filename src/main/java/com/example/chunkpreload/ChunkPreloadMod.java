package com.example.chunkpreload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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

public class ChunkPreloadMod implements ModInitializer {
	public static final String MOD_ID = "chunkpreload";
	public static final Logger LOGGER = LoggerFactory.getLogger("Chunk Preloader");
	public static ChunkPreloadConfig CONFIG;

	private static int[] offsetX;
	private static int[] offsetZ;
	private static int totalChunks;

	private static PreloadState state;
	private static ChunkStatus cachedTargetStatus;
	private static long lastDoneCount = 0;
	private static long lastTime = 0;
	private static float chunksPerSecond = 0;

	private static int nextRequestIndex = -1;
	private static final Set<Integer> inFlightIndices = new HashSet<>();
	private static final Set<Integer> completedIndices = new HashSet<>();
	private static final ConcurrentLinkedQueue<Integer> pendingCompletionQueue = new ConcurrentLinkedQueue<>();
	private static final AtomicBoolean refillTaskPending = new AtomicBoolean(false);

	private static MinecraftServer currentServer;

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
					.then(literal("refill")
							.executes(context -> {
								CONFIG.immediateRefill = !CONFIG.immediateRefill;
								CONFIG.save();
								context.getSource().sendSuccess(() -> Component.literal("Immediate Refill: " + (CONFIG.immediateRefill ? "ON" : "OFF")), true);
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
									context.getSource().sendSuccess(() -> Component.literal(String.format("Progress: %d/%d (Turbo: %b, Dim: %s, Speed: %.1f ch/s)", 
											state.doneCount, totalChunks, CONFIG.turboMode, state.dimension, chunksPerSecond)), false);
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
		ServerTickEvents.START_SERVER_TICK.register(server -> {
			if (state != null && state.started && !state.completed) {
				ServerLevel level = getLevelForDimension(server, state.dimension);
				if (level != null) processCompletions(level);
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			state = null;
			currentServer = null;
			inFlightIndices.clear();
			completedIndices.clear();
			pendingCompletionQueue.clear();
			refillTaskPending.set(false);
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			state = null;
			nextRequestIndex = -1;
			inFlightIndices.clear();
			completedIndices.clear();
			pendingCompletionQueue.clear();
			tickCounter = 0;
			lastBroadcastDone = -1;
			lastBroadcastActive = false;
			refillTaskPending.set(false);
			currentServer = null;
		});
	}

	private static void ensureState(MinecraftServer server) {
		currentServer = server;
		if (state == null) {
			state = PreloadState.get(server);
			nextRequestIndex = state.doneCount;
		}
	}

	private static int tickCounter = 0;
	private static final int BROADCAST_INTERVAL_TICKS = 5;
	private static int lastBroadcastDone = -1;
	private static boolean lastBroadcastActive = false;

	private void onServerTick(MinecraftServer server) {
		ensureState(server);

		if (!state.started || state.completed) {
			return;
		}

		ServerLevel currentLevel = getLevelForDimension(server, state.dimension);
		if (currentLevel == null) return;

		processCompletions(currentLevel);

		if (CONFIG.enabled) {
			updateCps();

			boolean serverIsBusy = !CONFIG.turboMode && CONFIG.adaptiveThrottling
					&& server.getAverageTickTimeNanos() > (long) (CONFIG.busyTickThresholdMs * 1_000_000L);

			boolean lowMemory = !CONFIG.turboMode && isLowMemory();

			if (!serverIsBusy && !lowMemory) {
				requestMoreChunks(currentLevel, 32);
			}

			if (state.doneCount >= totalChunks && !state.completed) {
				LOGGER.info("Chunk preload complete for {}: {} chunks generated", state.dimension, totalChunks);
				
				boolean nextDimStarted = false;
				if (state.dimension.equals(Level.OVERWORLD.identifier().toString()) && CONFIG.preloadNether) {
					ServerLevel next = server.getLevel(Level.NETHER);
					if (next != null) {
						startPreload(next, state.centerX, state.centerZ);
						nextDimStarted = true;
					}
				} else if (state.dimension.equals(Level.NETHER.identifier().toString()) && CONFIG.preloadEnd) {
					ServerLevel next = server.getLevel(Level.END);
					if (next != null) {
						startPreload(next, state.centerX, state.centerZ);
						nextDimStarted = true;
					}
				}

				if (!nextDimStarted) {
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

	private static ServerLevel getLevelForDimension(MinecraftServer server, String dimension) {
		Identifier dimId = Identifier.tryParse(dimension);
		if (dimId == null) return server.getLevel(Level.OVERWORLD);
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
	}

	private static void updateCps() {
		long now = System.currentTimeMillis();
		if (lastTime == 0) {
			lastTime = now;
			lastDoneCount = state.doneCount;
			return;
		}

		long elapsed = now - lastTime;
		if (elapsed >= 1000) {
			chunksPerSecond = (float) (state.doneCount - lastDoneCount) * 1000f / elapsed;
			lastTime = now;
			lastDoneCount = state.doneCount;
		}
	}

	private static void startPreload(ServerLevel level, int chunkX, int chunkZ) {
		String dimId = level.dimension().identifier().toString();
		state.markStarted(chunkX, chunkZ, dimId);
		rebuildSpiral();
		LOGGER.info("Starting chunk preload: {} chunks in {} around ({}, {})", totalChunks, dimId, chunkX, chunkZ);
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

	private static void requestMoreChunks(ServerLevel overworld, int limit) {
		int maxConcurrency = CONFIG.maxConcurrentAsyncChunks;
		
		if (cachedTargetStatus == null) {
			cachedTargetStatus = BuiltInRegistries.CHUNK_STATUS.get(Identifier.parse(CONFIG.targetStatus))
					.map(Holder.Reference::value).orElse(ChunkStatus.FULL);
		}
		ChunkStatus status = cachedTargetStatus;

		int requested = 0;
		while (inFlightIndices.size() < maxConcurrency && nextRequestIndex < totalChunks && requested < limit) {
			int index = nextRequestIndex++;
			requested++;

			if (completedIndices.contains(index)) {
				continue;
			}

			int chunkX = state.centerX + offsetX[index];
			int chunkZ = state.centerZ + offsetZ[index];
			ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);

			inFlightIndices.add(index);

			overworld.getChunkSource().addTicketWithRadius(TicketType.FORCED, chunkPos, 0);
			
			overworld.getChunkSource().getChunkFuture(chunkPos.x(), chunkPos.z(), status, true)
					.whenComplete((result, throwable) -> {
						if (throwable != null) {
							LOGGER.warn("Async preload of chunk ({}, {}) failed", chunkPos.x(), chunkPos.z(), throwable);
						}
						pendingCompletionQueue.add(index);

						if (CONFIG.immediateRefill && currentServer != null && !currentServer.isStopped() && refillTaskPending.compareAndSet(false, true)) {
							currentServer.execute(() -> {
								refillTaskPending.set(false);
								if (currentServer == null || currentServer.isStopped()) return;
								if (state != null && state.started && !state.completed) {
									ServerLevel level = getLevelForDimension(currentServer, state.dimension);
									if (level != null) {
										processCompletions(level);
										if (!isLowMemory()) {
											requestMoreChunks(level, 16);
										}
									}
								}
							});
						}
					});
		}
	}

	private static void broadcastProgress(MinecraftServer server) {
		boolean active = CONFIG.enabled && state.started && !state.completed;

		if (state.doneCount == lastBroadcastDone && active == lastBroadcastActive) {
			return;
		}

		lastBroadcastDone = state.doneCount;
		lastBroadcastActive = active;

		PreloadProgressPayload payload = new PreloadProgressPayload(state.doneCount, totalChunks, active, state.dimension, chunksPerSecond);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static void sendProgress(ServerPlayer player) {
		boolean active = CONFIG.enabled && state.started && !state.completed;
		PreloadProgressPayload payload = new PreloadProgressPayload(state.doneCount, totalChunks, active, state.dimension, chunksPerSecond);
		ServerPlayNetworking.send(player, payload);
	}

	public static void rebuildSpiral() {
		buildSpiral(CONFIG.radius);
	}

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
