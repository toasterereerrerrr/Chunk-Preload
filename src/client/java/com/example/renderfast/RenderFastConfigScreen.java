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
		general.addEntry(eb.startBooleanToggle(Component.literal("Turbo Mode"), config.turboMode).setDefaultValue(false).setTooltip(Component.literal("Bypasses safety throttles. Use with caution as it significantly increases server load!")).setSaveConsumer(v -> config.turboMode = v).build());
		general.addEntry(eb.startIntSlider(Component.literal("Generation Radius"), config.radius, 10, 2048).setDefaultValue(100).setTooltip(Component.literal("How many chunks out from the center to generate (10-2048).")).setSaveConsumer(v -> config.radius = v).build());
		general.addEntry(eb.startEnumSelector(Component.literal("Shape"), RenderFastConfig.Shape.class, config.shape).setDefaultValue(RenderFastConfig.Shape.CIRCLE).setTooltip(Component.literal("Circle is more natural; Square is better for filling world borders.")).setSaveConsumer(v -> config.shape = v).build());
		general.addEntry(eb.startBooleanToggle(Component.literal("Show HUD"), config.showHud).setDefaultValue(true).setTooltip(Component.literal("Display the progress bar in the top-right corner.")).setSaveConsumer(v -> config.showHud = v).build());
		general.addEntry(eb.startBooleanToggle(Component.literal("Show Mini-Map"), config.showMiniMap).setDefaultValue(false).setTooltip(Component.literal("Display a tiny 20x20 visual heatmap of current progress.")).setSaveConsumer(v -> config.showMiniMap = v).build());
		general.addEntry(eb.startBooleanToggle(Component.literal("Show Metrics"), config.showHudMetrics).setDefaultValue(false).setTooltip(Component.literal("Show detailed Speed (ch/s) and ETA on the HUD.")).setSaveConsumer(v -> config.showHudMetrics = v).build());
		general.addEntry(eb.startBooleanToggle(Component.literal("Show Console Logs"), config.showStatusMessages).setDefaultValue(true).setTooltip(Component.literal("Log progress updates to the server console.")).setSaveConsumer(v -> config.showStatusMessages = v).build());
		general.addEntry(eb.startIntSlider(Component.literal("Console Log Frequency (s)"), config.consoleReportIntervalSeconds, 1, 300).setDefaultValue(30).setTooltip(Component.literal("How often to log progress to the console.")).setSaveConsumer(v -> config.consoleReportIntervalSeconds = v).build());

		general.addEntry(eb.startIntSlider(Component.literal("ETA Smoothing Window (s)"), config.smoothEtaWindowSeconds, 5, 120).setDefaultValue(30).setTooltip(Component.literal("Window size for CPS and ETA calculations. Higher is smoother but slower to react.")).setSaveConsumer(v -> config.smoothEtaWindowSeconds = v).build());

		advanced.addEntry(eb.startStringDropdownMenu(Component.literal("Target Status"), config.targetStatus).setSelections(List.of("minecraft:empty", "minecraft:biomes", "minecraft:noise", "minecraft:surface", "minecraft:features", "minecraft:full")).setDefaultValue("minecraft:features").setTooltip(Component.literal("Generation depth. 'features' is recommended as it generates terrain and decorations §c2x faster§r than 'full'.")).setSaveConsumer(v -> config.targetStatus = v).build());
		advanced.addEntry(eb.startBooleanToggle(Component.literal("Immediate Refill"), config.immediateRefill).setDefaultValue(false).setTooltip(Component.literal("Queue more chunks immediately as soon as one finishes. §cUnstable§r on some server setups.")).setSaveConsumer(v -> config.immediateRefill = v).build());
		advanced.addEntry(eb.startStrList(Component.literal("Dimension Sequence"), config.dimensions).setDefaultValue(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")).setTooltip(Component.literal("Order for automatic dimension pregeneration. Dimension IDs are case-sensitive.")).setSaveConsumer(v -> config.dimensions = v).build());
		advanced.addEntry(eb.startStrList(Component.literal("Points of Interest"), config.pointsOfInterest).setTooltip(Component.literal("Format: 'x,z'. These coordinates will be generated before the main spiral.")).setSaveConsumer(v -> config.pointsOfInterest = v).build());
		advanced.addEntry(eb.startBooleanToggle(Component.literal("Lighting Fix Mode"), config.lightingFixMode).setDefaultValue(false).setTooltip(Component.literal("Only runs the light engine. Use this to fix dark/black chunks.")).setSaveConsumer(v -> config.lightingFixMode = v).build());
		advanced.addEntry(eb.startBooleanToggle(Component.literal("Structure Only Mode"), config.structureOnlyMode).setDefaultValue(false).setTooltip(Component.literal("Only generates structure starts. Much faster for pre-generating structures.")).setSaveConsumer(v -> config.structureOnlyMode = v).build());
		advanced.addEntry(eb.startBooleanToggle(Component.literal("Dry Run Mode"), config.dryRunMode).setDefaultValue(false).setTooltip(Component.literal("Shows the target area corners using particles without generating anything.")).setSaveConsumer(v -> config.dryRunMode = v).build());
		advanced.addEntry(eb.startIntSlider(Component.literal("Auto-Save Interval"), config.saveIntervalChunks, 0, 5000).setDefaultValue(500).setTooltip(Component.literal("Triggers a world save every X chunks to prevent progress loss on crash. 0 to disable.")).setSaveConsumer(v -> config.saveIntervalChunks = v).build());
		advanced.addEntry(eb.startIntField(Component.literal("Restart After Chunks"), config.restartAfterChunks).setDefaultValue(0).setTooltip(Component.literal("Automatically halts the server after generating X chunks (0 to disable). Useful for clearing memory leaks.")).setSaveConsumer(v -> config.restartAfterChunks = v).build());
		advanced.addEntry(eb.startTextField(Component.literal("On Complete Command"), config.onCompleteCommand).setDefaultValue("").setTooltip(Component.literal("Command to run when pre-generation finishes.")).setSaveConsumer(v -> config.onCompleteCommand = v).build());

		performance.addEntry(eb.startEnumSelector(Component.literal("CPU Preset"), RenderFastConfig.CpuUsageLevel.class, config.cpuUsageLevel).setDefaultValue(RenderFastConfig.CpuUsageLevel.MEDIUM).setTooltip(Component.literal("Automatically sets the max concurrent chunks. Higher presets increase speed but raise the risk of main-thread hangs.")).setSaveConsumer(v -> { config.cpuUsageLevel = v; config.maxConcurrentAsyncChunks = switch(v){case LOW->4;case MEDIUM->16;case HIGH->24;case VERY_HIGH->32;case INSANE->48;}; }).build());
		performance.addEntry(eb.startBooleanToggle(Component.literal("Adaptive Throttling"), config.adaptiveThrottling).setDefaultValue(true).setTooltip(Component.literal("Automatically pauses generation if the server becomes too laggy.")).setSaveConsumer(v -> config.adaptiveThrottling = v).build());
		performance.addEntry(eb.startBooleanToggle(Component.literal("Watchdog Breather"), config.watchdogBreather).setDefaultValue(true).setTooltip(Component.literal("Prevents the mod from blocking a single tick for too long. Vital for preventing 60s Watchdog crashes.")).setSaveConsumer(v -> config.watchdogBreather = v).build());
		performance.addEntry(eb.startDoubleField(Component.literal("Breather Threshold (ms)"), config.breatherThresholdMs).setDefaultValue(5000.0).setTooltip(Component.literal("Yield to the server if a single tick takes longer than this (Default: 5000ms). Setting this too high risks a crash.")).setSaveConsumer(v -> config.breatherThresholdMs = v).build());
		performance.addEntry(eb.startDoubleField(Component.literal("Recovery Threshold (ms)"), config.recoveryThresholdMs).setDefaultValue(2000.0).setTooltip(Component.literal("Wait until the average tick time drops below this before resuming generation (Default: 2000ms).")).setSaveConsumer(v -> config.recoveryThresholdMs = v).build());
		performance.addEntry(eb.startDoubleField(Component.literal("Memory Threshold"), config.memoryUsageThreshold).setDefaultValue(0.9).setTooltip(Component.literal("Pause generation if RAM usage exceeds this (e.g. 0.9 for 90%).")).setSaveConsumer(v -> config.memoryUsageThreshold = v).build());
		performance.addEntry(eb.startLongField(Component.literal("Min Disk Space (MB)"), config.minFreeDiskSpaceMb).setDefaultValue(512L).setTooltip(Component.literal("Pause if free disk space falls below this.")).setSaveConsumer(v -> config.minFreeDiskSpaceMb = v).build());
		performance.addEntry(eb.startBooleanToggle(Component.literal("Aggressive GC"), config.aggressiveUnload).setDefaultValue(false).setTooltip(Component.literal("Manually trigger Garbage Collection when memory is low. §cHigh lag impact§r.")).setSaveConsumer(v -> config.aggressiveUnload = v).build());
		performance.addEntry(eb.startIntSlider(Component.literal("Work Hours Start"), Integer.parseInt(config.startTime.split(":")[0]), 0, 23).setDefaultValue(0).setSaveConsumer(v -> config.startTime = String.format("%02d:00", v)).build());
		performance.addEntry(eb.startIntSlider(Component.literal("Work Hours End"), Integer.parseInt(config.endTime.split(":")[0]), 0, 23).setDefaultValue(23).setSaveConsumer(v -> config.endTime = String.format("%02d:59", v)).build());
		performance.addEntry(eb.startBooleanToggle(Component.literal("Auto-Turbo (Empty Server)"), config.autoTurboWhenEmpty).setDefaultValue(false).setTooltip(Component.literal("Enables Turbo Mode automatically when no players are online.")).setSaveConsumer(v -> config.autoTurboWhenEmpty = v).build());
		performance.addEntry(eb.startBooleanToggle(Component.literal("Stop When Offline"), config.pauseWhenEmpty).setDefaultValue(false).setTooltip(Component.literal("Automatically pauses generation when no players are online.")).setSaveConsumer(v -> config.pauseWhenEmpty = v).build());
		performance.addEntry(eb.startBooleanToggle(Component.literal("Stop When Online"), config.onlyPreloadWhenEmpty).setDefaultValue(false).setTooltip(Component.literal("Automatically pauses generation when players are online.")).setSaveConsumer(v -> config.onlyPreloadWhenEmpty = v).build());

		integration.addEntry(eb.startTextField(Component.literal("Discord Webhook URL"), config.discordWebhookUrl).setSaveConsumer(v -> config.discordWebhookUrl = v).build());
		integration.addEntry(eb.startIntSlider(Component.literal("Player Safety Radius"), config.playerSafetyRadius, 0, 16).setDefaultValue(0).setTooltip(Component.literal("Automatically loads chunks around players to prevent exploration lag.")).setSaveConsumer(v -> config.playerSafetyRadius = v).build());
		integration.addEntry(eb.startBooleanToggle(Component.literal("Voxy Integration"), config.voxyIntegration).setDefaultValue(true).setTooltip(Component.literal("Synchronize generation with Voxy (Iris LOD).")).setSaveConsumer(v -> config.voxyIntegration = v).build());
		integration.addEntry(eb.startBooleanToggle(Component.literal("Distant Horizons Support"), config.distantHorizonsIntegration).setDefaultValue(true).setTooltip(Component.literal("Ensure chunks are finalized and saved for Distant Horizons LOD generation.")).setSaveConsumer(v -> config.distantHorizonsIntegration = v).build());

		help.addEntry(eb.startTextDescription(Component.literal("§6§lRenderFast§r")).build());
		help.addEntry(eb.startTextDescription(Component.literal("A professional, high-performance chunk pre-generator designed for §bServer Stability§r. It saturates your hardware while ensuring the main thread remains responsive.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§e§lStability Features:§r")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bWatchdog Breather§r: Aggressively yields to the server to prevent the 60s crash limit.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bLag Recovery§r: Automatically pauses during TPS drops and waits for the server to stabilize.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bThrottled Pipeline§r: Uses small, frequent request batches to avoid main-thread hangs.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§e§lPerformance:§r")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bTarget Status§r: Using 'features' generates terrain/trees/ores up to §c2x faster§r than 'full'.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("• §bDimension Sequence§r: Configure the order to automatically preload Overworld, Nether, and End.")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§e§lCommands:§r")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§7/rf start | status | pause | stop | reset | help§r")).build());
		help.addEntry(eb.startTextDescription(Component.literal("§7/rf config [radius | cpu | ram | disk | webhook]§r")).build());

		return builder.build();
	}
}
