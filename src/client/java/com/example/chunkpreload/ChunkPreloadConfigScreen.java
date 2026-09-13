package com.example.chunkpreload;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
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

		general.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Enabled"), config.enabled)
				.setDefaultValue(true)
				.setTooltip(Component.literal(
						"Turns chunk preloading (and its HUD) off entirely. Doesn't affect a world that has " +
						"already finished preloading - there's nothing left for it to do there anyway."))
				.setSaveConsumer(value -> config.enabled = value)
				.build());

		general.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Smart throttle"), config.adaptiveThrottling)
				.setDefaultValue(true)
				.setTooltip(Component.literal(
						"Automatically pauses generation for a tick whenever the server is already running slow, " +
						"instead of piling more work on top. Recommended to leave on."))
				.setSaveConsumer(value -> config.adaptiveThrottling = value)
				.build());

		general.addEntry(entryBuilder
				.startIntSlider(Component.literal("Radius (chunks)"), config.radius, 10, 2000)
				.setDefaultValue(100)
				.setTooltip(Component.literal(
						"Only takes effect on worlds that haven't started preloading yet. " +
						"To restart an existing world with a new radius, delete that world's saved preload data first."))
				.setSaveConsumer(value -> config.radius = value)
				.build());

		if (FabricLoader.getInstance().isModLoaded("c2me")) {
			general.addEntry(entryBuilder
					.startEnumSelector(Component.literal("CPU usage"), ChunkPreloadConfig.CpuUsageLevel.class, config.cpuUsageLevel)
					.setDefaultValue(ChunkPreloadConfig.CpuUsageLevel.MEDIUM)
					.setTooltip(Component.literal(
							"C2ME detected - chunks generate across worker threads via its async pipeline. " +
							"Controls how many chunks may be in flight at once: High uses more threads/cores " +
							"at once and finishes faster, Low is gentler on weaker or fewer-core hardware."))
					.setSaveConsumer(value -> {
						config.cpuUsageLevel = value;
						config.maxConcurrentAsyncChunks = switch (value) {
							case LOW -> 4;
							case MEDIUM -> 8;
							case HIGH -> 16;
						};
					})
					.build());
		} else {
			general.addEntry(entryBuilder
					.startIntSlider(Component.literal("Max ms per tick"), config.maxMillisPerTick, 1, 200)
					.setDefaultValue(40)
					.setTooltip(Component.literal(
							"How many milliseconds per server tick may be spent generating chunks. " +
							"Higher = faster preload, more risk of lag spikes on slower hardware."))
					.setSaveConsumer(value -> config.maxMillisPerTick = value)
					.build());
		}

		general.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Show progress HUD"), config.showHud)
				.setDefaultValue(true)
				.setTooltip(Component.literal(
						"Turn off to hide the top-right progress bar. Chunk generation itself keeps running either way."))
				.setSaveConsumer(value -> config.showHud = value)
				.build());

		return builder.build();
	}
}