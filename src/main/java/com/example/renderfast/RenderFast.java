package com.example.renderfast;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class RenderFast implements ModInitializer {
	public static final String MOD_ID = "renderfast";
	public static final Logger LOGGER = LoggerFactory.getLogger("RenderFast");
	public static RenderFastConfig CONFIG;

	private static short[] offsetX;
	private static short[] offsetZ;
	private static int totalChunks;

	private static RenderFastState state;
	private static ChunkStatus cachedTargetStatus;
	private static String currentPauseReason = "";

	private static int chunksGeneratedThisSession = 0;
	private static int chunksSinceLastSave = 0;
	private static long sessionStartTime = 0;
	private static long benchmarkStartTime = 0;
	private static boolean isBenchmarking = false;

	private static int nextRequestIndex = -1;
	private static final Set<Integer> inFlightIndices = Collections.synchronizedSet(new HashSet<>());
	private static final Map<Integer, Long> inFlightStartTimes = new ConcurrentHashMap<>();
	private static final Set<Integer> completedIndices = Collections.synchronizedSet(new HashSet<>());
	private static final ConcurrentLinkedQueue<Integer> pendingCompletionQueue = new ConcurrentLinkedQueue<>();
	private static final AtomicBoolean refillTaskPending = new AtomicBoolean(false);

	private static final Deque<Long> timeWindow = new ArrayDeque<>();
	private static final Deque<Integer> countWindow = new ArrayDeque<>();
	private static float currentCps = 0;

	private static MinecraftServer currentServer;
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

	@Override
	public void onInitialize() {
		CONFIG = RenderFastConfig.load();
		buildSpiral(CONFIG.radius);

		PayloadTypeRegistry.clientboundPlay().register(RenderFastProgressPayload.TYPE, RenderFastProgressPayload.CODEC);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			var root = literal("renderfast").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_ADMIN));

			root.then(literal("start")
				.executes(c -> {
					ensureState(c.getSource().getServer());
					BlockPos p = BlockPos.containing(c.getSource().getPosition());
					startPreload(c.getSource().getLevel(), p.getX() >> 4, p.getZ() >> 4);
					c.getSource().sendSuccess(() -> Component.literal("Started preloading around your position"), true);
					return 1;
				})
				.then(argument("radius", IntegerArgumentType.integer(1)).executes(c -> {
					int r = IntegerArgumentType.getInteger(c, "radius");
					ensureState(c.getSource().getServer());
					BlockPos p = BlockPos.containing(c.getSource().getPosition());
					startPreload(c.getSource().getLevel(), p.getX() >> 4, p.getZ() >> 4, r);
					c.getSource().sendSuccess(() -> Component.literal("Started preloading with radius " + r), true);
					return 1;
				}).then(argument("x", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer()).executes(c -> {
					int r = IntegerArgumentType.getInteger(c, "radius");
					int x = IntegerArgumentType.getInteger(c, "x");
					int z = IntegerArgumentType.getInteger(c, "z");
					ensureState(c.getSource().getServer());
					startPreload(c.getSource().getLevel(), x >> 4, z >> 4, r);
					c.getSource().sendSuccess(() -> Component.literal("Started preloading at (" + x + ", " + z + ") with radius " + r), true);
					return 1;
				})))));

			root.then(literal("pause").executes(c -> {
				ensureState(c.getSource().getServer()); state.setPaused(true); currentPauseReason = "MANUAL";
				c.getSource().sendSuccess(() -> Component.literal("Preloading paused"), true); return 1;
			}));

			root.then(literal("resume").executes(c -> {
				ensureState(c.getSource().getServer()); state.setPaused(false); currentPauseReason = "";
				c.getSource().sendSuccess(() -> Component.literal("Preloading resumed"), true); return 1;
			}));

			root.then(literal("reset").executes(c -> {
				ensureState(c.getSource().getServer());
				if (state != null) {
					state.started = false;
					state.completed = false;
					state.paused = false;
					state.doneCount = 0;
					state.radius = Math.max(1, CONFIG.radius);
					state.setDirty();
					currentPauseReason = "";
					inFlightIndices.clear();
					inFlightStartTimes.clear();
					completedIndices.clear();
					pendingCompletionQueue.clear();
				}
				BlockPos p = BlockPos.containing(c.getSource().getPosition());
				startPreload(c.getSource().getLevel(), p.getX() >> 4, p.getZ() >> 4);
				c.getSource().sendSuccess(() -> Component.literal("Pregen progress reset."), true); return 1;
			}));

			root.then(literal("border").executes(c -> {
				ensureState(c.getSource().getServer());
				ServerLevel level = c.getSource().getLevel();
				var border = level.getWorldBorder();
				int minX = (int) Math.floor(border.getMinX());
				int maxX = (int) Math.floor(border.getMaxX());
				int minZ = (int) Math.floor(border.getMinZ());
				int maxZ = (int) Math.floor(border.getMaxZ());
				int centerX = (minX + maxX) / 2;
				int centerZ = (minZ + maxZ) / 2;
				int radius = Math.max(1, (int) Math.min(Math.max((maxX - minX), (maxZ - minZ)) / 2.0, CONFIG.radius));
				startPreload(level, centerX >> 4, centerZ >> 4, radius);
				c.getSource().sendSuccess(() -> Component.literal("Started border preloading with radius " + radius), true);
				return 1;
			}));

			root.then(literal("status").executes(c -> {
				ensureState(c.getSource().getServer());
				if (!state.started) { c.getSource().sendSuccess(() -> Component.literal("Not started"), false); }
				else if (state.completed) { c.getSource().sendSuccess(() -> Component.literal("Completed: " + state.doneCount + "/" + totalChunks), false); }
				else {
					int p = (int)(((float)state.doneCount / totalChunks) * 100);
					String bar = "=".repeat(p/5) + "-".repeat(20 - (p/5));
					String msg = String.format("§6RenderFast Status:§r\n" +
							"§7Progress:§r [%s] %d%% (%d/%d)\n" +
							"§7Dimension:§r %s\n" +
							"§7Speed:§r %.1f ch/s | §7ETA:§r %s\n" +
							"§7Throttled:§r %s", 
							bar, p, state.doneCount, totalChunks, state.dimension, currentCps, formatEta(), 
							currentPauseReason.isEmpty() ? "§aNone§r" : "§c" + currentPauseReason + "§r");
					c.getSource().sendSuccess(() -> Component.literal(msg), false);
				}
				return 1;
			}));

			root.then(literal("estimate").executes(c -> {
				double mb = (totalChunks * 10240.0) / (1024.0 * 1024.0);
				c.getSource().sendSuccess(() -> Component.literal(String.format("Estimate: ~%.2f MB disk space.", mb)), false); return 1;
			}));

			root.then(literal("dryrun").executes(c -> {
				CONFIG.dryRunMode = !CONFIG.dryRunMode; CONFIG.save();
				c.getSource().sendSuccess(() -> Component.literal("Dry Run Mode: " + (CONFIG.dryRunMode ? "ON" : "OFF")), true); return 1;
			}));

			root.then(literal("stop").executes(c -> {
				ensureState(c.getSource().getServer()); state.markCompleted();
				c.getSource().sendSuccess(() -> Component.literal("Preloading stopped"), true); return 1;
			}));

			root.then(literal("turbo").executes(c -> {
				CONFIG.turboMode = !CONFIG.turboMode; CONFIG.save();
				c.getSource().sendSuccess(() -> Component.literal("Turbo Mode: " + (CONFIG.turboMode ? "ON" : "OFF")), true); return 1;
			}));

			dispatcher.register(root);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ensureState(server);
			if (CONFIG.enabled && state != null && !state.completed && !state.started) {
				ServerLevel level = server.getLevel(Level.OVERWORLD);
				if (level != null) startPreload(level, handler.getPlayer().blockPosition().getX() >> 4, handler.getPlayer().blockPosition().getZ() >> 4);
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
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> stopAllPreloading());
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> stopAllPreloading());
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	private static void stopAllPreloading() {
		state = null; currentServer = null; nextRequestIndex = -1;
		inFlightIndices.clear(); inFlightStartTimes.clear(); completedIndices.clear(); pendingCompletionQueue.clear();
		tickCounter = 0; lastBroadcastDone = -1; lastBroadcastActive = false; refillTaskPending.set(false);
	}

	private static void ensureState(MinecraftServer server) {
		currentServer = server;
		if (state == null) {
			state = RenderFastState.get(server);
			if (state.started) {
				buildSpiral(state.radius);
				nextRequestIndex = state.doneCount;
			}
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
				long d = System.currentTimeMillis() - benchmarkStartTime;
				LOGGER.info("Benchmark complete: 121 chunks in {}ms", d);
				server.getPlayerList().broadcastSystemMessage(Component.literal("Benchmark complete: 121 chunks in " + d + "ms"), false);
				isBenchmarking = false; sendDiscordWebhook("Benchmark complete: 121 chunks in " + d + "ms");
			}
			return;
		}

		ServerLevel currentLevel = getLevelForDimension(server, state.dimension);
		if (currentLevel == null) return;

		processCompletions(currentLevel);
		runWatchdog();

		if (CONFIG.enabled && !state.paused) {
			updateCps();
			if (!isInsideWorkHours()) {
				currentPauseReason = "OFF HOURS";
				broadcastProgress(server);
				return;
			}
			if (CONFIG.playerSafetyRadius > 0) requestSafetyChunks(server);

			boolean empty = server.getPlayerCount() == 0;
			boolean isTurbo = CONFIG.turboMode || (CONFIG.autoTurboWhenEmpty && empty);
			boolean tooManyPlayers = CONFIG.onlyPreloadWhenEmpty && !empty;
			boolean lowDisk = (server.getWorldPath(LevelResource.ROOT).toFile().getFreeSpace() / (1024 * 1024)) < CONFIG.minFreeDiskSpaceMb;
			boolean lowMemory = isLowMemory();
			long avgTickNanos = server.getAverageTickTimeNanos();
			boolean busy = !isTurbo && (CONFIG.adaptiveThrottling && avgTickNanos > (long) (CONFIG.busyTickThresholdMs * 1_000_000L));
			boolean lowTps = !isTurbo && (1000.0 / (avgTickNanos / 1_000_000.0)) < CONFIG.minTpsThreshold;

			if (lowMemory) currentPauseReason = "LOW RAM";
			else if (tooManyPlayers) currentPauseReason = "PLAYERS ONLINE";
			else if (lowDisk) currentPauseReason = "LOW DISK";
			else if (busy) currentPauseReason = "BUSY TICK (" + (avgTickNanos / 1_000_000L) + "ms)";
			else if (lowTps) currentPauseReason = "LOW TPS";
			else if (CONFIG.dryRunMode) { currentPauseReason = "DRY RUN"; showDryRunParticles(currentLevel); }
			else {
				currentPauseReason = "";
				int limit = isTurbo ? 128 : (CONFIG.cpuUsageLevel == RenderFastConfig.CpuUsageLevel.LOW ? 16 : 64);
				requestMoreChunks(currentLevel, limit);
			}

			if (state.doneCount >= totalChunks && !state.completed) { finishDimension(server); return; }
		} else if (state.paused) currentPauseReason = "MANUAL";

		if (++tickCounter >= BROADCAST_INTERVAL_TICKS) { tickCounter = 0; broadcastProgress(server); }
	}

	private boolean isInsideWorkHours() {
		try {
			LocalTime now = LocalTime.now();
			LocalTime start = LocalTime.parse(CONFIG.startTime, DateTimeFormatter.ofPattern("HH:mm"));
			LocalTime end = LocalTime.parse(CONFIG.endTime, DateTimeFormatter.ofPattern("HH:mm"));
			if (start.isBefore(end)) return !now.isBefore(start) && !now.isAfter(end);
			else return !now.isBefore(start) || !now.isAfter(end);
		} catch (Exception e) { return true; }
	}

	private void runWatchdog() {
		long now = System.currentTimeMillis();
		inFlightStartTimes.forEach((idx, start) -> {
			if (now - start > CONFIG.watchdogTimeoutSeconds * 1000L) {
				LOGGER.warn("Watchdog: Chunk index {} timed out after {}s. Retrying...", idx, CONFIG.watchdogTimeoutSeconds);
				inFlightIndices.remove(idx);
				inFlightStartTimes.remove(idx);
			}
		});
	}

	private void showDryRunParticles(ServerLevel level) {
		if (tickCounter % 20 != 0) return;
		int r = state.radius; int[] dx = {r, -r, 0, 0}; int[] dz = {0, 0, r, -r};
		for (int i = 0; i < 4; i++) {
			int x = (state.centerX + dx[i]) << 4; int z = (state.centerZ + dz[i]) << 4;
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x + 8, 100, z + 8, 50, 2, 2, 2, 0.1);
		}
	}

	private void requestSafetyChunks(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ServerLevel level = (ServerLevel) p.level(); ChunkPos center = p.chunkPosition(); int r = CONFIG.playerSafetyRadius;
			for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
				level.getChunkSource().addTicketWithRadius(TicketType.FORCED, new ChunkPos(center.x() + dx, center.z() + dz), 0);
			}
		}
	}

	private void finishDimension(MinecraftServer server) {
		if (state == null) return;
		long time = (System.currentTimeMillis() - sessionStartTime) / 1000;
		String report = String.format("Pregen complete for %s: %d chunks in %ds (%.1f ch/s)", 
				state.dimension, totalChunks, time, (float) totalChunks / Math.max(1, time));
		LOGGER.info(report); sendDiscordWebhook(report);
		server.getPlayerList().getPlayers().forEach(p -> ((ServerLevel)p.level()).playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.0f, 1.0f));
		
		int idx = CONFIG.dimensions.indexOf(state.dimension);
		if (idx >= 0 && idx < CONFIG.dimensions.size() - 1) {
			ServerLevel next = getLevelForDimension(server, CONFIG.dimensions.get(idx + 1));
			if (next != null) { startPreload(next, state.centerX, state.centerZ, state.radius); broadcastProgress(server); return; }
		}
		state.markCompleted();
		if (!CONFIG.onCompleteCommand.isEmpty()) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), CONFIG.onCompleteCommand);
		broadcastProgress(server);
	}

	private static ServerLevel getLevelForDimension(MinecraftServer s, String d) {
		Identifier id = Identifier.tryParse(d); if (id == null) return s.getLevel(Level.OVERWORLD);
		return s.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	private static void updateCps() {
		if (state == null) return;
		long now = System.currentTimeMillis();
		timeWindow.addLast(now);
		countWindow.addLast(state.doneCount);
		while (!timeWindow.isEmpty() && now - timeWindow.getFirst() > CONFIG.smoothEtaWindowSeconds * 1000L) {
			timeWindow.removeFirst(); countWindow.removeFirst();
		}
		if (timeWindow.size() > 1) {
			long elapsed = now - timeWindow.getFirst();
			int count = state.doneCount - countWindow.getFirst();
			currentCps = (float) count * 1000f / elapsed;
		}
	}

	private String formatEta() {
		if (currentCps <= 0) return "N/A";
		long remaining = (long)((totalChunks - state.doneCount) / currentCps);
		return String.format("%dm %ds", remaining / 60, remaining % 60);
	}

	private static void startPreload(ServerLevel level, int cx, int cz) { startPreload(level, cx, cz, CONFIG.radius); }

	private static void startPreload(ServerLevel level, int cx, int cz, int r) {
		if (state == null) return;
		int safeRadius = Math.max(1, Math.min(r <= 0 ? CONFIG.radius : r, 2048));
		String dimId = level.dimension().identifier().toString();
		state.markStarted(cx, cz, dimId, safeRadius); buildSpiral(safeRadius);
		sessionStartTime = System.currentTimeMillis(); nextRequestIndex = 0;
		inFlightIndices.clear(); inFlightStartTimes.clear(); completedIndices.clear(); pendingCompletionQueue.clear();
		chunksGeneratedThisSession = 0; chunksSinceLastSave = 0; refillTaskPending.set(false); cachedTargetStatus = null;
		LOGGER.info("Starting chunk preload: {} chunks in {} around ({}, {}) with radius {}", totalChunks, dimId, cx, cz, r);
		sendDiscordWebhook("Chunk preloading started in " + dimId + " (" + totalChunks + " chunks)");
	}

	private static void sendDiscordWebhook(String m) {
		if (CONFIG.discordWebhookUrl == null || CONFIG.discordWebhookUrl.isEmpty()) return;
		try {
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(CONFIG.discordWebhookUrl)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" + m + "\"}")).build();
			HTTP_CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		} catch (Exception ignored) {}
	}

	private static boolean isLowMemory() {
		Runtime rt = Runtime.getRuntime(); double u = (double) (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
		boolean low = u > CONFIG.memoryUsageThreshold;
		if (low && CONFIG.aggressiveUnload) System.gc();
		return low;
	}

	private static void processCompletions(ServerLevel level) {
		if (state == null) return;
		Integer idx; boolean changed = false;
		while ((idx = pendingCompletionQueue.poll()) != null) {
			if (idx < 0 || idx >= totalChunks) continue;
			level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, new ChunkPos(state.centerX + offsetX[idx], state.centerZ + offsetZ[idx]), 0);
			if (inFlightIndices.remove(idx)) {
				inFlightStartTimes.remove(idx);
				completedIndices.add(idx); changed = true; chunksGeneratedThisSession++; chunksSinceLastSave++;
				if (CONFIG.saveIntervalChunks > 0 && chunksSinceLastSave >= CONFIG.saveIntervalChunks) {
					level.getServer().saveEverything(true, false, true);
					chunksSinceLastSave = 0;
				}
				if (CONFIG.restartAfterChunks > 0 && chunksGeneratedThisSession >= CONFIG.restartAfterChunks) level.getServer().halt(false);
			}
		}
		if (changed) { state.doneCount = Math.min(completedIndices.size(), totalChunks); state.setDirty(); }
	}

	private static void requestMoreChunks(ServerLevel level, int limit) {
		if (state == null || currentServer == null || currentServer.isStopped() || isLowMemory()) return;
		if (cachedTargetStatus == null) {
			String sid = CONFIG.lightingFixMode ? "minecraft:light" : (CONFIG.structureOnlyMode ? "minecraft:structure_starts" : CONFIG.targetStatus);
			cachedTargetStatus = BuiltInRegistries.CHUNK_STATUS.get(Identifier.parse(sid)).map(Holder.Reference::value).orElse(ChunkStatus.FULL);
		}
		if (!CONFIG.pointsOfInterest.isEmpty() && nextRequestIndex == 0) processPOIs(level, cachedTargetStatus);
		int req = 0;
		while (inFlightIndices.size() < CONFIG.maxConcurrentAsyncChunks && nextRequestIndex < totalChunks && req < limit) {
			int i = nextRequestIndex++; if (completedIndices.contains(i)) continue;
			req++; ChunkPos cp = new ChunkPos(state.centerX + offsetX[i], state.centerZ + offsetZ[i]);
			inFlightIndices.add(i); inFlightStartTimes.put(i, System.currentTimeMillis());
			level.getChunkSource().addTicketWithRadius(TicketType.FORCED, cp, 0);
			level.getChunkSource().getChunkFuture(cp.x(), cp.z(), cachedTargetStatus, true).whenComplete((res, thr) -> {
				pendingCompletionQueue.add(i);
				if (CONFIG.immediateRefill && currentServer != null && !currentServer.isStopped() && refillTaskPending.compareAndSet(false, true)) {
					// Check if server is already under heavy load before scheduling refill
					if (!CONFIG.adaptiveThrottling || currentServer.getAverageTickTimeNanos() < (long)(CONFIG.busyTickThresholdMs * 0.8 * 1_000_000L)) {
						currentServer.execute(() -> {
							refillTaskPending.set(false);
							if (currentServer != null && !currentServer.isStopped() && state != null && state.started && !state.completed) {
								ServerLevel l = getLevelForDimension(currentServer, state.dimension);
								if (l != null) { processCompletions(l); requestMoreChunks(l, 8); }
							}
						});
					} else {
						refillTaskPending.set(false); // Drop this refill request to allow server to breathe
					}
				}
			});
		}
	}

	private static void processPOIs(ServerLevel l, ChunkStatus s) {
		for (String poi : CONFIG.pointsOfInterest) try {
			String[] p = poi.split(","); int x = Integer.parseInt(p[0].trim()) >> 4, z = Integer.parseInt(p[1].trim()) >> 4;
			l.getChunkSource().addTicketWithRadius(TicketType.FORCED, new ChunkPos(x, z), 0);
			l.getChunkSource().getChunkFuture(x, z, s, true);
		} catch (Exception ignored) {}
	}

	private static void broadcastProgress(MinecraftServer s) {
		if (state == null) return;
		boolean a = CONFIG.enabled && state.started && !state.completed;
		if (state.doneCount == lastBroadcastDone && a == lastBroadcastActive) return;
		lastBroadcastDone = state.doneCount; lastBroadcastActive = a;
		RenderFastProgressPayload p = new RenderFastProgressPayload(state.doneCount, totalChunks, a, state.paused, state.dimension, currentCps, currentPauseReason, getMapData());
		s.getPlayerList().getPlayers().forEach(pl -> { if (ServerPlayNetworking.canSend(pl, RenderFastProgressPayload.TYPE)) ServerPlayNetworking.send(pl, p); });
	}

	private static byte[] getMapData() {
		byte[] data = new byte[50]; // 20x20 bitset
		if (totalChunks <= 0 || state == null) return data;
		int size = 20;
		for (int i = 0; i < totalChunks; i++) {
			if (completedIndices.contains(i)) {
				int r = state.radius;
				int xGrid = (int)(((offsetX[i] + r) / (double)(2 * r)) * size);
				int zGrid = (int)(((offsetZ[i] + r) / (double)(2 * r)) * size);
				xGrid = Math.clamp(xGrid, 0, size - 1);
				zGrid = Math.clamp(zGrid, 0, size - 1);
				int bitIdx = zGrid * size + xGrid;
				data[bitIdx / 8] |= (byte)(1 << (bitIdx % 8));
			}
		}
		return data;
	}

	private static void sendProgress(ServerPlayer pl) {
		if (state == null) return;
		RenderFastProgressPayload p = new RenderFastProgressPayload(state.doneCount, totalChunks, CONFIG.enabled && state.started && !state.completed, state.paused, state.dimension, currentCps, currentPauseReason, getMapData());
		if (ServerPlayNetworking.canSend(pl, RenderFastProgressPayload.TYPE)) ServerPlayNetworking.send(pl, p);
	}

	public static void rebuildSpiral() { 
		if (state != null && state.started && !state.completed) buildSpiral(state.radius); 
		else buildSpiral(CONFIG.radius);
	}

	private static void buildSpiral(int r) {
		int side = 2 * r + 1; short[] tx = new short[side * side], tz = new short[side * side];
		int c = 0; long r2 = (long) r * r; tx[c] = 0; tz[c] = 0; c++;
		for (int ir = 1; ir <= r; ir++) {
			for (int dx = -ir; dx <= ir; dx++) if (CONFIG.shape == RenderFastConfig.Shape.SQUARE || ((long) dx * dx + (long) ir * ir <= r2)) {
				tx[c] = (short) dx; tz[c] = (short) -ir; c++; tx[c] = (short) dx; tz[c] = (short) ir; c++;
			}
			for (int dz = -ir + 1; dz <= ir - 1; dz++) if (CONFIG.shape == RenderFastConfig.Shape.SQUARE || ((long) ir * ir + (long) dz * dz <= r2)) {
				tx[c] = (short) -ir; tz[c] = (short) dz; c++; tx[c] = (short) ir; tz[c] = (short) dz; c++;
			}
		}
		offsetX = Arrays.copyOf(tx, c); offsetZ = Arrays.copyOf(tz, c); totalChunks = c;
	}
}
