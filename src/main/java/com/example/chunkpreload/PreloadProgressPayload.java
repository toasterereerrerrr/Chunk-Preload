package com.example.chunkpreload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PreloadProgressPayload(
		int done,
		int total,
		boolean active,
		boolean paused,
		String dimension,
		float chunksPerSecond,
		String pauseReason
) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(ChunkPreloadMod.MOD_ID, "progress");

	public static final Type<PreloadProgressPayload> TYPE = new Type<>(ID);

	public static final StreamCodec<RegistryFriendlyByteBuf, PreloadProgressPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, PreloadProgressPayload::done,
			ByteBufCodecs.INT, PreloadProgressPayload::total,
			ByteBufCodecs.BOOL, PreloadProgressPayload::active,
			ByteBufCodecs.BOOL, PreloadProgressPayload::paused,
			ByteBufCodecs.STRING_UTF8, PreloadProgressPayload::dimension,
			ByteBufCodecs.FLOAT, PreloadProgressPayload::chunksPerSecond,
			ByteBufCodecs.STRING_UTF8, PreloadProgressPayload::pauseReason,
			PreloadProgressPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
