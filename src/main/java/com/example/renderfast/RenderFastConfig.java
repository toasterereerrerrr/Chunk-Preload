package com.example.renderfast;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RenderFastConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "renderfast.json";

	// GENERAL
	public boolean enabled = true;
	public int radius = 100;
	public Shape shape = Shape.CIRCLE;
	public CpuUsageLevel cpuUsageLevel = CpuUsageLevel.MEDIUM;
	public boolean showHud = true;
	public boolean showHudMetrics = false;
	public boolean showStatusMessages = true;

	// ADVANCED GENERATION
	public String targetStatus = "minecraft:features";
	public List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
	public int maxConcurrentAsyncChunks = 32;
	public boolean immediateRefill = true;
	public boolean structureOnlyMode = false;
	public boolean lightingFixMode = false;
	public List<String> pointsOfInterest = new ArrayList<>();

	// PERFORMANCE & SAFETY
	public boolean adaptiveThrottling = true;
	public double busyTickThresholdMs = 45.0;
	public double minTpsThreshold = 15.0;
	public double memoryUsageThreshold = 0.90;
	public long minFreeDiskSpaceMb = 512;
	public boolean aggressiveUnload = false;
	public boolean onlyPreloadWhenEmpty = false;
	public boolean autoTurboWhenEmpty = false;
	public boolean turboMode = false;

	// AUTOMATION & TOOLS
	public int saveIntervalChunks = 500;
	public String startTime = "00:00";
	public String endTime = "23:59";
	public int watchdogTimeoutSeconds = 60;
	public boolean dryRunMode = false;
	public String onCompleteCommand = "";
	public int restartAfterChunks = 0;

	// INTEGRATION
	public String discordWebhookUrl = "";
	public int playerSafetyRadius = 0;

	// CLIENT VISUALS
	public boolean showMiniMap = false;
	public int smoothEtaWindowSeconds = 30;

	public enum CpuUsageLevel { LOW, MEDIUM, HIGH, VERY_HIGH, INSANE }
	public enum Shape { CIRCLE, SQUARE }

	public void applyCpuProfile() {
		if (cpuUsageLevel == null) cpuUsageLevel = CpuUsageLevel.MEDIUM;
		maxConcurrentAsyncChunks = switch (cpuUsageLevel) {
			case LOW -> 8;
			case MEDIUM -> 32;
			case HIGH -> 64;
			case VERY_HIGH -> 128;
			case INSANE -> 256;
		};
		if (radius < 1) radius = 100;
		if (radius > 2000) radius = 2000;
		if (saveIntervalChunks < 0) saveIntervalChunks = 0;
		if (watchdogTimeoutSeconds < 1) watchdogTimeoutSeconds = 1;
		if (smoothEtaWindowSeconds < 1) smoothEtaWindowSeconds = 1;
		if (playerSafetyRadius < 0) playerSafetyRadius = 0;
		if (minFreeDiskSpaceMb < 0) minFreeDiskSpaceMb = 0;
		if (memoryUsageThreshold < 0.0) memoryUsageThreshold = 0.0;
		if (memoryUsageThreshold > 1.0) memoryUsageThreshold = 1.0;
		if (busyTickThresholdMs < 0.0) busyTickThresholdMs = 0.0;
		if (minTpsThreshold < 0.0) minTpsThreshold = 0.0;
		if (dimensions == null || dimensions.isEmpty()) dimensions = new ArrayList<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
		if (pointsOfInterest == null) pointsOfInterest = new ArrayList<>();
		if (shape == null) shape = Shape.CIRCLE;
	}

	public static RenderFastConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			if (Files.exists(path)) {
				try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
					RenderFastConfig config = GSON.fromJson(reader, RenderFastConfig.class);
					if (config != null) {
						config.applyCpuProfile();
						return config;
					}
				}
			}
		} catch (IOException e) {
			RenderFast.LOGGER.warn("Failed to read {}, falling back to defaults", FILE_NAME, e);
		}
		RenderFastConfig defaults = new RenderFastConfig();
		defaults.applyCpuProfile();
		defaults.save();
		return defaults;
	}

	public void save() {
		applyCpuProfile();
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			RenderFast.LOGGER.warn("Failed to write {}", FILE_NAME, e);
		}
	}
}
