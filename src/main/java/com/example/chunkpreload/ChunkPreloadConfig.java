package com.example.chunkpreload;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ChunkPreloadConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "chunkpreload.json";

	public int radius = 100;
	public boolean enabled = true;
	public boolean adaptiveThrottling = true;

	public double busyTickThresholdMs = 45.0;
	public double memoryUsageThreshold = 0.90;
	public boolean turboMode = false;

	public enum CpuUsageLevel {
		LOW, MEDIUM, HIGH, VERY_HIGH, INSANE
	}

	public CpuUsageLevel cpuUsageLevel = CpuUsageLevel.MEDIUM;
	public int maxConcurrentAsyncChunks = 32;
	public boolean immediateRefill = true;

	public boolean showHud = true;
	public boolean showStatusMessages = true;
	public boolean showHudMetrics = false;

	public enum Shape {
		CIRCLE, SQUARE
	}

	public Shape shape = Shape.CIRCLE;
	public String onCompleteCommand = "";
	public String targetStatus = "minecraft:full";
	public boolean preloadNether = false;
	public boolean preloadEnd = false;

	public boolean onlyPreloadWhenEmpty = false;
	public int consoleLogIntervalSeconds = 30;
	public double minTpsThreshold = 15.0;
	public long minFreeDiskSpaceMb = 512;

	public String discordWebhookUrl = "";
	public boolean notifyMapMods = true;
	public int restartAfterChunks = 0;
	public boolean structureOnlyMode = false;

	public static ChunkPreloadConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			if (Files.exists(path)) {
				try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
					ChunkPreloadConfig config = GSON.fromJson(reader, ChunkPreloadConfig.class);
					if (config != null) return config;
				}
			}
		} catch (IOException e) {
			ChunkPreloadMod.LOGGER.warn("Failed to read {}, falling back to defaults", FILE_NAME, e);
		}
		ChunkPreloadConfig defaults = new ChunkPreloadConfig();
		defaults.save();
		return defaults;
	}

	public void save() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			ChunkPreloadMod.LOGGER.warn("Failed to write {}", FILE_NAME, e);
		}
	}
}
