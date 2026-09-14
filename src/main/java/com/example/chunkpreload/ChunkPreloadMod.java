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

import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.world.level.ChunkPos;
import java.util.LinkedList;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.server.level.ColumnPos;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;

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
	private static long lastConsoleLogTime = 0;

	private static final int RECENT_INDICES_COUNT = 100;
	private static final LinkedList<Integer> recentIndices = new LinkedList<>();
	private static int chunksGeneratedThisSession = 0;
	private static long benchmarkStartTime = 0;
	private static boolean isBenchmarking = false;

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
			var root = literal("chunkpreload")
					.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN));

			var startCommand = literal("start")
					.executes(context -> {
						MinecraftServer server = context.getSource().getServer();
						ensureState(server);
						ServerLevel level = context.getSource().getLevel();
						BlockPos pos = BlockPos.containing(context.getSource().getPosition());
						startPreload(level, pos.getX() >> 4, pos.getZ() >> 4);
						context.getSource().sendSuccess(() -> Component.literal("Started preloading around your position"), true);
						return 1;
					});
			startCommand.then(argument("x", IntegerArgumentType.integer())
					.then(argument("z", IntegerArgumentType.integer())
							.executes(context -> {
								int x = IntegerArgumentType.getInteger(context, "x");
								int z = IntegerArgumentType.getInteger(context, "z");
								MinecraftServer server = context.getSource().getServer();
								ensureState(server);
								ServerLevel level = context.getSource().getLevel();
								startPreload(level, x >> 4, z >> 4);
								context.getSource().sendSuccess(() -> Component.literal("Started preloading around " + x + ", " + z), true);
								return 1;
							})));
			startCommand.then(argument("pos", ColumnPosArgument.columnPos())
					.executes(context -> {
						ColumnPos pos = ColumnPosArgument.getColumnPos(context, "pos");
						MinecraftServer server = context.getSource().getServer();
						ensureState(server);
						ServerLevel level = context.getSource().getLevel();
						startPreload(level, pos.x() >> 4, pos.z() >> 4);
						context.getSource().sendSuccess(() -> Component.literal("Started preloading around " + pos.x() + ", " + pos.z()), true);
						return 1;
					}));
			var radiusStartCommand = argument("radius", IntegerArgumentType.integer(1))
					.executes(context -> {
						int r = IntegerArgumentType.getInteger(context, "radius");
						MinecraftServer server = context.getSource().getServer();
						ensureState(server);
						ServerLevel level = context.getSource().getLevel();
						BlockPos pos = BlockPos.containing(context.getSource().getPosition());
						startPreload(level, pos.getX() >> 4, pos.getZ() >> 4, r);
						context.getSource().sendSuccess(() -> Component.literal("Started preloading with radius " + r), true);
						return 1;
					});
			radiusStartCommand.then(argument("x", IntegerArgumentType.integer())
					.then(argument("z", IntegerArgumentType.integer())
							.executes(context -> {
								int r = IntegerArgumentType.getInteger(context, "radius");
								int x = IntegerArgumentType.getInteger(context, "x");
								int z = IntegerArgumentType.getInteger(context, "z");
								MinecraftServer server = context.getSource().getServer();
								ensureState(server);
								ServerLevel level = context.getSource().getLevel();
								startPreload(level, x >> 4, z >> 4, r);
								context.getSource().sendSuccess(() -> Component.literal("Started preloading around " + x + ", " + z + " with radius " + r), true);
								return 1;
							})));
			startCommand.then(radiusStartCommand);
			root.then(startCommand);

			root.then(literal("border")
					.executes(context -> {
						ServerLevel level = context.getSource().getLevel();
						WorldBorder border = level.getWorldBorder();
						int centerX = (int) border.getCenterX();
						int centerZ = (int) border.getCenterZ();
						int radius = (int) (border.getSize() / 2.0) / 16;
						CONFIG.radius = radius;
						CONFIG.shape = ChunkPreloadConfig.Shape.SQUARE;
						CONFIG.save();
						rebuildSpiral();
						MinecraftServer server = context.getSource().getServer();
						ensureState(server);
						startPreload(level, centerX >> 4, centerZ >> 4);
						context.getSource().sendSuccess(() -> Component.literal("Started preloading within world border (radius: " + radius + ")"), true);
						return 1;
					}));

			root.then(literal("benchmark")
					.executes(context -> {
						MinecraftServer server = context.getSource().getServer();
						ensureState(server);
						ServerLevel level = context.getSource().getLevel();
						BlockPos pos = BlockPos.containing(context.getSource().getPosition());
						isBenchmarking = true;
						benchmarkStartTime = System.currentTimeMillis();
						int oldRadius = CONFIG.radius;
						CONFIG.radius = 5;
						rebuildSpiral();
						startPreload(level, pos.getX() >> 4, pos.getZ() >> 4);
						CONFIG.radius = oldRadius;
						context.getSource().sendSuccess(() -> Component.literal("Starting 10x10 benchmark..."), true);
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

			root.then(literal("refill")
					.executes(context -> {
						CONFIG.immediateRefill = !CONFIG.immediateRefill;
						CONFIG.save();
						context.getSource().sendSuccess(() -> Component.literal("Immediate Refill: " + (CONFIG.immediateRefill ? "ON" : "OFF")), true);
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
							context.getSource().sendSuccess(() -> Component.literal(String.format("Progress: %d/%d (Turbo: %b, Dim: %s, Speed: %.1f ch/s)", state.doneCount, totalChunks, CONFIG.turboMode, state.dimension, chunksPerSecond)), false);
						}
						return 1;
					}));

			dispatcher.register(root);
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

		if (CONFIG.enabled) {
			updateCps();
			handleConsoleLogging();

			boolean lowMemory = isLowMemory();
			boolean tooManyPlayers = CONFIG.onlyPreloadWhenEmpty && server.getPlayerCount() > 0;
			boolean lowDisk = isLowDiskSpace(server);
			boolean serverIsBusy = !CONFIG.turboMode && (CONFIG.adaptiveThrottling
					&& server.getAverageTickTimeNanos() > (long) (CONFIG.busyTickThresholdMs * 1_000_000L));
			boolean lowTps = !CONFIG.turboMode && (1000.0 / (server.getAverageTickTimeNanos() / 1_000_000.0)) < CONFIG.minTpsThreshold;

			boolean shouldRequestMoreChunks = !lowMemory && (
					CONFIG.turboMode || (!serverIsBusy && !tooManyPlayers && !lowTps && !lowDisk)
			);

			if (shouldRequestMoreChunks) {
				requestMoreChunks(currentLevel, 32);
			}

			if (state.doneCount >= totalChunks && !state.completed) {
				LOGGER.info("Chunk preload complete for {}: {} chunks generated", state.dimension, totalChunks);
				sendDiscordWebhook("Chunk preloading complete for " + state.dimension + ": " + totalChunks + " chunks");
				
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

	private static void handleConsoleLogging() {
		if (CONFIG.consoleLogIntervalSeconds <= 0) return;
		long now = System.currentTimeMillis();
		if (now - lastConsoleLogTime > CONFIG.consoleLogIntervalSeconds * 1000L) {
			lastConsoleLogTime = now;
			LOGGER.info(String.format("Pregen Progress: %d/%d chunks (%s) | Speed: %.1f ch/s", 
					state.doneCount, totalChunks, state.dimension, chunksPerSecond));
		}
	}

	private static boolean isLowDiskSpace(MinecraftServer server) {
		File worldDir = server.getWorldPath(LevelResource.ROOT).toFile();
		long freeSpaceMb = worldDir.getFreeSpace() / (1024 * 1024);
		return freeSpaceMb < CONFIG.minFreeDiskSpaceMb;
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
		startPreload(level, chunkX, chunkZ, CONFIG.radius);
	}

	private static void startPreload(ServerLevel level, int chunkX, int chunkZ, int radius) {
		int previousRadius = CONFIG.radius;
		if (radius > 0 && radius != previousRadius) {
			CONFIG.radius = radius;
		}

		String dimId = level.dimension().identifier().toString();
		state.markStarted(chunkX, chunkZ, dimId);
		rebuildSpiral();
		recentIndices.clear();
		cachedTargetStatus = null;
		LOGGER.info("Starting chunk preload: {} chunks in {} around ({}, {})", totalChunks, dimId, chunkX, chunkZ);
		sendDiscordWebhook("Chunk preloading started in " + dimId + " at " + chunkX + ", " + chunkZ + " (" + totalChunks + " chunks)");

		if (radius > 0 && radius != previousRadius) {
			CONFIG.radius = previousRadius;
		}
	}

	private static void sendDiscordWebhook(String message) {
		String url = CONFIG.discordWebhookUrl;
		if (url == null || url.isEmpty()) return;

		try {
			HttpClient client = HttpClient.newHttpClient();
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" + message + "\"}"))
					.build();

			client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
					.thenAccept(response -> {
						if (response.statusCode() >= 400) {
							LOGGER.warn("Discord webhook failed with status: {}", response.statusCode());
						}
					})
					.exceptionally(t -> {
						LOGGER.warn("Discord webhook failed", t);
						return null;
					});
		} catch (Exception e) {
			LOGGER.warn("Failed to send Discord webhook", e);
		}
	}

	private static void updateMapMods(ServerLevel level, int chunkX, int chunkZ) {
		if (!CONFIG.notifyMapMods) return;
		
		// BlueMap support
		try {
			Class<?> apiClass = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI");
			Optional<?> api = (Optional<?>) apiClass.getMethod("getInstance").invoke(null);
			if (api.isPresent()) {
				Object apiObj = api.get();
				Optional<?> bmWorld = (Optional<?>) apiClass.getMethod("getWorld", Level.class).invoke(apiObj, level);
				if (bmWorld.isPresent()) {
					Object world = bmWorld.get();
					Iterable<?> maps = (Iterable<?>) world.getClass().getMethod("getMaps").invoke(world);
					for (Object map : maps) {
						Class<?> vector2iClass = Class.forName("com.flowpowered.math.vector.Vector2i");
						Object vector = vector2iClass.getConstructor(int.class, int.class).newInstance(chunkX, chunkZ);
						map.getClass().getMethod("render", vector2iClass).invoke(map, vector);
					}
				}
			}
		} catch (Exception ignored) {}
		
		// Dynmap support
		try {
			Class<?> dynmapApiClass = Class.forName("org.dynmap.DynmapCommonAPI");
			// Usually dynmap handles it via chunk load events, but we can force it if needed
		} catch (Exception ignored) {}
		
		// Xaero's Minimap / World Map support
		tryInvokeMapRefresh(chunkX, chunkZ,
				"xaero.minimap.XaeroMinimap",
				"xaero.minimap.api.XaeroMinimapAPI",
				"xaero.worldmap.XaeroWorldMap",
				"xaero.map.WorldMap",
				"xaero.common.minimap.Minimap"
		);
	}

	private static void tryInvokeMapRefresh(int chunkX, int chunkZ, String... candidateClassNames) {
		for (String className : candidateClassNames) {
			try {
				Class<?> clazz = Class.forName(className);
				Object target = findXaeroMapTarget(clazz);
				if (target == null) {
					continue;
				}

				for (Method method : clazz.getMethods()) {
					String methodName = method.getName().toLowerCase(Locale.ROOT);
					if (!methodName.contains("refresh") && !methodName.contains("reload") && !methodName.contains("render")
							&& !methodName.contains("update") && !methodName.contains("redraw") && !methodName.contains("map")) {
						continue;
					}
					try {
						if (method.getParameterCount() == 0) {
							method.invoke(target);
							return;
						}
						if (method.getParameterCount() == 2 && method.getParameterTypes()[0] == int.class && method.getParameterTypes()[1] == int.class) {
							method.invoke(target, chunkX, chunkZ);
							return;
						}
						if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == int.class) {
							method.invoke(target, chunkX);
							return;
						}
					} catch (Exception ignored) {
						// Try the next candidate signature rather than failing everything.
					}
				}
			} catch (Exception ignored) {
				// Xaero may not be installed or may have a different implementation.
			}
		}
	}

	private static Object findXaeroMapTarget(Class<?> clazz) throws Exception {
		for (Method method : clazz.getMethods()) {
			if (!Modifier.isStatic(method.getModifiers())) continue;
			if (method.getName().equals("getInstance") && method.getParameterCount() == 0) {
				return method.invoke(null);
			}
		}
		for (Field field : clazz.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) && clazz.isAssignableFrom(field.getType())) {
				field.setAccessible(true);
				return field.get(null);
			}
		}
		return clazz;
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

			recentIndices.addFirst(completedIndex);
			if (recentIndices.size() > RECENT_INDICES_COUNT) {
				recentIndices.removeLast();
			}
			
			chunksGeneratedThisSession++;
			updateMapMods(overworld, chunkX, chunkZ);

			if (CONFIG.restartAfterChunks > 0 && chunksGeneratedThisSession >= CONFIG.restartAfterChunks) {
				LOGGER.info("Restart limit reached ({} chunks). Stopping server...", CONFIG.restartAfterChunks);
				overworld.getServer().halt(false);
			}
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
		if (isLowMemory()) {
			return;
		}

		int maxConcurrency = CONFIG.maxConcurrentAsyncChunks;
		
		if (cachedTargetStatus == null) {
			String statusId = CONFIG.structureOnlyMode ? "minecraft:structure_starts" : CONFIG.targetStatus;
			cachedTargetStatus = BuiltInRegistries.CHUNK_STATUS.get(Identifier.parse(statusId))
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

		List<Integer> recent = new ArrayList<>(recentIndices);
		PreloadProgressPayload payload = new PreloadProgressPayload(state.doneCount, totalChunks, active, state.dimension, chunksPerSecond, recent);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, PreloadProgressPayload.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	private static void sendProgress(ServerPlayer player) {
		boolean active = CONFIG.enabled && state.started && !state.completed;
		List<Integer> recent = new ArrayList<>(recentIndices);
		PreloadProgressPayload payload = new PreloadProgressPayload(state.doneCount, totalChunks, active, state.dimension, chunksPerSecond, recent);
		if (ServerPlayNetworking.canSend(player, PreloadProgressPayload.TYPE)) {
			ServerPlayNetworking.send(player, payload);
		}
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
