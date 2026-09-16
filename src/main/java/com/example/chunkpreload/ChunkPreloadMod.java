package com.example.chunkpreload;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.particles.ParticleTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class ChunkPreloadMod implements ModInitializer {
	public static final String MOD_ID = "chunkpreload";
	public static final Logger LOGGER = LoggerFactory.getLogger("Chunk Preloader");
	public static ChunkPreloadConfig CONFIG;

	private static short[] offsetX;
	private static short[] offsetZ;
	private static int totalChunks;

	private static PreloadState state;
	private static ChunkStatus cachedTargetStatus;
	private static long lastDoneCount = 0;
	private static long lastTime = 0;
	private static float chunksPerSecond = 0;
	private static long lastConsoleLogTime = 0;
	private static String currentPauseReason = "";

	private static int chunksGeneratedThisSession = 0;
	private static long benchmarkStartTime = 0;
	private static boolean isBenchmarking = false;

	private static int nextRequestIndex = -1;
	private static final Set<Integer> inFlightIndices = new HashSet<>();
	private static final Set<Integer> completedIndices = new HashSet<>();
	private static final ConcurrentLinkedQueue<Integer> pendingCompletionQueue = new ConcurrentLinkedQueue<>();
	private static final AtomicBoolean refillTaskPending = new AtomicBoolean(false);

	private static MinecraftServer currentServer;
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

	@Override
	public void onInitialize() {
		CONFIG = ChunkPreloadConfig.load();
		buildSpiral(CONFIG.radius);

		PayloadTypeRegistry.clientboundPlay().register(PreloadProgressPayload.TYPE, PreloadProgressPayload.CODEC);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			var root = literal("chunkpreload")
					.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN));

			root.then(literal("start")
					.executes(context -> {
						ensureState(context.getSource().getServer());
						BlockPos pos = BlockPos.containing(context.getSource().getPosition());
						startPreload(context.getSource().getLevel(), pos.getX() >> 4, pos.getZ() >> 4);
						context.getSource().sendSuccess(() -> Component.literal("Started preloading around your position"), true);
						return 1;
					})
					.then(argument("radius", IntegerArgumentType.integer(1))
							.executes(context -> {
								int r = IntegerArgumentType.getInteger(context, "radius");
								ensureState(context.getSource().getServer());
								BlockPos pos = BlockPos.containing(context.getSource().getPosition());
								startPreload(context.getSource().getLevel(), pos.getX() >> 4, pos.getZ() >> 4, r);
								context.getSource().sendSuccess(() -> Component.literal("Started preloading with radius " + r), true);
								return 1;
							}))
					.then(argument("pos", ColumnPosArgument.columnPos())
							.executes(context -> {
								ColumnPos pos = ColumnPosArgument.getColumnPos(context, "pos");
								ensureState(context.getSource().getServer());
								startPreload(context.getSource().getLevel(), pos.x() >> 4, pos.z() >> 4);
								context.getSource().sendSuccess(() -> Component.literal("Started preloading around " + pos.x() + ", " + pos.z()), true);
								return 1;
							})));

			root.then(literal("pause")
					.executes(context -> {
						ensureState(context.getSource().getServer());
						state.setPaused(true);
						currentPauseReason = "MANUAL";
						context.getSource().sendSuccess(() -> Component.literal("Preloading paused"), true);
						return 1;
					}));

			root.then(literal("resume")
					.executes(context -> {
						ensureState(context.getSource().getServer());
						state.setPaused(false);
						currentPauseReason = "";
						context.getSource().sendSuccess(() -> Component.literal("Preloading resumed"), true);
						return 1;
					}));

			root.then(literal("border")
					.executes(context -> {
						ServerLevel level = context.getSource().getLevel();
						WorldBorder border = level.getWorldBorder();
						int radius = (int) (border.getSize() / 2.0) / 16;
						CONFIG.radius = radius;
						CONFIG.shape = ChunkPreloadConfig.Shape.SQUARE;
						CONFIG.save();
						ensureState(context.getSource().getServer());
						startPreload(level, (int) border.getCenterX() >> 4, (int) border.getCenterZ() >> 4);
						context.getSource().sendSuccess(() -> Component.literal("Started preloading within world border (radius: " + radius + ")"), true);
						return 1;
					}));

			root.then(literal("benchmark")
					.executes(context -> {
						ensureState(context.getSource().getServer());
						isBenchmarking = true;
						benchmarkStartTime = System.currentTimeMillis();
						int oldRadius = CONFIG.radius;
						BlockPos pos = BlockPos.containing(context.getSource().getPosition());
						startPreload(context.getSource().getLevel(), pos.getX() >> 4, pos.getZ() >> 4, 5);
						CONFIG.radius = oldRadius;
						context.getSource().sendSuccess(() -> Component.literal("Starting 10x10 benchmark..."), true);
						return 1;
					}));

			root.then(literal("estimate")
					.executes(context -> {
						long sizeBytes = totalChunks * 10240L; // Estimate 10KB per chunk
						double sizeMb = sizeBytes / (1024.0 * 1024.0);
						context.getSource().sendSuccess(() -> Component.literal(String.format("Estimate: %d chunks will take approx %.2f MB of disk space.", totalChunks, sizeMb)), false);
						return 1;
					}));

			root.then(literal("dryrun")
					.executes(context -> {
						CONFIG.dryRunMode = !CONFIG.dryRunMode;
						CONFIG.save();
						context.getSource().sendSuccess(() -> Component.literal("Dry Run Mode: " + (CONFIG.dryRunMode ? "ON" : "OFF")), true);
						return 1;
					}));

			root.then(literal("stop")
					.executes(context -> {
						ensureState(context.getSource().getServer());
						state.markCompleted();
						context.getSource().sendSuccess(() -> Component.literal("Preloading stopped"), true);
						return 1;
					}));

			root.then(literal("turbo")
					.executes(context -> {
						CONFIG.turboMode = !CONFIG.turboMode;
						CONFIG.save();
						context.getSource().sendSuccess(() -> Component.literal("Turbo Mode: " + (CONFIG.turboMode ? "ON" : "OFF")), true);
						return 1;
					}));

			root.then(literal("status")
					.executes(context -> {
						ensureState(context.getSource().getServer());
						if (!state.started) {
							context.getSource().sendSuccess(() -> Component.literal("Not started"), false);
						} else if (state.completed) {
							context.getSource().sendSuccess(() -> Component.literal("Completed: " + state.doneCount + "/" + totalChunks), false);
						} else {
							String p = state.paused ? " (PAUSED: " + currentPauseReason + ")" : "";
							context.getSource().sendSuccess(() -> Component.literal(String.format("Progress: %d/%d (Turbo: %b, Dim: %s, Speed: %.1f ch/s)%s", 
									state.doneCount, totalChunks, CONFIG.turboMode, state.dimension, chunksPerSecond, p)), false);
						}
						return 1;
					}));

			dispatcher.register(root);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ensureState(server);
			if (CONFIG.enabled && state != null && !state.completed && !state.started) {
				ServerLevel level = server.getLevel(Level.OVERWORLD);
				if (level != null) {
					BlockPos pos = handler.getPlayer().blockPosition();
					startPreload(level, pos.getX() >> 4, pos.getZ() >> 4);
				}
			}
			sendProgress(handler.getPlayer());
		});

		ServerTickEvents.START_SERVER_TICK.register(server -> {
			if (state != null && state.started && !state.completed) {
				ServerLevel level = getLevelForDimension(server, state.dimension);
				if (level != null) processCompletions(level);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopAllPreloading());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> stopAllPreloading());
	}

	private static void stopAllPreloading() {
		state = null;
		currentServer = null;
		nextRequestIndex = -1;
		inFlightIndices.clear();
		completedIndices.clear();
		pendingCompletionQueue.clear();
		tickCounter = 0;
		lastBroadcastDone = -1;
		lastBroadcastActive = false;
		refillTaskPending.set(false);
	}

	private static void ensureState(MinecraftServer server) {
		currentServer = server;
		if (state == null) {
			state = PreloadState.get(server);
			nextRequestIndex = state != null ? state.doneCount : 0;
		}
	}

	private static int tickCounter = 0;
	private static final int BROADCAST_INTERVAL_TICKS = 5;
	private static int lastBroadcastDone = -1;
	private static boolean lastBroadcastActive = false;

	private void onServerTick(MinecraftServer server) {
		ensureState(server);
		if (state == null || !state.started || state.completed) {
			if (isBenchmarking) {
				long duration = System.currentTimeMillis() - benchmarkStartTime;
				LOGGER.info("Benchmark complete: 121 chunks generated in {}ms", duration);
				server.getPlayerList().broadcastSystemMessage(Component.literal("Benchmark complete: 121 chunks in " + duration + "ms"), false);
				isBenchmarking = false;
				sendDiscordWebhook("Benchmark complete: 121 chunks in " + duration + "ms");
			}
			return;
		}

		ServerLevel currentLevel = getLevelForDimension(server, state.dimension);
		if (currentLevel == null) return;

		processCompletions(currentLevel);

		if (CONFIG.enabled && !state.paused) {
			updateCps();
			handleConsoleLogging();

			if (CONFIG.dryRunMode) {
				showDryRunParticles(currentLevel);
				currentPauseReason = "DRY RUN";
				broadcastProgress(server);
				return;
			}

			if (CONFIG.playerSafetyRadius > 0) {
				requestSafetyChunks(server);
			}

			boolean empty = server.getPlayerCount() == 0;
			boolean isTurbo = CONFIG.turboMode || (CONFIG.autoTurboWhenEmpty && empty);
			boolean tooManyPlayers = CONFIG.onlyPreloadWhenEmpty && !empty;
			boolean lowDisk = isLowDiskSpace(server);
			boolean lowMemory = isLowMemory();
			boolean serverIsBusy = !isTurbo && (CONFIG.adaptiveThrottling
					&& server.getAverageTickTimeNanos() > (long) (CONFIG.busyTickThresholdMs * 1_000_000L));
			boolean lowTps = !isTurbo && (1000.0 / (server.getAverageTickTimeNanos() / 1_000_000.0)) < CONFIG.minTpsThreshold;

			if (lowMemory) currentPauseReason = "LOW RAM";
			else if (tooManyPlayers) currentPauseReason = "PLAYERS ONLINE";
			else if (lowDisk) currentPauseReason = "LOW DISK";
			else if (serverIsBusy) currentPauseReason = "BUSY TICK";
			else if (lowTps) currentPauseReason = "LOW TPS";
			else currentPauseReason = "";

			if (!lowMemory && (isTurbo || (!serverIsBusy && !tooManyPlayers && !lowTps && !lowDisk))) {
				requestMoreChunks(currentLevel, 64);
			}

			if (state.doneCount >= totalChunks && !state.completed) {
				finishDimension(server);
				return;
			}
		} else if (state.paused) {
			currentPauseReason = "MANUAL";
		}

		if (++tickCounter >= BROADCAST_INTERVAL_TICKS) {
			tickCounter = 0;
			broadcastProgress(server);
		}
	}

	private void showDryRunParticles(ServerLevel level) {
		if (tickCounter % 20 != 0) return;
		int r = CONFIG.radius;
		// Circle corners
		int[] dx = {r, -r, 0, 0};
		int[] dz = {0, 0, r, -r};
		for (int i = 0; i < 4; i++) {
			int x = (state.centerX + dx[i]) << 4;
			int z = (state.centerZ + dz[i]) << 4;
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x + 8, 100, z + 8, 50, 2, 2, 2, 0.1);
		}
	}

	private void requestSafetyChunks(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerLevel level = (ServerLevel) player.level();
			ChunkPos center = player.chunkPosition();
			int r = CONFIG.playerSafetyRadius;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					level.getChunkSource().addTicketWithRadius(TicketType.FORCED, new ChunkPos(center.x() + dx, center.z() + dz), 0);
				}
			}
		}
	}

	private void finishDimension(MinecraftServer server) {
		if (state == null) return;
		LOGGER.info("Chunk preload complete for {}: {} chunks generated", state.dimension, totalChunks);
		sendDiscordWebhook("Chunk preloading complete for " + state.dimension + ": " + totalChunks + " chunks");
		
		int currentIndex = CONFIG.dimensions.indexOf(state.dimension);
		if (currentIndex >= 0 && currentIndex < CONFIG.dimensions.size() - 1) {
			String nextDim = CONFIG.dimensions.get(currentIndex + 1);
			ServerLevel next = getLevelForDimension(server, nextDim);
			if (next != null) {
				startPreload(next, state.centerX, state.centerZ);
				broadcastProgress(server);
				return;
			}
		}

		state.markCompleted();
		if (!CONFIG.onCompleteCommand.isEmpty()) {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), CONFIG.onCompleteCommand);
		}
		broadcastProgress(server);
	}

	private static void handleConsoleLogging() {
		if (CONFIG.consoleLogIntervalSeconds <= 0 || state == null) return;
		long now = System.currentTimeMillis();
		if (now - lastConsoleLogTime > CONFIG.consoleLogIntervalSeconds * 1000L) {
			lastConsoleLogTime = now;
			LOGGER.info(String.format("Pregen Progress: %d/%d chunks (%s) | Speed: %.1f ch/s", 
					state.doneCount, totalChunks, state.dimension, chunksPerSecond));
		}
	}

	private static boolean isLowDiskSpace(MinecraftServer server) {
		File worldDir = server.getWorldPath(LevelResource.ROOT).toFile();
		return (worldDir.getFreeSpace() / (1024 * 1024)) < CONFIG.minFreeDiskSpaceMb;
	}

	private static ServerLevel getLevelForDimension(MinecraftServer server, String dimension) {
		Identifier dimId = Identifier.tryParse(dimension);
		if (dimId == null) return server.getLevel(Level.OVERWORLD);
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
	}

	private static void updateCps() {
		if (state == null) return;
		long now = System.currentTimeMillis();
		if (lastTime == 0) { lastTime = now; lastDoneCount = state.doneCount; return; }
		long elapsed = now - lastTime;
		if (elapsed >= 1000) {
			chunksPerSecond = (float) (state.doneCount - lastDoneCount) * 1000f / elapsed;
			lastTime = now; lastDoneCount = state.doneCount;
		}
	}

	private static void startPreload(ServerLevel level, int chunkX, int chunkZ) {
		startPreload(level, chunkX, chunkZ, CONFIG.radius);
	}

	private static void startPreload(ServerLevel level, int chunkX, int chunkZ, int radius) {
		if (state == null) return;
		int previousRadius = CONFIG.radius;
		if (radius > 0) CONFIG.radius = radius;

		String dimId = level.dimension().identifier().toString();
		state.markStarted(chunkX, chunkZ, dimId);
		buildSpiral(CONFIG.radius);
		nextRequestIndex = 0;
		inFlightIndices.clear();
		completedIndices.clear();
		pendingCompletionQueue.clear();
		chunksGeneratedThisSession = 0;
		refillTaskPending.set(false);
		cachedTargetStatus = null;
		LOGGER.info("Starting chunk preload: {} chunks in {} around ({}, {})", totalChunks, dimId, chunkX, chunkZ);
		sendDiscordWebhook("Chunk preloading started in " + dimId + " at " + chunkX + ", " + chunkZ + " (" + totalChunks + " chunks)");

		CONFIG.radius = previousRadius;
	}

	private static void sendDiscordWebhook(String message) {
		String url = CONFIG.discordWebhookUrl;
		if (url == null || url.isEmpty()) return;
		try {
			HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" + message + "\"}")).build();
			HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
		} catch (Exception ignored) {}
	}

	private static boolean isLowMemory() {
		Runtime runtime = Runtime.getRuntime();
		double used = (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();
		boolean low = used > CONFIG.memoryUsageThreshold;
		if (low && CONFIG.aggressiveUnload) System.gc();
		return low;
	}

	private static void processCompletions(ServerLevel overworld) {
		if (state == null) return;
		Integer completedIndex;
		boolean changed = false;
		while ((completedIndex = pendingCompletionQueue.poll()) != null) {
			if (completedIndex < 0 || completedIndex >= totalChunks) continue;
			int chunkX = state.centerX + offsetX[completedIndex];
			int chunkZ = state.centerZ + offsetZ[completedIndex];
			overworld.getChunkSource().removeTicketWithRadius(TicketType.FORCED, new ChunkPos(chunkX, chunkZ), 0);
			if (inFlightIndices.remove(completedIndex)) {
				completedIndices.add(completedIndex); changed = true; chunksGeneratedThisSession++;
				if (CONFIG.restartAfterChunks > 0 && chunksGeneratedThisSession >= CONFIG.restartAfterChunks) overworld.getServer().halt(false);
			}
		}
		if (changed) { state.doneCount = Math.min(completedIndices.size(), totalChunks); state.setDirty(); }
	}

	private static void requestMoreChunks(ServerLevel overworld, int limit) {
		if (state == null || currentServer == null || currentServer.isStopped() || isLowMemory()) return;
		int maxConcurrency = CONFIG.maxConcurrentAsyncChunks;
		
		if (cachedTargetStatus == null) {
			String statusId;
			if (CONFIG.lightingFixMode) statusId = "minecraft:light";
			else if (CONFIG.structureOnlyMode) statusId = "minecraft:structure_starts";
			else statusId = CONFIG.targetStatus;
			
			cachedTargetStatus = BuiltInRegistries.CHUNK_STATUS.get(Identifier.parse(statusId)).map(Holder.Reference::value).orElse(ChunkStatus.FULL);
		}
		ChunkStatus status = cachedTargetStatus;

		// POINT OF INTEREST QUEUE
		if (!CONFIG.pointsOfInterest.isEmpty() && nextRequestIndex == 0) {
			processPOIs(overworld, status);
		}

		int requested = 0;
		while (inFlightIndices.size() < maxConcurrency && nextRequestIndex < totalChunks && requested < limit) {
			int index = nextRequestIndex++;
			if (completedIndices.contains(index)) continue;
			requested++;
			ChunkPos chunkPos = new ChunkPos(state.centerX + offsetX[index], state.centerZ + offsetZ[index]);
			inFlightIndices.add(index);
			overworld.getChunkSource().addTicketWithRadius(TicketType.FORCED, chunkPos, 0);
			overworld.getChunkSource().getChunkFuture(chunkPos.x(), chunkPos.z(), status, true).whenComplete((result, throwable) -> {
				pendingCompletionQueue.add(index);
				if (CONFIG.immediateRefill && currentServer != null && !currentServer.isStopped() && refillTaskPending.compareAndSet(false, true)) {
					currentServer.execute(() -> {
						refillTaskPending.set(false);
						if (currentServer == null || currentServer.isStopped()) return;
						if (state != null && state.started && !state.completed) {
							ServerLevel level = getLevelForDimension(currentServer, state.dimension);
							if (level != null) { processCompletions(level); requestMoreChunks(level, 16); }
						}
					});
				}
			});
		}
	}

	private static void processPOIs(ServerLevel level, ChunkStatus status) {
		for (String poi : CONFIG.pointsOfInterest) {
			try {
				String[] parts = poi.split(",");
				int cx = Integer.parseInt(parts[0].trim()) >> 4;
				int cz = Integer.parseInt(parts[1].trim()) >> 4;
				level.getChunkSource().addTicketWithRadius(TicketType.FORCED, new ChunkPos(cx, cz), 0);
				level.getChunkSource().getChunkFuture(cx, cz, status, true);
			} catch (Exception ignored) {}
		}
	}

	private static void broadcastProgress(MinecraftServer server) {
		if (state == null) return;
		boolean active = CONFIG.enabled && state.started && !state.completed;
		if (state.doneCount == lastBroadcastDone && active == lastBroadcastActive) return;
		lastBroadcastDone = state.doneCount; lastBroadcastActive = active;
		PreloadProgressPayload payload = new PreloadProgressPayload(state.doneCount, totalChunks, active, state.paused, state.dimension, chunksPerSecond, currentPauseReason);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, PreloadProgressPayload.TYPE)) ServerPlayNetworking.send(player, payload);
		}
	}

	private static void sendProgress(ServerPlayer player) {
		if (state == null) return;
		PreloadProgressPayload payload = new PreloadProgressPayload(state.doneCount, totalChunks, CONFIG.enabled && state.started && !state.completed, state.paused, state.dimension, chunksPerSecond, currentPauseReason);
		if (ServerPlayNetworking.canSend(player, PreloadProgressPayload.TYPE)) ServerPlayNetworking.send(player, payload);
	}

	public static void rebuildSpiral() { buildSpiral(CONFIG.radius); }

	private static void buildSpiral(int radius) {
		int side = 2 * radius + 1;
		short[] tmpX = new short[side * side]; short[] tmpZ = new short[side * side];
		int count = 0; long radiusSquared = (long) radius * radius;
		tmpX[count] = 0; tmpZ[count] = 0; count++;
		for (int r = 1; r <= radius; r++) {
			for (int dx = -r; dx <= r; dx++) {
				if (CONFIG.shape == ChunkPreloadConfig.Shape.SQUARE || ((long) dx * dx + (long) r * r <= radiusSquared)) {
					tmpX[count] = (short) dx; tmpZ[count] = (short) -r; count++;
					tmpX[count] = (short) dx; tmpZ[count] = (short) r; count++;
				}
			}
			for (int dz = -r + 1; dz <= r - 1; dz++) {
				if (CONFIG.shape == ChunkPreloadConfig.Shape.SQUARE || ((long) r * r + (long) dz * dz <= radiusSquared)) {
					tmpX[count] = (short) -r; tmpZ[count] = (short) dz; count++;
					tmpX[count] = (short) r; tmpZ[count] = (short) dz; count++;
				}
			}
		}
		offsetX = Arrays.copyOf(tmpX, count); offsetZ = Arrays.copyOf(tmpZ, count); totalChunks = count;
		LOGGER.info("Chunk Preloader configured for a {}-chunk {} ({} chunks total)", radius, CONFIG.shape, totalChunks);
	}
}
