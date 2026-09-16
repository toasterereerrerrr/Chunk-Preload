package com.example.renderfast;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RenderFastProgressPayload(
		int done,
		int total,
		boolean active,
		boolean paused,
		String dimension,
		float chunksPerSecond,
		String pauseReason,
		byte[] mapData
) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(RenderFast.MOD_ID, "progress");

	public static final Type<RenderFastProgressPayload> TYPE = new Type<>(ID);

	public static final StreamCodec<RegistryFriendlyByteBuf, RenderFastProgressPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, RenderFastProgressPayload::done,
			ByteBufCodecs.INT, RenderFastProgressPayload::total,
			ByteBufCodecs.BOOL, RenderFastProgressPayload::active,
			ByteBufCodecs.BOOL, RenderFastProgressPayload::paused,
			ByteBufCodecs.STRING_UTF8, RenderFastProgressPayload::dimension,
			ByteBufCodecs.FLOAT, RenderFastProgressPayload::chunksPerSecond,
			ByteBufCodecs.STRING_UTF8, RenderFastProgressPayload::pauseReason,
			ByteBufCodecs.BYTE_ARRAY, RenderFastProgressPayload::mapData,
			RenderFastProgressPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
