package com.example.renderfast;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
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

	private static int chunksSinceLastSave = 0;
	private static long sessionStartTime = 0;

	private static int nextRequestIndex = -1;
	private static final Set<Integer> inFlightIndices = Collections.synchronizedSet(new HashSet<>());
	private static final Map<Integer, Long> inFlightStartTimes = new ConcurrentHashMap<>();
	private static final Set<Integer> completedIndices = Collections.synchronizedSet(new HashSet<>());
	private static final ConcurrentLinkedQueue<Integer> pendingCompletionQueue = new ConcurrentLinkedQueue<>();

	private static final Deque<Long> timeWindow = new ArrayDeque<>();
	private static final Deque<Integer> countWindow = new ArrayDeque<>();
	private static float currentCps = 0;

	private static long currentTickStartTime = 0;
	private static boolean inLagRecovery = false;
	private static MinecraftServer currentServer;
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

	@Override
	public void onInitialize() {
		CONFIG = RenderFastConfig.load();
		buildSpiral(CONFIG.radius);

		PayloadTypeRegistry.clientboundPlay().register(RenderFastProgressPayload.TYPE, RenderFastProgressPayload.CODEC);
		CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> registerCommands(dispatcher));

		ServerPlayConnectionEvents.JOIN.register((handler, _, server) -> {
			ensureState(server);
			sendProgress(handler.getPlayer());
		});

		ServerTickEvents.START_SERVER_TICK.register(server -> {
			currentTickStartTime = System.currentTimeMillis();
			if (state != null && state.started && !state.completed) {
				ServerLevel level = getLevelForDimension(server, state.dimension);
				if (level != null) processCompletions(level);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
		ServerLifecycleEvents.SERVER_STOPPING.register(_ -> stopAllPreloading());
		ServerLifecycleEvents.SERVER_STOPPED.register(_ -> stopAllPreloading());
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = literal("renderfast").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_ADMIN));

		root.then(literal("start")
			.executes(c -> {
				ensureState(c.getSource().getServer());
				BlockPos p = BlockPos.containing(c.getSource().getPosition());
				if (canContinue(c.getSource().getLevel(), p.getX() >> 4, p.getZ() >> 4, CONFIG.radius)) {
					if (state.paused) { state.setPaused(false); currentPauseReason = ""; c.getSource().sendSuccess(() -> Component.literal("Resuming task..."), true); }
					else c.getSource().sendSuccess(() -> Component.literal("Already running here."), false);
					return 1;
				}
				startPreload(c.getSource().getLevel(), p.getX() >> 4, p.getZ() >> 4);
				c.getSource().sendSuccess(() -> Component.literal("Started preload."), true);
				return 1;
			})
			.then(argument("radius", IntegerArgumentType.integer(1)).executes(c -> {
				int r = IntegerArgumentType.getInteger(c, "radius");
				ensureState(c.getSource().getServer());
				BlockPos p = BlockPos.containing(c.getSource().getPosition());
				startPreload(c.getSource().getLevel(), p.getX() >> 4, p.getZ() >> 4, r);
				c.getSource().sendSuccess(() -> Component.literal("Started radius " + r), true);
				return 1;
			})));

		root.then(literal("pause").executes(c -> {
			ensureState(c.getSource().getServer());
			if (!state.started || state.completed) { c.getSource().sendFailure(Component.literal("No task active.")); return 0; }
			state.setPaused(true); currentPauseReason = "MANUAL";
			c.getSource().sendSuccess(() -> Component.literal("Paused"), true); return 1;
		}));

		root.then(literal("resume").executes(c -> {
			ensureState(c.getSource().getServer());
			if (!state.started || state.completed) { c.getSource().sendFailure(Component.literal("No task active.")); return 0; }
			state.setPaused(false); currentPauseReason = "";
			c.getSource().sendSuccess(() -> Component.literal("Resumed"), true); return 1;
		}));

		root.then(literal("reset").executes(c -> {
			ensureState(c.getSource().getServer());
			if (!state.started) { c.getSource().sendFailure(Component.literal("No task to reset.")); return 0; }
			BlockPos p = BlockPos.containing(c.getSource().getPosition());
			startPreload(c.getSource().getLevel(), p.getX() >> 4, p.getZ() >> 4, state.radius);
			c.getSource().sendSuccess(() -> Component.literal("Reset and restarted."), true); return 1;
		}));

		root.then(literal("stop").executes(c -> {
			ensureState(c.getSource().getServer());
			if (!state.started || state.completed) { c.getSource().sendFailure(Component.literal("No active task.")); return 0; }
			state.markCompleted(); c.getSource().sendSuccess(() -> Component.literal("Stopped."), true); return 1;
		}));

		root.then(literal("status").executes(c -> {
			ensureState(c.getSource().getServer());
			if (!state.started) { c.getSource().sendSuccess(() -> Component.literal("Not started"), false); }
			else if (state.completed) { c.getSource().sendSuccess(() -> Component.literal("Completed: " + state.doneCount + "/" + totalChunks), false); }
			else {
				int p = (int)(((float)state.doneCount / totalChunks) * 100);
				String msg = String.format("§6Status:§r %d%% (%d/%d) | Speed: %.1f ch/s | Throttled: %s", 
						p, state.doneCount, totalChunks, currentCps, currentPauseReason.isEmpty() ? "None" : currentPauseReason);
				c.getSource().sendSuccess(() -> Component.literal(msg), false);
			}
			return 1;
		}));

		root.then(literal("turbo").executes(c -> {
			CONFIG.turboMode = !CONFIG.turboMode; CONFIG.save();
			c.getSource().sendSuccess(() -> Component.literal("Turbo Mode: " + (CONFIG.turboMode ? "ON" : "OFF")), true); return 1;
		}));

		var config = literal("config");
		config.then(literal("radius").then(argument("v", IntegerArgumentType.integer(1, 2048)).executes(c -> { CONFIG.radius = IntegerArgumentType.getInteger(c, "v"); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Radius: " + CONFIG.radius), true); return 1; })));
		config.then(literal("enable").executes(c -> { CONFIG.enabled = !CONFIG.enabled; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Enabled: " + (CONFIG.enabled ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("hud").executes(c -> { CONFIG.showHud = !CONFIG.showHud; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("HUD: " + (CONFIG.showHud ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("minimap").executes(c -> { CONFIG.showMiniMap = !CONFIG.showMiniMap; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("MiniMap: " + (CONFIG.showMiniMap ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("metrics").executes(c -> { CONFIG.showHudMetrics = !CONFIG.showHudMetrics; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Metrics: " + (CONFIG.showHudMetrics ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("statusmsg").executes(c -> { CONFIG.showStatusMessages = !CONFIG.showStatusMessages; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Status Messages: " + (CONFIG.showStatusMessages ? "ON" : "OFF")), true); return 1; }));
		
		config.then(literal("adaptive").executes(c -> { CONFIG.adaptiveThrottling = !CONFIG.adaptiveThrottling; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Adaptive Throttling: " + (CONFIG.adaptiveThrottling ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("watchdog").executes(c -> { CONFIG.watchdogBreather = !CONFIG.watchdogBreather; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Watchdog Breather: " + (CONFIG.watchdogBreather ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("breather").then(argument("ms", IntegerArgumentType.integer(1)).executes(c -> { CONFIG.breatherThresholdMs = (double) IntegerArgumentType.getInteger(c, "ms"); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Breather Threshold: " + CONFIG.breatherThresholdMs + "ms"), true); return 1; })));
		config.then(literal("recovery").then(argument("ms", IntegerArgumentType.integer(1)).executes(c -> { CONFIG.recoveryThresholdMs = (double) IntegerArgumentType.getInteger(c, "ms"); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Recovery Threshold: " + CONFIG.recoveryThresholdMs + "ms"), true); return 1; })));
		
		config.then(literal("pauseoffline").executes(c -> { CONFIG.pauseWhenEmpty = !CONFIG.pauseWhenEmpty; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Pause Offline: " + (CONFIG.pauseWhenEmpty ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("pauseonline").executes(c -> { CONFIG.onlyPreloadWhenEmpty = !CONFIG.onlyPreloadWhenEmpty; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Pause Online: " + (CONFIG.onlyPreloadWhenEmpty ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("autoturbo").executes(c -> { CONFIG.autoTurboWhenEmpty = !CONFIG.autoTurboWhenEmpty; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Auto-Turbo: " + (CONFIG.autoTurboWhenEmpty ? "ON" : "OFF")), true); return 1; }));
		
		config.then(literal("voxy").executes(c -> { CONFIG.voxyIntegration = !CONFIG.voxyIntegration; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Voxy Integration: " + (CONFIG.voxyIntegration ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("dh").executes(c -> { CONFIG.distantHorizonsIntegration = !CONFIG.distantHorizonsIntegration; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Distant Horizons Integration: " + (CONFIG.distantHorizonsIntegration ? "ON" : "OFF")), true); return 1; }));

		config.then(literal("ram").then(argument("pct", IntegerArgumentType.integer(1, 100)).executes(c -> { CONFIG.memoryUsageThreshold = IntegerArgumentType.getInteger(c, "pct") / 100.0; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("RAM limit: " + (CONFIG.memoryUsageThreshold * 100) + "%"), true); return 1; })));
		config.then(literal("disk").then(argument("mb", IntegerArgumentType.integer(1)).executes(c -> { CONFIG.minFreeDiskSpaceMb = IntegerArgumentType.getInteger(c, "mb"); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Min Disk: " + CONFIG.minFreeDiskSpaceMb + "MB"), true); return 1; })));
		config.then(literal("timeout").then(argument("sec", IntegerArgumentType.integer(1)).executes(c -> { CONFIG.watchdogTimeoutSeconds = IntegerArgumentType.getInteger(c, "sec"); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Timeout: " + CONFIG.watchdogTimeoutSeconds + "s"), true); return 1; })));
		config.then(literal("status").then(argument("val", StringArgumentType.string()).executes(c -> { CONFIG.targetStatus = StringArgumentType.getString(c, "val"); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Target Status: " + CONFIG.targetStatus), true); return 1; })));
		config.then(literal("dimensions").then(argument("list", StringArgumentType.greedyString()).executes(c -> { CONFIG.dimensions = new ArrayList<>(Arrays.asList(StringArgumentType.getString(c, "list").split(","))); CONFIG.dimensions.replaceAll(String::trim); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Dimensions updated."), true); return 1; })));
		config.then(literal("saveinterval").then(argument("v", IntegerArgumentType.integer(0)).executes(c -> { CONFIG.saveIntervalChunks = IntegerArgumentType.getInteger(c, "v"); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Save interval: " + CONFIG.saveIntervalChunks), true); return 1; })));
		config.then(literal("oncomplete").then(argument("cmd", StringArgumentType.greedyString()).executes(c -> { CONFIG.onCompleteCommand = StringArgumentType.getString(c, "cmd"); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("On Complete set."), true); return 1; })));
		config.then(literal("restart").then(argument("v", IntegerArgumentType.integer(0)).executes(c -> { CONFIG.restartAfterChunks = IntegerArgumentType.getInteger(c, "v"); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Restart after " + CONFIG.restartAfterChunks + " chunks"), true); return 1; })));
		config.then(literal("refill").executes(c -> { CONFIG.immediateRefill = !CONFIG.immediateRefill; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Refill: " + (CONFIG.immediateRefill ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("lighting").executes(c -> { CONFIG.lightingFixMode = !CONFIG.lightingFixMode; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Lighting Fix: " + (CONFIG.lightingFixMode ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("structure").executes(c -> { CONFIG.structureOnlyMode = !CONFIG.structureOnlyMode; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Structure Only: " + (CONFIG.structureOnlyMode ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("gc").executes(c -> { CONFIG.aggressiveUnload = !CONFIG.aggressiveUnload; CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Aggressive GC: " + (CONFIG.aggressiveUnload ? "ON" : "OFF")), true); return 1; }));
		config.then(literal("busytick").then(argument("ms", IntegerArgumentType.integer(1)).executes(c -> { CONFIG.busyTickThresholdMs = (double) IntegerArgumentType.getInteger(c, "ms"); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Busy Tick Threshold: " + CONFIG.busyTickThresholdMs + "ms"), true); return 1; })));
		config.then(literal("mintps").then(argument("v", IntegerArgumentType.integer(1)).executes(c -> { CONFIG.minTpsThreshold = (double) IntegerArgumentType.getInteger(c, "v"); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Min TPS: " + CONFIG.minTpsThreshold), true); return 1; })));
		config.then(literal("window").then(argument("sec", IntegerArgumentType.integer(1)).executes(c -> { CONFIG.smoothEtaWindowSeconds = IntegerArgumentType.getInteger(c, "sec"); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("ETA Window: " + CONFIG.smoothEtaWindowSeconds + "s"), true); return 1; })));

		var poi = literal("poi");
		poi.then(literal("add").then(argument("coords", StringArgumentType.greedyString()).executes(c -> { String s = StringArgumentType.getString(c, "coords"); CONFIG.pointsOfInterest.add(s); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("Added POI: " + s), true); return 1; })));
		poi.then(literal("clear").executes(c -> { CONFIG.pointsOfInterest.clear(); CONFIG.save(); c.getSource().sendSuccess(() -> Component.literal("POIs cleared."), true); return 1; }));
		config.then(poi);
		
		root.then(config);
		dispatcher.register(root);
		dispatcher.register(literal("rf").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_ADMIN)).redirect(root.build()));
	}

	private static boolean canContinue(ServerLevel level, int cx, int cz, int r) {
		if (state == null || !state.started || state.completed) return false;
		return state.centerX == cx && state.centerZ == cz && state.radius == r && state.dimension.equals(level.dimension().identifier().toString());
	}

	private static void stopAllPreloading() {
		state = null; currentServer = null; nextRequestIndex = -1;
		inFlightIndices.clear(); inFlightStartTimes.clear(); completedIndices.clear(); pendingCompletionQueue.clear();
	}

	private static void ensureState(MinecraftServer server) {
		currentServer = server;
		if (state == null) {
			state = RenderFastState.get(server);
			if (state.started) { buildSpiral(state.radius); nextRequestIndex = state.doneCount; }
		}
	}

	private static int tickCounter = 0;
	private static long lastConsoleReportTime = 0;
	private static final int BROADCAST_INTERVAL_TICKS = 5;
	private static int lastBroadcastDone = -1;
	private static boolean lastBroadcastActive = false;

	private void onServerTick(MinecraftServer server) {
		ensureState(server);
		if (state == null || !state.started || state.completed) return;

		ServerLevel currentLevel = getLevelForDimension(server, state.dimension);
		if (currentLevel == null) return;

		processCompletions(currentLevel);
		runWatchdog();

		if (CONFIG.enabled && !state.paused) {
			updateCps();
			if (!isInsideWorkHours()) { currentPauseReason = "OFF HOURS"; broadcastProgress(server); return; }

			long elapsedThisTick = System.currentTimeMillis() - currentTickStartTime;
			if (inLagRecovery) {
				double avgMs = server.getAverageTickTimeNanos() / 1_000_000.0;
				if (avgMs < CONFIG.recoveryThresholdMs && elapsedThisTick < CONFIG.recoveryThresholdMs) inLagRecovery = false;
				else { currentPauseReason = "LAG RECOVERY"; broadcastProgress(server); return; }
			}

			boolean isTurbo = CONFIG.turboMode || (CONFIG.autoTurboWhenEmpty && server.getPlayerCount() == 0);
			boolean tooManyPlayers = CONFIG.onlyPreloadWhenEmpty && server.getPlayerCount() > 0;
			boolean stopOffline = CONFIG.pauseWhenEmpty && server.getPlayerCount() == 0;
			boolean lowDisk = (server.getWorldPath(LevelResource.ROOT).toFile().getFreeSpace() / (1024 * 1024)) < CONFIG.minFreeDiskSpaceMb;
			boolean lowMemory = isLowMemory();
			long avgTickNanos = server.getAverageTickTimeNanos();
			boolean busy = !isTurbo && (CONFIG.adaptiveThrottling && avgTickNanos > (long) (CONFIG.busyTickThresholdMs * 1_000_000L));

			if (lowMemory) currentPauseReason = "LOW RAM";
			else if (tooManyPlayers) currentPauseReason = "PLAYERS ONLINE";
			else if (stopOffline) currentPauseReason = "OFFLINE";
			else if (lowDisk) currentPauseReason = "LOW DISK";
			else if (busy) currentPauseReason = "BUSY TICK";
			else {
				currentPauseReason = "";
				int limit = isTurbo ? 16 : 4;
				if (CONFIG.voxyIntegration || CONFIG.distantHorizonsIntegration) limit = isTurbo ? 8 : 2;
				requestMoreChunks(currentLevel, limit);
			}

			if (state.doneCount >= totalChunks && !state.completed) { finishDimension(server); return; }
		} else if (state.paused) currentPauseReason = "MANUAL";

		if (++tickCounter >= BROADCAST_INTERVAL_TICKS) { tickCounter = 0; broadcastProgress(server); }
		long now = System.currentTimeMillis();
		if (now - lastConsoleReportTime >= CONFIG.consoleReportIntervalSeconds * 1000L) {
			lastConsoleReportTime = now;
			if (state != null && state.started && !state.completed) {
				int p = (int)(((float)state.doneCount / totalChunks) * 100);
				LOGGER.info("Progress: {}% ({}/{}) | Speed: {} ch/s | Dim: {}",
						p, state.doneCount, totalChunks, String.format("%.1f", currentCps), state.dimension);
			}
		}
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
				inFlightIndices.remove(idx); inFlightStartTimes.remove(idx);
			}
		});
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
		LOGGER.info("Pregen complete for {}: {} chunks in {}s", state.dimension, totalChunks, time);
		
		int idx = CONFIG.dimensions.indexOf(state.dimension);
		if (idx >= 0 && idx < CONFIG.dimensions.size() - 1) {
			ServerLevel next = getLevelForDimension(server, CONFIG.dimensions.get(idx + 1));
			if (next != null) { startPreload(next, state.centerX, state.centerZ, state.radius); return; }
		}
		state.markCompleted();
		if (!CONFIG.onCompleteCommand.isEmpty()) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), CONFIG.onCompleteCommand);
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

	private static String formatEta() {
		if (currentCps <= 0) return "N/A";
		long remaining = (long)((totalChunks - state.doneCount) / currentCps);
		return String.format("%dm %ds", remaining / 60, remaining % 60);
	}

	private static void startPreload(ServerLevel level, int cx, int cz) { startPreload(level, cx, cz, CONFIG.radius); }

	private static void startPreload(ServerLevel level, int cx, int cz, int r) {
		if (state == null) return;
		int safeRadius = Math.clamp(r <= 0 ? CONFIG.radius : r, 1, 2048);
		String dimId = level.dimension().identifier().toString();
		state.markStarted(cx, cz, dimId, safeRadius); buildSpiral(safeRadius);
		sessionStartTime = System.currentTimeMillis(); nextRequestIndex = 0;
		inFlightIndices.clear(); inFlightStartTimes.clear(); completedIndices.clear(); pendingCompletionQueue.clear();
		cachedTargetStatus = null;
		LOGGER.info("Starting preload: {} chunks in {}", totalChunks, dimId);
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
		return u > CONFIG.memoryUsageThreshold;
	}

	private static void processCompletions(ServerLevel level) {
		if (state == null) return;
		Integer idx; boolean changed = false;
		while ((idx = pendingCompletionQueue.poll()) != null) {
			if (idx < 0 || idx >= totalChunks) continue;
			level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, new ChunkPos(state.centerX + offsetX[idx], state.centerZ + offsetZ[idx]), 0);
			if (inFlightIndices.remove(idx)) {
				inFlightStartTimes.remove(idx); completedIndices.add(idx); changed = true; chunksSinceLastSave++;
				if (CONFIG.saveIntervalChunks > 0 && chunksSinceLastSave >= CONFIG.saveIntervalChunks) {
					level.getServer().saveEverything(true, false, true); chunksSinceLastSave = 0;
				}
			}
			if (CONFIG.watchdogBreather && (System.currentTimeMillis() - currentTickStartTime) > CONFIG.breatherThresholdMs) break;
		}
		if (changed) { state.doneCount = Math.min(completedIndices.size(), totalChunks); state.setDirty(); }
	}

	private static void requestMoreChunks(ServerLevel level, int limit) {
		if (state == null || currentServer == null || isLowMemory()) return;
		if (cachedTargetStatus == null) cachedTargetStatus = BuiltInRegistries.CHUNK_STATUS.get(Identifier.parse(CONFIG.targetStatus)).map(Holder.Reference::value).orElse(ChunkStatus.FULL);
		
		int req = 0;
		while (inFlightIndices.size() < CONFIG.maxConcurrentAsyncChunks && nextRequestIndex < totalChunks && req < limit) {
			if (CONFIG.watchdogBreather && (System.currentTimeMillis() - currentTickStartTime) > CONFIG.breatherThresholdMs) { inLagRecovery = true; break; }
			int i = nextRequestIndex++; if (completedIndices.contains(i)) continue;
			req++; ChunkPos cp = new ChunkPos(state.centerX + offsetX[i], state.centerZ + offsetZ[i]);
			inFlightIndices.add(i); inFlightStartTimes.put(i, System.currentTimeMillis());
			requestChunkGeneration(level, cp, i);
		}
	}

	private static void requestChunkGeneration(ServerLevel level, ChunkPos cp, int i) {
		level.getChunkSource().addTicketWithRadius(TicketType.FORCED, cp, 0);
		try {
			level.getChunkSource().getChunkFuture(cp.x(), cp.z(), cachedTargetStatus, true).whenComplete((_, _) -> pendingCompletionQueue.add(i));
		} catch (Exception e) {
			inFlightIndices.remove(i); inFlightStartTimes.remove(i);
		}
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
		byte[] data = new byte[50]; if (totalChunks <= 0 || state == null) return data;
		int size = 20;
		for (int i = 0; i < totalChunks; i++) {
			if (completedIndices.contains(i)) {
				int r = state.radius;
				int xG = (int)(((offsetX[i] + r) / (double)(2 * r)) * size);
				int zG = (int)(((offsetZ[i] + r) / (double)(2 * r)) * size);
				int bI = Math.clamp(zG, 0, size-1) * size + Math.clamp(xG, 0, size-1);
				data[bI / 8] |= (byte)(1 << (bitIdx(bI)));
			}
		}
		return data;
	}

	private static int bitIdx(int i) { return i % 8; }

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
