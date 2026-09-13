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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChunkPreloadClient implements ClientModInitializer {
	private static int done = 0;
	private static int total = 0;
	private static boolean active = false;
	private static long completedAtMillis = -1;
	private static long worldJoinTime = -1;

	private static final int HIDE_AFTER_MILLIS = 3000;
	private static final int C2ME_MSG_DURATION_MILLIS = 10000;
	private static final boolean C2ME_INSTALLED = FabricLoader.getInstance().isModLoaded("c2me");

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

			if (total > 0 && done < total) {
				completedAtMillis = -1;
			} else if (total > 0 && done >= total && completedAtMillis < 0) {
				completedAtMillis = System.currentTimeMillis();
			}
		});

		HudElementRegistry.attachElementBefore(
				VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(ChunkPreloadMod.MOD_ID, "preload_hud"),
				ChunkPreloadClient::render
		);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			worldJoinTime = System.currentTimeMillis();
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			worldJoinTime = -1;
			completedAtMillis = -1;
			done = 0;
			total = 0;
			active = false;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_CONFIG_KEY.consumeClick()) {
				if (client.gui.screen() == null) {
					client.setScreenAndShow(ChunkPreloadConfigScreen.create(null));
				}
			}
		});
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		boolean recentlyCompleted = completedAtMillis > 0 && System.currentTimeMillis() - completedAtMillis <= HIDE_AFTER_MILLIS;
		boolean showC2MEMessage = C2ME_INSTALLED && worldJoinTime > 0 && (System.currentTimeMillis() - worldJoinTime) < C2ME_MSG_DURATION_MILLIS;

		if (!showC2MEMessage && ((!active && !recentlyCompleted) || total <= 0 || !ChunkPreloadMod.CONFIG.showHud)) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		Font font = client.font;

		int screenWidth = client.getWindow().getGuiScaledWidth();
		int margin = 6;
		int currentY = margin;

		// 1. Render C2ME Message if applicable
		if (showC2MEMessage) {
			String c2meLabel = "C2ME detected - chunks will load quickly";
			int c2meWidth = font.width(c2meLabel);
			int c2meX = screenWidth - c2meWidth - margin;
			graphics.text(font, c2meLabel, c2meX, currentY, ARGB.opaque(0xFF5555), true);
			currentY += font.lineHeight + 2;
		}

		// 2. Render Progress or Done status
		if (done >= total && total > 0) {
			if (recentlyCompleted) {
				String label = "Done loading";
				int labelWidth = font.width(label);
				int labelX = screenWidth - labelWidth - margin;
				graphics.text(font, label, labelX, currentY, ARGB.opaque(0x55FF55), true);
			}
			return;
		}

		if (!active || total <= 0) return;

		int barWidth = 160;
		int barHeight = 6;

		float progress = Math.min(1f, (float) done / (float) total);
		int percent = (int) (progress * 100f);

		String label = "Preloading chunks: " + done + " / " + total + " (" + percent + "%)";
		int labelWidth = font.width(label);

		int labelX = screenWidth - Math.max(labelWidth, barWidth) - margin;
		int barX = screenWidth - Math.max(labelWidth, barWidth) - margin;
		int barY = currentY + font.lineHeight + 2;

		graphics.text(font, label, labelX, currentY, ARGB.opaque(0xFFFFFF), true);

		graphics.fill(barX, barY, barX + barWidth, barY + barHeight, ARGB.opaque(0x333333));
		int filledWidth = (int) (barWidth * progress);
		graphics.fill(barX, barY, barX + filledWidth, barY + barHeight, ARGB.opaque(0x55CC55));
		graphics.outline(barX, barY, barWidth, barHeight, ARGB.opaque(0xFFFFFF));
	}
}