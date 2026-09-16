package com.example.chunkpreload;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

public class ChunkPreloadClient implements ClientModInitializer {
	private static int done = 0;
	private static int total = 0;
	private static boolean active = false;
	private static boolean paused = false;
	private static String dimension = "minecraft:overworld";
	private static float chunksPerSecond = 0;
	private static String pauseReason = "";
	private static long completedAtMillis = -1;
	private static long worldJoinTime = -1;
	private static long startTime = -1;
	private static int startDone = 0;

	private static final int HIDE_AFTER_MILLIS = 3000;
	private static final int C2ME_MSG_DURATION_MILLIS = 10000;
	private static final boolean C2ME_INSTALLED = FabricLoader.getInstance().isModLoaded("c2me");

	private static String cachedLabel = "";
	private static int cachedLabelWidth = 0;
	private static long lastHudUpdateTime = 0;

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath(ChunkPreloadMod.MOD_ID, "category")
	);

	private static final KeyMapping OPEN_CONFIG_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.chunkpreload.open_config",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.getValue(),
			CATEGORY
	));

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(PreloadProgressPayload.TYPE, (payload, context) -> {
			done = payload.done();
			total = payload.total();
			active = payload.active();
			paused = payload.paused();
			dimension = payload.dimension();
			chunksPerSecond = payload.chunksPerSecond();
			pauseReason = payload.pauseReason();

			if (total > 0 && done < total) {
				completedAtMillis = -1;
				if (startTime < 0 && active && !paused) {
					startTime = System.currentTimeMillis();
					startDone = done;
				}
			} else if (total > 0 && done >= total && completedAtMillis < 0) {
				completedAtMillis = System.currentTimeMillis();
				startTime = -1;
			}
			updateCachedStrings();
		});

		HudElementRegistry.attachElementBefore(
				VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(ChunkPreloadMod.MOD_ID, "preload_hud"),
				ChunkPreloadClient::render
		);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> worldJoinTime = System.currentTimeMillis());

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			worldJoinTime = -1; completedAtMillis = -1; done = 0; total = 0; active = false; paused = false; startTime = -1; cachedLabel = ""; pauseReason = "";
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_CONFIG_KEY.consumeClick()) {
				if (client.gui.screen() == null) client.setScreenAndShow(ChunkPreloadConfigScreen.create(null));
			}
		});
	}

	private static void updateCachedStrings() {
		Minecraft client = Minecraft.getInstance();
		Font font = client.font;
		if (font == null) return;

		String etaStr = "";
		if (startTime > 0 && done > startDone && !paused) {
			long elapsed = System.currentTimeMillis() - startTime;
			double chunksPerMs = (double) (done - startDone) / elapsed;
			if (chunksPerMs > 0) {
				long remaining = (long) ((total - done) / chunksPerMs);
				etaStr = String.format(" | ETA: %dm %ds", (remaining / 60000), (remaining / 1000) % 60);
			}
		}

		String dimStr = dimension.replace("minecraft:", "");
		String shapeStr = ChunkPreloadMod.CONFIG.shape.toString().toLowerCase();
		int percent = (total > 0) ? (int) (((float) done / total) * 100f) : 0;
		
		String status;
		if (paused) {
			status = pauseReason.isEmpty() ? " (PAUSED)" : " (PAUSED: " + pauseReason + ")";
		} else {
			status = "";
		}

		if (ChunkPreloadMod.CONFIG.showHudMetrics) {
			cachedLabel = String.format("Preloading %s (%s): %d/%d (%d%%)%s | %.1f ch/s%s", dimStr, shapeStr, done, total, percent, status, chunksPerSecond, etaStr);
		} else {
			cachedLabel = String.format("Preloading %s (%s): %d/%d (%d%%)%s", dimStr, shapeStr, done, total, percent, status);
		}
		cachedLabelWidth = font.width(cachedLabel);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		boolean recentlyCompleted = completedAtMillis > 0 && System.currentTimeMillis() - completedAtMillis <= HIDE_AFTER_MILLIS;
		boolean showC2MEMessage = C2ME_INSTALLED && ChunkPreloadMod.CONFIG.showStatusMessages && worldJoinTime > 0 && (System.currentTimeMillis() - worldJoinTime) < C2ME_MSG_DURATION_MILLIS;

		if (!showC2MEMessage && ((!active && !recentlyCompleted) || total <= 0 || !ChunkPreloadMod.CONFIG.showHud)) return;

		Minecraft client = Minecraft.getInstance();
		Font font = client.font;
		int screenWidth = client.getWindow().getGuiScaledWidth();
		int margin = 6;
		int currentY = margin;

		if (showC2MEMessage) {
			String label = "C2ME detected - chunks will load quickly";
			graphics.text(font, label, screenWidth - font.width(label) - margin, currentY, ARGB.opaque(0xFF5555), true);
			currentY += font.lineHeight + 2;
		}

		if (active && ChunkPreloadMod.CONFIG.turboMode) {
			String label = "Turbo Mode";
			graphics.text(font, label, screenWidth - font.width(label) - margin, currentY, ARGB.opaque(0xFF5555), true);
			currentY += font.lineHeight + 2;
		}

		if (done >= total && total > 0) {
			if (recentlyCompleted && ChunkPreloadMod.CONFIG.showStatusMessages) {
				String label = "Done loading";
				graphics.text(font, label, screenWidth - font.width(label) - margin, currentY, ARGB.opaque(0x55FF55), true);
			}
			return;
		}

		if (!active || total <= 0 || !ChunkPreloadMod.CONFIG.showHud) return;

		if (System.currentTimeMillis() - lastHudUpdateTime > 1000) { updateCachedStrings(); lastHudUpdateTime = System.currentTimeMillis(); }

		int barWidth = 160;
		int x = screenWidth - Math.max(cachedLabelWidth, barWidth) - margin;
		int barY = currentY + font.lineHeight + 2;

		graphics.text(font, cachedLabel, x, currentY, ARGB.opaque(0xFFFFFF), true);
		graphics.fill(x, barY, x + barWidth, barY + 6, ARGB.opaque(0x333333));
		graphics.fill(x, barY, x + (int) (barWidth * Math.min(1f, (float) done / total)), barY + 6, ARGB.opaque(0x55CC55));
		graphics.outline(x, barY, barWidth, 6, ARGB.opaque(0xFFFFFF));

		if (ChunkPreloadMod.CONFIG.showHudMetrics) {
			Runtime r = Runtime.getRuntime();
			double mem = (double) (r.totalMemory() - r.freeMemory()) / r.maxMemory();
			String memStr = String.format("Memory: %d%%", (int)(mem * 100));
			graphics.text(font, memStr, screenWidth - font.width(memStr) - margin, barY + 8, ARGB.opaque(mem > 0.9 ? 0xFF5555 : (mem > 0.7 ? 0xFFFF55 : 0x55FF55)), true);
		}
	}
}
