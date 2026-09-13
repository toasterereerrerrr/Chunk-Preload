package com.example.chunkpreload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Sent from server to client with the current preload progress.
 * {@code active} tells the client whether it should be showing the HUD at all.
 */
public record PreloadProgressPayload(int done, int total, boolean active) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(ChunkPreloadMod.MOD_ID, "progress");

	public static final CustomPacketPayload.Type<PreloadProgressPayload> TYPE =
			new CustomPacketPayload.Type<>(ID);

	public static final StreamCodec<RegistryFriendlyByteBuf, PreloadProgressPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, PreloadProgressPayload::done,
			ByteBufCodecs.INT, PreloadProgressPayload::total,
			ByteBufCodecs.BOOL, PreloadProgressPayload::active,
			PreloadProgressPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
