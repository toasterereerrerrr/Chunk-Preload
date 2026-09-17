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
		general.addEntry(eb.startBooleanToggle(Component.literal("Turbo Mode"), config.turboMode).setDefaultValue(false).setTooltip(Component.literal("Bypasses all safety limits. Use with caution!")).setSaveConsumer(v -> config.turboMode = v).build());
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
		performance.addEntry(eb.startBooleanToggle(Component.literal("Auto-Turbo (Empty Server)"), config.autoTurboWhenEmpty).setDefaultValue(false).setTooltip(Component.literal("Enables Turbo Mode automatically when no players are online.")).setSaveConsumer(v -> config.autoTurboWhenEmpty = v).build());

		integration.addEntry(eb.startTextField(Component.literal("Discord Webhook URL"), config.discordWebhookUrl).setSaveConsumer(v -> config.discordWebhookUrl = v).build());
		integration.addEntry(eb.startIntSlider(Component.literal("Player Safety Radius"), config.playerSafetyRadius, 0, 16).setDefaultValue(0).setTooltip(Component.literal("Automatically loads chunks around players to prevent exploration lag.")).setSaveConsumer(v -> config.playerSafetyRadius = v).build());

		help.addEntry(eb.startTextDescription(Component.literal("§6§lRenderFast§r")).build());
		help.addEntry(eb.startTextDescription(Component.literal("RenderFast eliminates \"chunk-loading stutter\" by pre-generating terrain before you start exploring. It features §bSmart Throttling§r to monitor TPS, RAM, and Disk space, and §bTurbo Mode§r to bypass all safety limits.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§e§lKey Features:§r")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bExtreme Speed§r: Fully asynchronous loading pipeline with Immediate Refill logic.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bDimension Sequence§r: Preload any dimension in a configurable order.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bDetailed HUD§r: Displays exact pause reasons like LOW RAM or BUSY TICK.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bPriority POIs§r: Generate specific coordinates first.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bLighting Fix Mode§r: Specialized mode to fix dark/black chunks.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bDry Run Preview§r: Visualize corners using particles.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bDisk Estimation§r: Get an estimate of final file size.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bCompletion Reports§r: Summary reports to console and Discord.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bPlayer Safety Bubble§r: Automatically loads radius around players.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bMap Mod Sync§r: Support for BlueMap, Dynmap, and Xaero's Map.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§e§lCommands:§r")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§7/renderfast start [radius] [x] [z]§r")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§7/renderfast pause | resume | stop | reset | status§r")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§7/renderfast border | estimate | dryrun | turbo§r")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§e§lSetup:§r")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• MC 1.21.x (26.2) | Fabric Loader | Cloth Config.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• Target Status: 'features' is 2x faster than 'full'.")).build());

		return builder.build();
	}
}
