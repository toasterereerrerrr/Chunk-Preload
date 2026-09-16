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
	public boolean notifyMapMods = true;
	public int playerSafetyRadius = 0;

	// CLIENT VISUALS
	public boolean showMiniMap = false;
	public int smoothEtaWindowSeconds = 30;

	public enum CpuUsageLevel { LOW, MEDIUM, HIGH, VERY_HIGH, INSANE }
	public enum Shape { CIRCLE, SQUARE }

	public static RenderFastConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			if (Files.exists(path)) {
				try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
					RenderFastConfig config = GSON.fromJson(reader, RenderFastConfig.class);
					if (config != null) return config;
				}
			}
		} catch (IOException e) {
			RenderFastMod.LOGGER.warn("Failed to read {}, falling back to defaults", FILE_NAME, e);
		}
		RenderFastConfig defaults = new RenderFastConfig();
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
			RenderFastMod.LOGGER.warn("Failed to write {}", FILE_NAME, e);
		}
	}
}
