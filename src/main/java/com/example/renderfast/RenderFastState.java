package com.example.renderfast;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public class RenderFastState extends SavedData {
	public static final Codec<RenderFastState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.fieldOf("started").forGetter(s -> s.started),
			Codec.BOOL.fieldOf("completed").forGetter(s -> s.completed),
			Codec.BOOL.fieldOf("paused").forGetter(s -> s.paused),
			Codec.INT.fieldOf("centerX").forGetter(s -> s.centerX),
			Codec.INT.fieldOf("centerZ").forGetter(s -> s.centerZ),
			Codec.INT.fieldOf("doneCount").forGetter(s -> s.doneCount),
			Codec.STRING.fieldOf("dimension").orElse("minecraft:overworld").forGetter(s -> s.dimension),
			Codec.INT.fieldOf("radius").orElse(100).forGetter(s -> s.radius)
	).apply(instance, RenderFastState::new));

	public static final SavedDataType<RenderFastState> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(RenderFast.MOD_ID, "renderfast_state"),
			RenderFastState::new,
			CODEC,
			DataFixTypes.LEVEL
	);

	boolean started = false;
	boolean completed = false;
	boolean paused = false;
	int centerX = 0;
	int centerZ = 0;
	int doneCount = 0;
	String dimension = "minecraft:overworld";
	int radius = 100;

	public RenderFastState() {
	}

	private RenderFastState(boolean started, boolean completed, boolean paused, int centerX, int centerZ, int doneCount, String dimension, int radius) {
		this.started = started;
		this.completed = completed;
		this.paused = paused;
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.doneCount = doneCount;
		this.dimension = dimension;
		this.radius = radius;
	}

	public static RenderFastState get(MinecraftServer server) {
		ServerLevel overworld = server.getLevel(ServerLevel.OVERWORLD);
		if (overworld == null) return new RenderFastState();
		return overworld.getDataStorage().computeIfAbsent(TYPE);
	}

	public void markStarted(int chunkX, int chunkZ, String dimension, int radius) {
		this.started = true;
		this.completed = false;
		this.paused = false;
		this.centerX = chunkX;
		this.centerZ = chunkZ;
		this.doneCount = 0;
		this.dimension = dimension;
		this.radius = radius;
		setDirty();
	}

	public void setPaused(boolean paused) {
		this.paused = paused;
		setDirty();
	}

	public void markCompleted() {
		this.completed = true;
		this.paused = false;
		setDirty();
	}
}
