package com.example.chunkpreload;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Builds the Cloth Config settings screen for Chunk Preloader.
 * Edits {@link ChunkPreloadMod#CONFIG} in place and writes it back to
 * config/chunkpreload.json when the player clicks "Save".
 *
 * Note: changing the radius here only affects worlds that haven't started
 * preloading yet - see the README for how to force-restart an existing world.
 */
public class ChunkPreloadConfigScreen {
	public static Screen create(Screen parent) {
		ChunkPreloadConfig config = ChunkPreloadMod.CONFIG;

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.literal("Chunk Preloader"))
				.setSavingRunnable(() -> {
					config.save();
					ChunkPreloadMod.rebuildSpiral();
				});

		ConfigEntryBuilder entryBuilder = builder.entryBuilder();
		ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));
		ConfigCategory advanced = builder.getOrCreateCategory(Component.literal("Advanced"));

		// --- GENERAL CATEGORY ---

		general.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Enabled"), config.enabled)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Turns chunk preloading (and its HUD) off entirely."))
				.setSaveConsumer(value -> config.enabled = value)
				.build());

		general.addEntry(entryBuilder
				.startIntSlider(Component.literal("Radius (chunks)"), config.radius, 10, 2000)
				.setDefaultValue(100)
				.setTooltip(Component.literal("Radius to preload around the starting point. Only affects new preloads."))
				.setSaveConsumer(value -> config.radius = value)
				.build());

		general.addEntry(entryBuilder
				.startEnumSelector(Component.literal("CPU usage"), ChunkPreloadConfig.CpuUsageLevel.class, config.cpuUsageLevel)
				.setDefaultValue(ChunkPreloadConfig.CpuUsageLevel.MEDIUM)
				.setTooltip(Component.literal(
						"Controls how aggressively chunks generate. High finishes faster but uses more CPU, " +
						"Low is gentler on weaker hardware."))
				.setSaveConsumer(value -> {
					config.cpuUsageLevel = value;
					config.maxConcurrentAsyncChunks = switch (value) {
						case LOW -> 8;
						case MEDIUM -> 32;
						case HIGH -> 64;
					};
				})
				.build());

		// --- ADVANCED CATEGORY ---

		advanced.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Smart throttle"), config.adaptiveThrottling)
				.setDefaultValue(true)
				.setTooltip(Component.literal(
						"Automatically pauses generation if the server is already busy."))
				.setSaveConsumer(value -> config.adaptiveThrottling = value)
				.build());

		advanced.addEntry(entryBuilder
				.startDoubleField(Component.literal("Busy tick threshold (ms)"), config.busyTickThresholdMs)
				.setDefaultValue(45.0)
				.setTooltip(Component.literal(
						"The server tick time threshold (in ms) above which generation pauses. " +
						"Higher = more aggressive, lower = less lag."))
				.setSaveConsumer(value -> config.busyTickThresholdMs = value)
				.build());

		advanced.addEntry(entryBuilder
				.startIntSlider(Component.literal("Memory usage threshold (%)"), (int) (config.memoryUsageThreshold * 100), 10, 99)
				.setDefaultValue(90)
				.setTooltip(Component.literal(
						"Pause generation when JVM memory usage exceeds this percentage to avoid OutOfMemory errors."))
				.setSaveConsumer(value -> config.memoryUsageThreshold = value / 100.0)
				.build());

		advanced.addEntry(entryBuilder
				.startIntField(Component.literal("Max concurrent chunks"), config.maxConcurrentAsyncChunks)
				.setDefaultValue(32)
				.setTooltip(Component.literal(
						"Manual override for the number of chunks requested at once. Overwritten by 'CPU usage' if changed there."))
				.setSaveConsumer(value -> config.maxConcurrentAsyncChunks = value)
				.build());

		advanced.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Show progress HUD"), config.showHud)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Show/hide the top-right progress bar."))
				.setSaveConsumer(value -> config.showHud = value)
				.build());

		advanced.addEntry(entryBuilder
				.startIntSlider(Component.literal("Max ms per tick (Legacy)"), config.maxMillisPerTick, 1, 200)
				.setDefaultValue(40)
				.setTooltip(Component.literal("Legacy setting for synchronous loading. Currently unused."))
				.setSaveConsumer(value -> config.maxMillisPerTick = value)
				.build());

		return builder.build();
	}
}