package com.example.renderfast;

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

public class RenderFastClient implements ClientModInitializer {
	private static int done = 0;
	private static int total = 0;
	private static boolean active = false;
	private static boolean paused = false;
	private static String dimension = "minecraft:overworld";
	private static float chunksPerSecond = 0;
	private static String pauseReason = "";
	private static byte[] mapData = new byte[50];
	
	private static long completedAtMillis = -1;
	private static long worldJoinTime = -1;

	private static final int HIDE_AFTER_MILLIS = 3000;
	private static final boolean C2ME_INSTALLED = FabricLoader.getInstance().isModLoaded("c2me");

	private static String cachedLabel = "";
	private static int cachedLabelWidth = 0;
	private static long lastHudUpdateTime = 0;

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(RenderFast.MOD_ID, "category"));
	private static final KeyMapping OPEN_CONFIG_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.renderfast.open_config", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY));

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(RenderFastProgressPayload.TYPE, (payload, _) -> {
			done = payload.done(); total = payload.total(); active = payload.active(); paused = payload.paused();
			dimension = payload.dimension(); chunksPerSecond = payload.chunksPerSecond();
			pauseReason = payload.pauseReason(); mapData = payload.mapData();

			if (total > 0 && done >= total && completedAtMillis < 0) completedAtMillis = System.currentTimeMillis();
			else if (total > 0 && done < total) completedAtMillis = -1;
			updateCachedStrings();
		});

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(RenderFast.MOD_ID, "renderfast_hud"), RenderFastClient::render);
		ClientPlayConnectionEvents.JOIN.register((_, _, _) -> worldJoinTime = System.currentTimeMillis());
		ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> { worldJoinTime = -1; completedAtMillis = -1; done = 0; total = 0; active = false; cachedLabel = ""; });
		ClientTickEvents.END_CLIENT_TICK.register(c -> { while (OPEN_CONFIG_KEY.consumeClick()) if (c.gui.screen() == null) c.setScreenAndShow(RenderFastConfigScreen.create(null)); });
	}

	private static void updateCachedStrings() {
		Minecraft c = Minecraft.getInstance(); Font f = c.font;
		String dimStr = dimension.replace("minecraft:", "");
		int percent = (total > 0) ? (int)(((float)done/total)*100) : 0;
		String status = paused ? (pauseReason.isEmpty() ? " (PAUSED)" : " (" + pauseReason + ")") : "";
		String eta = "";

		if (chunksPerSecond > 0) {
			long rem = (long)((total - done) / chunksPerSecond);
			eta = String.format(" | ETA: %dm %ds", rem / 60, rem % 60);
		}

		if (RenderFast.CONFIG.showHudMetrics) cachedLabel = String.format("Preloading %s: %d/%d (%d%%)%s | %.1f ch/s%s", dimStr, done, total, percent, status, chunksPerSecond, eta);
		else cachedLabel = String.format("Preloading %s: %d%% (%d/%d)%s", dimStr, percent, done, total, status);
		cachedLabelWidth = f.width(cachedLabel);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		boolean completed = completedAtMillis > 0 && System.currentTimeMillis() - completedAtMillis <= HIDE_AFTER_MILLIS;
		boolean showC2ME = C2ME_INSTALLED && RenderFast.CONFIG.showStatusMessages && worldJoinTime > 0 && (System.currentTimeMillis() - worldJoinTime) < 10000;
		if (!showC2ME && ((!active && !completed) || total <= 0 || !RenderFast.CONFIG.showHud)) return;

		Minecraft mc = Minecraft.getInstance(); Font font = mc.font;
		int width = mc.getWindow().getGuiScaledWidth();
		int margin = 6; int y = margin;

		if (showC2ME) { String l = "C2ME Detected - Extreme Generation Enabled"; graphics.text(font, l, width - font.width(l) - margin, y, ARGB.opaque(0x55FFFF), true); y += 12; }
		if (active && RenderFast.CONFIG.turboMode) { String l = "Turbo Mode Active"; graphics.text(font, l, width - font.width(l) - margin, y, ARGB.opaque(0xFF5555), true); y += 12; }

		if (completed) { graphics.text(font, "Generation Complete", width - font.width("Generation Complete") - margin, y, ARGB.opaque(0x55FF55), true); return; }
		if (!active) return;
		if (System.currentTimeMillis() - lastHudUpdateTime > 1000) { updateCachedStrings(); lastHudUpdateTime = System.currentTimeMillis(); }

		int barW = 160; int x = width - Math.max(cachedLabelWidth, barW) - margin;
		graphics.text(font, cachedLabel, x, y, ARGB.opaque(0xFFFFFF), true); y += 12;
		graphics.fill(x, y, x + barW, y + 6, ARGB.opaque(0x333333));
		graphics.fill(x, y, x + (int)(barW * Math.min(1f, (float)done/total)), y + 6, ARGB.opaque(0x55CC55));
		graphics.outline(x, y, barW, 6, ARGB.opaque(0xFFFFFF));

		if (RenderFast.CONFIG.showMiniMap) {
			y += 10;
			for (int row = 0; row < 20; row++) {
				StringBuilder grid = new StringBuilder();
				for (int col = 0; col < 20; col++) {
					int bitIdx = row * 20 + col;
					boolean bit = (mapData[bitIdx / 8] & (1 << (bitIdx % 8))) != 0;
					grid.append(bit ? "█" : "░");
				}
				String rowStr = grid.toString();
				graphics.text(font, rowStr, width - font.width(rowStr) - margin, y, ARGB.opaque(0xAAAAAA), false);
				y += 4;
			}
		}
	}
}
