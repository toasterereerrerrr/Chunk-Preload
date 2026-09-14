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

/**
 * Simple JSON config stored at config/chunkpreload.json.
 * Edit it and restart the server/game to change the radius or how much
 * time per tick the preloader is allowed to spend generating chunks.
 */
public class ChunkPreloadConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "chunkpreload.json";

	/** Radius, in chunks, to preload around the first player's spawn point. */
	public int radius = 100;
   /** If false, chunk preloading and its HUD are switched off entirely - the mod does nothing. */
	public boolean enabled = true;
   /**
	 * If true, generation automatically backs off during ticks where the server is
	 * already running slow, instead of adding more load on top of an already-busy server.
   */
	public boolean adaptiveThrottling = true;

	/**
	 * The average tick time threshold (in milliseconds) above which the preloader
	 * will pause generation if adaptiveThrottling is enabled.
	 */
	public double busyTickThresholdMs = 45.0;

	/**
	 * The percentage of maximum memory usage (0.0 to 1.0) above which the preloader
	 * will pause to allow the GC to catch up and avoid OutOfMemory errors.
	 */
	public double memoryUsageThreshold = 0.90;

	/**
	 * If true, generation ignores all throttling thresholds and runs at maximum possible speed.
	 * May cause significant lag and high memory usage.
	 */
	public boolean turboMode = false;

	/** Roughly how many milliseconds per server tick may be spent generating chunks. */
	public int maxMillisPerTick = 40;
   /**
	 * How many chunks may be requested concurrently through the async pipeline at once.
	 * Higher finishes faster but adds more simultaneous CPU load; lower is gentler on weaker hardware.
	*/
	public enum CpuUsageLevel {
		LOW, MEDIUM, HIGH, VERY_HIGH, INSANE
	}

	/**
	 * How aggressively to use available CPU threads/cores for concurrent
	 * chunk generation. Maps to maxConcurrentAsyncChunks below.
	 */
	public CpuUsageLevel cpuUsageLevel = CpuUsageLevel.MEDIUM;

	/**
	 * How many chunks may be requested concurrently through the async pipeline at once.
	 * Higher finishes faster but adds more simultaneous CPU load.
	 */
	public int maxConcurrentAsyncChunks = 32;

	/**
	 * If true, when a chunk finishes loading, another is immediately requested
	 * instead of waiting for the next server tick.
	 */
	public boolean immediateRefill = true;
   
   public boolean showHud = true;
   public boolean showStatusMessages = true;

	// --- NEW FEATURES ---

	public enum Shape {
		CIRCLE, SQUARE
	}

	public Shape shape = Shape.CIRCLE;

	/** If not empty, this command will be executed when preloading finishes. */
	public String onCompleteCommand = "";

	/** Target chunk status. "full" is default. "features" or "liquid_carvers" are faster. */
	public String targetStatus = "minecraft:full";

	public boolean preloadNether = false;
	public boolean preloadEnd = false;

	// --- SERVER FRIENDLY OPTIONS ---

	/** If true, preloading only happens when no players are online. */
	public boolean onlyPreloadWhenEmpty = false;

	/** Log progress to console every N seconds. 0 to disable. */
	public int consoleLogIntervalSeconds = 30;

	/** If current TPS drops below this, pause generation. */
	public double minTpsThreshold = 15.0;

	/** Minimum free disk space in MB required to continue preloading. */
	public long minFreeDiskSpaceMb = 512;

	public static ChunkPreloadConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

		try {
			if (Files.exists(path)) {
				try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
					ChunkPreloadConfig config = GSON.fromJson(reader, ChunkPreloadConfig.class);

					if (config != null) {
						return config;
					}
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
