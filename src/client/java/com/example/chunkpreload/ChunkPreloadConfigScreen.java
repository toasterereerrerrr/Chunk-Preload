package com.example.chunkpreload;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

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

		general.addEntry(entryBuilder.startBooleanToggle(Component.literal("Show progress HUD"), config.showHud)
				.setDefaultValue(true).setSaveConsumer(v -> config.showHud = v).build());

		general.addEntry(entryBuilder.startBooleanToggle(Component.literal("Show status messages"), config.showStatusMessages)
				.setDefaultValue(true).setSaveConsumer(v -> config.showStatusMessages = v).build());

		general.addEntry(entryBuilder.startBooleanToggle(Component.literal("Show HUD metrics"), config.showHudMetrics)
				.setDefaultValue(false).setSaveConsumer(v -> config.showHudMetrics = v).build());

		advanced.addEntry(entryBuilder.startStringDropdownMenu(Component.literal("Target Status"), config.targetStatus)
				.setSelections(List.of(
						"minecraft:empty",
						"minecraft:structure_starts",
						"minecraft:structure_references",
						"minecraft:biomes",
						"minecraft:noise",
						"minecraft:surface",
						"minecraft:carvers",
						"minecraft:liquid_carvers",
						"minecraft:features",
						"minecraft:initialize_light",
						"minecraft:light",
						"minecraft:spawn",
						"minecraft:full"
				))
				.setDefaultValue("minecraft:features")
				.setTooltip(Component.literal("Controls how far each chunk generates. 'features' is recommended for maximum speed without losing terrain detail."))
				.setSaveConsumer(v -> config.targetStatus = v).build());
		
		advanced.addEntry(entryBuilder.startStrList(Component.literal("Dimension List"), config.dimensions)
				.setDefaultValue(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"))
				.setSaveConsumer(v -> config.dimensions = v).build());

		advanced.addEntry(entryBuilder.startStrList(Component.literal("Points of Interest"), config.pointsOfInterest)
				.setTooltip(Component.literal("Format: 'x,z'. Chunks at these locations will be generated first."))
				.setSaveConsumer(v -> config.pointsOfInterest = v).build());

		advanced.addEntry(entryBuilder.startBooleanToggle(Component.literal("Dry Run Mode"), config.dryRunMode)
				.setDefaultValue(false).setTooltip(Component.literal("If on, generation is paused and particles show the pregen area corners."))
				.setSaveConsumer(v -> config.dryRunMode = v).build());

		advanced.addEntry(entryBuilder.startBooleanToggle(Component.literal("Lighting Fix Mode"), config.lightingFixMode)
				.setDefaultValue(false).setTooltip(Component.literal("Only runs the lighting engine. Useful for fixing dark/black chunks."))
				.setSaveConsumer(v -> config.lightingFixMode = v).build());

		advanced.addEntry(entryBuilder.startIntSlider(Component.literal("Player Safety Radius"), config.playerSafetyRadius, 0, 16)
				.setDefaultValue(0).setTooltip(Component.literal("Automatically pregen chunks around all online players. 0 to disable."))
				.setSaveConsumer(v -> config.playerSafetyRadius = v).build());

		advanced.addEntry(entryBuilder.startBooleanToggle(Component.literal("Auto Turbo When Empty"), config.autoTurboWhenEmpty)
				.setDefaultValue(false).setTooltip(Component.literal("Automatically enable Turbo Mode when no players are online."))
				.setSaveConsumer(v -> config.autoTurboWhenEmpty = v).build());

		advanced.addEntry(entryBuilder.startBooleanToggle(Component.literal("Aggressive Memory Flush"), config.aggressiveUnload)
				.setDefaultValue(false).setTooltip(Component.literal("Force Java to cleanup memory when usage is high. Might cause small freezes."))
				.setSaveConsumer(v -> config.aggressiveUnload = v).build());

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
