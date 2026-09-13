package com.example.chunkpreload;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Tracks preload progress for a world so that a server restart mid-preload
 * resumes instead of starting over (and so a finished world never re-triggers).
 */
public class PreloadState extends SavedData {
	public static final Codec<PreloadState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.fieldOf("started").forGetter(s -> s.started),
			Codec.BOOL.fieldOf("completed").forGetter(s -> s.completed),
			Codec.INT.fieldOf("centerX").forGetter(s -> s.centerX),
			Codec.INT.fieldOf("centerZ").forGetter(s -> s.centerZ),
			Codec.INT.fieldOf("doneCount").forGetter(s -> s.doneCount)
	).apply(instance, PreloadState::new));

	public static final SavedDataType<PreloadState> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(ChunkPreloadMod.MOD_ID, "preload_state"),
			PreloadState::new,
			CODEC,
			null
	);

	boolean started = false;
	boolean completed = false;
	int centerX = 0;
	int centerZ = 0;
	int doneCount = 0;

	public PreloadState() {
	}

	private PreloadState(boolean started, boolean completed, int centerX, int centerZ, int doneCount) {
		this.started = started;
		this.completed = completed;
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.doneCount = doneCount;
	}

	public static PreloadState get(MinecraftServer server) {
		ServerLevel overworld = server.getLevel(ServerLevel.OVERWORLD);

		if (overworld == null) {
			return new PreloadState();
		}

		return overworld.getDataStorage().computeIfAbsent(TYPE);
	}

	public void markStarted(int chunkX, int chunkZ) {
		this.started = true;
		this.centerX = chunkX;
		this.centerZ = chunkZ;
		this.doneCount = 0;
		setDirty();
	}

	public void setDoneCount(int doneCount) {
		this.doneCount = doneCount;
		setDirty();
	}

	public void markCompleted() {
		this.completed = true;
		setDirty();
	}
}
