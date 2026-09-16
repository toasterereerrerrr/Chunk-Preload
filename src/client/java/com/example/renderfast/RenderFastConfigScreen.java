package com.example.renderfast;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class RenderFastConfigScreen {
	public static Screen create(Screen parent) {
		RenderFastConfig config = RenderFast.CONFIG;
		ConfigBuilder builder = ConfigBuilder.create().setParentScreen(parent).setTitle(Component.literal("RenderFast Configuration")).setSavingRunnable(() -> { config.save(); RenderFast.rebuildSpiral(); });
		ConfigEntryBuilder eb = builder.entryBuilder();

		ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));
		ConfigCategory advanced = builder.getOrCreateCategory(Component.literal("Advanced"));
		ConfigCategory performance = builder.getOrCreateCategory(Component.literal("Safety & Performance"));
		ConfigCategory integration = builder.getOrCreateCategory(Component.literal("Integration"));
		ConfigCategory help = builder.getOrCreateCategory(Component.literal("Features & Help"));

		general.addEntry(eb.startBooleanToggle(Component.literal("Enable RenderFast"), config.enabled).setDefaultValue(true).setTooltip(Component.literal("Global toggle for the background generation engine.")).setSaveConsumer(v -> config.enabled = v).build());
		general.addEntry(eb.startIntSlider(Component.literal("Generation Radius"), config.radius, 10, 2000).setDefaultValue(100).setTooltip(Component.literal("How many chunks out from the center to generate.")).setSaveConsumer(v -> config.radius = v).build());
		general.addEntry(eb.startEnumSelector(Component.literal("Shape"), RenderFastConfig.Shape.class, config.shape).setDefaultValue(RenderFastConfig.Shape.CIRCLE).setTooltip(Component.literal("Circle is more natural; Square is better for filling world borders.")).setSaveConsumer(v -> config.shape = v).build());
		general.addEntry(eb.startBooleanToggle(Component.literal("Show HUD"), config.showHud).setDefaultValue(true).setTooltip(Component.literal("Display the progress bar in the top-right corner.")).setSaveConsumer(v -> config.showHud = v).build());
		general.addEntry(eb.startBooleanToggle(Component.literal("Show Mini-Map"), config.showMiniMap).setDefaultValue(false).setTooltip(Component.literal("Display a tiny 20x20 visual heatmap of current progress.")).setSaveConsumer(v -> config.showMiniMap = v).build());
		general.addEntry(eb.startBooleanToggle(Component.literal("Show Metrics"), config.showHudMetrics).setDefaultValue(false).setTooltip(Component.literal("Show detailed Speed (ch/s) and ETA on the HUD.")).setSaveConsumer(v -> config.showHudMetrics = v).build());

		advanced.addEntry(eb.startStringDropdownMenu(Component.literal("Target Status"), config.targetStatus).setSelections(List.of("minecraft:empty", "minecraft:biomes", "minecraft:noise", "minecraft:surface", "minecraft:features", "minecraft:full")).setDefaultValue("minecraft:features").setTooltip(Component.literal("Recommended: 'features' (fastest for terrain). 'full' is standard but slow.")).setSaveConsumer(v -> config.targetStatus = v).build());
		advanced.addEntry(eb.startStrList(Component.literal("Dimension Sequence"), config.dimensions).setDefaultValue(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")).setTooltip(Component.literal("The order in which dimensions will be automatically pregenerated.")).setSaveConsumer(v -> config.dimensions = v).build());
		advanced.addEntry(eb.startStrList(Component.literal("Points of Interest"), config.pointsOfInterest).setTooltip(Component.literal("Format: 'x,z'. These coordinates will be generated before the main spiral.")).setSaveConsumer(v -> config.pointsOfInterest = v).build());
		advanced.addEntry(eb.startBooleanToggle(Component.literal("Lighting Fix Mode"), config.lightingFixMode).setDefaultValue(false).setTooltip(Component.literal("Only runs the light engine. Use this to fix dark/black chunks.")).setSaveConsumer(v -> config.lightingFixMode = v).build());
		advanced.addEntry(eb.startBooleanToggle(Component.literal("Dry Run Mode"), config.dryRunMode).setDefaultValue(false).setTooltip(Component.literal("Shows the target area corners using particles without generating anything.")).setSaveConsumer(v -> config.dryRunMode = v).build());
		advanced.addEntry(eb.startIntSlider(Component.literal("Auto-Save Interval"), config.saveIntervalChunks, 0, 5000).setDefaultValue(500).setTooltip(Component.literal("Triggers a world save every X chunks to prevent progress loss on crash. 0 to disable.")).setSaveConsumer(v -> config.saveIntervalChunks = v).build());

		performance.addEntry(eb.startEnumSelector(Component.literal("CPU Preset"), RenderFastConfig.CpuUsageLevel.class, config.cpuUsageLevel).setDefaultValue(RenderFastConfig.CpuUsageLevel.MEDIUM).setTooltip(Component.literal("Automatically sets the max concurrent chunks.")).setSaveConsumer(v -> { config.cpuUsageLevel = v; config.maxConcurrentAsyncChunks = switch(v){case LOW->8;case MEDIUM->32;case HIGH->64;case VERY_HIGH->128;case INSANE->256;}; }).build());
		performance.addEntry(eb.startBooleanToggle(Component.literal("Adaptive Throttling"), config.adaptiveThrottling).setDefaultValue(true).setTooltip(Component.literal("Automatically pauses generation if the server becomes too laggy.")).setSaveConsumer(v -> config.adaptiveThrottling = v).build());
		performance.addEntry(eb.startIntSlider(Component.literal("Work Hours Start"), Integer.parseInt(config.startTime.split(":")[0]), 0, 23).setDefaultValue(0).setSaveConsumer(v -> config.startTime = String.format("%02d:00", v)).build());
		performance.addEntry(eb.startIntSlider(Component.literal("Work Hours End"), Integer.parseInt(config.endTime.split(":")[0]), 0, 23).setDefaultValue(23).setSaveConsumer(v -> config.endTime = String.format("%02d:59", v)).build());
		performance.addEntry(eb.startBooleanToggle(Component.literal("Turbo Mode"), config.turboMode).setDefaultValue(false).setTooltip(Component.literal("Bypasses all safety limits. Use with caution!")).setSaveConsumer(v -> config.turboMode = v).build());
		performance.addEntry(eb.startBooleanToggle(Component.literal("Auto-Turbo (Empty Server)"), config.autoTurboWhenEmpty).setDefaultValue(false).setTooltip(Component.literal("Enables Turbo Mode automatically when no players are online.")).setSaveConsumer(v -> config.autoTurboWhenEmpty = v).build());

		integration.addEntry(eb.startTextField(Component.literal("Discord Webhook URL"), config.discordWebhookUrl).setSaveConsumer(v -> config.discordWebhookUrl = v).build());
		integration.addEntry(eb.startIntSlider(Component.literal("Player Safety Radius"), config.playerSafetyRadius, 0, 16).setDefaultValue(0).setTooltip(Component.literal("Automatically loads chunks around players to prevent exploration lag.")).setSaveConsumer(v -> config.playerSafetyRadius = v).build());

		help.addEntry(eb.startTextDescription(Component.literal("§6--- Summary of Features ---")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§e⚡ Extreme Speed:§r Uses async pipelines to saturate your CPU.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§e🛡️ Smart Throttling:§r Monitors TPS, RAM, and Disk space automatically.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§e🌌 Multi-Dimension:§r Sequences through the Overworld, Nether, and End.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§e⏸️ Pause & Resume:§r Commands to stop and start without losing progress.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§e🗺️ Map Mod Sync:§r Automatically triggers BlueMap and Xaero renders.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§e🕒 Smooth ETA:§r Sliding window calculation for accurate time estimates.")).build());

		return builder.build();
	}
}
