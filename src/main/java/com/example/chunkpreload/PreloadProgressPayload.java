package com.example.chunkpreload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Sent from server to client with the current preload progress.
 * {@code active} tells the client whether it should be showing the HUD at all.
 */
public record PreloadProgressPayload(int done, int total, boolean active, String dimension, float chunksPerSecond, List<Integer> recentIndices) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(ChunkPreloadMod.MOD_ID, "progress");

	public static final Type<PreloadProgressPayload> TYPE =
			new Type<>(ID);

	public static final StreamCodec<RegistryFriendlyByteBuf, PreloadProgressPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, PreloadProgressPayload::done,
			ByteBufCodecs.INT, PreloadProgressPayload::total,
			ByteBufCodecs.BOOL, PreloadProgressPayload::active,
			ByteBufCodecs.STRING_UTF8, PreloadProgressPayload::dimension,
			ByteBufCodecs.FLOAT, PreloadProgressPayload::chunksPerSecond,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), PreloadProgressPayload::recentIndices,
			PreloadProgressPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
