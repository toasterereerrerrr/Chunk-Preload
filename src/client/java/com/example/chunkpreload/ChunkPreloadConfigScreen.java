package com.example.chunkpreload;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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

		general.addEntry(entryBuilder.startBooleanToggle(Component.literal("Enabled"), config.enabled)
				.setDefaultValue(true).setSaveConsumer(v -> config.enabled = v).build());

		general.addEntry(entryBuilder.startIntSlider(Component.literal("Radius (chunks)"), config.radius, 10, 2000)
				.setDefaultValue(100).setSaveConsumer(v -> config.radius = v).build());

		general.addEntry(entryBuilder.startEnumSelector(Component.literal("CPU usage"), ChunkPreloadConfig.CpuUsageLevel.class, config.cpuUsageLevel)
				.setDefaultValue(ChunkPreloadConfig.CpuUsageLevel.MEDIUM).setSaveConsumer(v -> {
					config.cpuUsageLevel = v;
					config.maxConcurrentAsyncChunks = switch (v) {
						case LOW -> 8; case MEDIUM -> 32; case HIGH -> 64; case VERY_HIGH -> 128; case INSANE -> 256;
					};
				}).build());

		general.addEntry(entryBuilder.startBooleanToggle(Component.literal("Turbo Mode"), config.turboMode)
				.setDefaultValue(false).setSaveConsumer(v -> config.turboMode = v).build());

		general.addEntry(entryBuilder.startEnumSelector(Component.literal("Shape"), ChunkPreloadConfig.Shape.class, config.shape)
				.setDefaultValue(ChunkPreloadConfig.Shape.CIRCLE).setSaveConsumer(v -> config.shape = v).build());

		general.addEntry(entryBuilder.startBooleanToggle(Component.literal("Preload Nether"), config.preloadNether)
				.setDefaultValue(false).setSaveConsumer(v -> config.preloadNether = v).build());

		general.addEntry(entryBuilder.startBooleanToggle(Component.literal("Preload End"), config.preloadEnd)
				.setDefaultValue(false).setSaveConsumer(v -> config.preloadEnd = v).build());

		general.addEntry(entryBuilder.startBooleanToggle(Component.literal("Show progress HUD"), config.showHud)
				.setDefaultValue(true).setSaveConsumer(v -> config.showHud = v).build());

		general.addEntry(entryBuilder.startBooleanToggle(Component.literal("Show status messages"), config.showStatusMessages)
				.setDefaultValue(true).setSaveConsumer(v -> config.showStatusMessages = v).build());

		general.addEntry(entryBuilder.startBooleanToggle(Component.literal("Show HUD metrics"), config.showHudMetrics)
				.setDefaultValue(false).setSaveConsumer(v -> config.showHudMetrics = v).build());

		advanced.addEntry(entryBuilder.startTextField(Component.literal("Target Status"), config.targetStatus)
				.setDefaultValue("minecraft:full").setSaveConsumer(v -> config.targetStatus = v).build());

		advanced.addEntry(entryBuilder.startBooleanToggle(Component.literal("Immediate Refill"), config.immediateRefill)
				.setDefaultValue(true).setSaveConsumer(v -> config.immediateRefill = v).build());

		advanced.addEntry(entryBuilder.startIntSlider(Component.literal("Max concurrent chunks"), config.maxConcurrentAsyncChunks, 1, 256)
				.setDefaultValue(32).setSaveConsumer(v -> config.maxConcurrentAsyncChunks = v).build());

		advanced.addEntry(entryBuilder.startBooleanToggle(Component.literal("Smart throttle"), config.adaptiveThrottling)
				.setDefaultValue(true).setSaveConsumer(v -> config.adaptiveThrottling = v).build());

		advanced.addEntry(entryBuilder.startDoubleField(Component.literal("Busy tick threshold (ms)"), config.busyTickThresholdMs)
				.setDefaultValue(45.0).setSaveConsumer(v -> config.busyTickThresholdMs = v).build());

		advanced.addEntry(entryBuilder.startIntSlider(Component.literal("Memory usage threshold (%)"), (int) (config.memoryUsageThreshold * 100), 10, 99)
				.setDefaultValue(90).setSaveConsumer(v -> config.memoryUsageThreshold = v / 100.0).build());

		advanced.addEntry(entryBuilder.startBooleanToggle(Component.literal("Only When Empty"), config.onlyPreloadWhenEmpty)
				.setDefaultValue(false).setSaveConsumer(v -> config.onlyPreloadWhenEmpty = v).build());

		advanced.addEntry(entryBuilder.startDoubleField(Component.literal("Min TPS Threshold"), config.minTpsThreshold)
				.setDefaultValue(15.0).setSaveConsumer(v -> config.minTpsThreshold = v).build());

		advanced.addEntry(entryBuilder.startLongField(Component.literal("Min Free Disk (MB)"), config.minFreeDiskSpaceMb)
				.setDefaultValue(512L).setSaveConsumer(v -> config.minFreeDiskSpaceMb = v).build());

		integration.addEntry(entryBuilder.startTextField(Component.literal("Discord Webhook URL"), config.discordWebhookUrl)
				.setSaveConsumer(v -> config.discordWebhookUrl = v).build());

		integration.addEntry(entryBuilder.startBooleanToggle(Component.literal("Notify Map Mods"), config.notifyMapMods)
				.setDefaultValue(true).setSaveConsumer(v -> config.notifyMapMods = v).build());

		integration.addEntry(entryBuilder.startIntField(Component.literal("Restart After (Chunks)"), config.restartAfterChunks)
				.setDefaultValue(0).setSaveConsumer(v -> config.restartAfterChunks = v).build());

		integration.addEntry(entryBuilder.startBooleanToggle(Component.literal("Structure Only Mode"), config.structureOnlyMode)
				.setDefaultValue(false).setSaveConsumer(v -> config.structureOnlyMode = v).build());

		return builder.build();
	}
}
