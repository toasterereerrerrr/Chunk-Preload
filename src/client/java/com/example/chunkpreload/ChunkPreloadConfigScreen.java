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
		ConfigCategory integration = builder.getOrCreateCategory(Component.literal("Integration"));

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
						"INSANE is for high-end systems."))
				.setSaveConsumer(value -> {
					config.cpuUsageLevel = value;
					config.maxConcurrentAsyncChunks = switch (value) {
						case LOW -> 8;
						case MEDIUM -> 32;
						case HIGH -> 64;
						case VERY_HIGH -> 128;
						case INSANE -> 256;
					};
				})
				.build());

		general.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Turbo Mode"), config.turboMode)
				.setDefaultValue(false)
				.setTooltip(Component.literal("Bypasses server-load throttles for maximum speed. Memory protection still pauses generation when the heap is too full."))
				.setSaveConsumer(value -> config.turboMode = value)
				.build());

		general.addEntry(entryBuilder
				.startEnumSelector(Component.literal("Shape"), ChunkPreloadConfig.Shape.class, config.shape)
				.setDefaultValue(ChunkPreloadConfig.Shape.CIRCLE)
				.setTooltip(Component.literal("CIRCLE generates in a ring, SQUARE generates a full block area."))
				.setSaveConsumer(value -> config.shape = value)
				.build());

		general.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Preload Nether"), config.preloadNether)
				.setDefaultValue(false)
				.setTooltip(Component.literal("Automatically start preloading the Nether after the Overworld is done."))
				.setSaveConsumer(value -> config.preloadNether = value)
				.build());

		general.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Preload End"), config.preloadEnd)
				.setDefaultValue(false)
				.setTooltip(Component.literal("Automatically start preloading the End after the Nether is done."))
				.setSaveConsumer(value -> config.preloadEnd = value)
				.build());

		general.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Show progress HUD"), config.showHud)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Show/hide the top-right progress bar."))
				.setSaveConsumer(value -> config.showHud = value)
				.build());

		general.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Show status messages"), config.showStatusMessages)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Show/hide the C2ME detection and 'Done loading' messages."))
				.setSaveConsumer(value -> config.showStatusMessages = value)
				.build());

		general.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Show HUD metrics"), config.showHudMetrics)
				.setDefaultValue(false)
				.setTooltip(Component.literal("Show memory usage and chunks/second under the normal progress HUD."))
				.setSaveConsumer(value -> config.showHudMetrics = value)
				.build());

		// --- ADVANCED CATEGORY ---

		advanced.addEntry(entryBuilder
				.startTextField(Component.literal("Target Status"), config.targetStatus)
				.setDefaultValue("minecraft:full")
				.setTooltip(Component.literal("Status to load chunks to (e.g., 'minecraft:full', 'minecraft:features'). Faster if not 'full'."))
				.setSaveConsumer(value -> config.targetStatus = value)
				.build());

		advanced.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Route-aware preloading"), config.routeAwarePreloading)
				.setDefaultValue(false)
				.setTooltip(Component.literal("Prioritize chunks ahead of the player instead of filling the full spiral in a fixed order. Off by default."))
				.setSaveConsumer(value -> config.routeAwarePreloading = value)
				.build());

		advanced.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Immediate Refill"), config.immediateRefill)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Bypasses tick latency by requesting a new chunk as soon as one finishes. FASTEST."))
				.setSaveConsumer(value -> config.immediateRefill = value)
				.build());

		advanced.addEntry(entryBuilder
				.startIntSlider(Component.literal("Max concurrent chunks"), config.maxConcurrentAsyncChunks, 1, 256)
				.setDefaultValue(32)
				.setTooltip(Component.literal("Manual override for the number of chunks requested at once."))
				.setSaveConsumer(value -> config.maxConcurrentAsyncChunks = value)
				.build());

		advanced.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Smart throttle"), config.adaptiveThrottling)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Automatically pauses generation if the server is already busy."))
				.setSaveConsumer(value -> config.adaptiveThrottling = value)
				.build());

		advanced.addEntry(entryBuilder
				.startDoubleField(Component.literal("Busy tick threshold (ms)"), config.busyTickThresholdMs)
				.setDefaultValue(45.0)
				.setTooltip(Component.literal("The server tick time threshold (in ms) above which generation pauses."))
				.setSaveConsumer(value -> config.busyTickThresholdMs = value)
				.build());

		advanced.addEntry(entryBuilder
				.startIntSlider(Component.literal("Memory usage threshold (%)"), (int) (config.memoryUsageThreshold * 100), 10, 99)
				.setDefaultValue(90)
				.setTooltip(Component.literal("Pause generation when JVM memory usage exceeds this percentage."))
				.setSaveConsumer(value -> config.memoryUsageThreshold = value / 100.0)
				.build());

		advanced.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Only When Empty"), config.onlyPreloadWhenEmpty)
				.setDefaultValue(false)
				.setTooltip(Component.literal("Pause generation whenever players are online to avoid lag."))
				.setSaveConsumer(value -> config.onlyPreloadWhenEmpty = value)
				.build());

		advanced.addEntry(entryBuilder
				.startDoubleField(Component.literal("Min TPS Threshold"), config.minTpsThreshold)
				.setDefaultValue(15.0)
				.setTooltip(Component.literal("Pause if server TPS drops below this value."))
				.setSaveConsumer(value -> config.minTpsThreshold = value)
				.build());

		advanced.addEntry(entryBuilder
				.startLongField(Component.literal("Min Free Disk (MB)"), config.minFreeDiskSpaceMb)
				.setDefaultValue(512L)
				.setTooltip(Component.literal("Pause if free disk space is lower than this."))
				.setSaveConsumer(value -> config.minFreeDiskSpaceMb = value)
				.build());

		// --- INTEGRATION CATEGORY ---

		integration.addEntry(entryBuilder
				.startTextField(Component.literal("Discord Webhook URL"), config.discordWebhookUrl)
				.setTooltip(Component.literal("Post progress updates to a Discord channel."))
				.setSaveConsumer(value -> config.discordWebhookUrl = value)
				.build());

		integration.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Notify Map Mods"), config.notifyMapMods)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Tell BlueMap/Dynmap to render chunks as they are generated."))
				.setSaveConsumer(value -> config.notifyMapMods = value)
				.build());

		integration.addEntry(entryBuilder
				.startIntField(Component.literal("Restart After (Chunks)"), config.restartAfterChunks)
				.setDefaultValue(0)
				.setTooltip(Component.literal("Automatically /stop the server after this many chunks. 0 to disable."))
				.setSaveConsumer(value -> config.restartAfterChunks = value)
				.build());

		integration.addEntry(entryBuilder
				.startBooleanToggle(Component.literal("Structure Only Mode"), config.structureOnlyMode)
				.setDefaultValue(false)
				.setTooltip(Component.literal("Only generate structure data (fastest). Use for mapping structures."))
				.setSaveConsumer(value -> config.structureOnlyMode = value)
				.build());

		return builder.build();
	}
}
