package dev.farlands;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * Square vertical shafts with dead-straight walls, scattered between spires.
 * Returns -100.0 inside a pit (overwhelms any terrain density), 0.0 outside.
 * Combined with terrain via add() to carve shafts all the way down.
 */
public final class VoidPitDensityFunction implements DensityFunction.Base {
	public static final MapCodec<VoidPitDensityFunction> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.INT.fieldOf("cell_size").forGetter(f -> f.cellSize),
			Codec.INT.fieldOf("pit_size").forGetter(f -> f.pitSize),
			Codec.DOUBLE.fieldOf("chance").forGetter(f -> f.chance),
			Codec.INT.optionalFieldOf("salt", 0).forGetter(f -> f.salt)
	).apply(instance, VoidPitDensityFunction::new));

	public static final CodecHolder<VoidPitDensityFunction> CODEC_HOLDER = CodecHolder.of(CODEC);

	private final int cellSize;
	private final int pitSize;
	private final double chance;
	private final int salt;

	public VoidPitDensityFunction(int cellSize, int pitSize, double chance, int salt) {
		this.cellSize = Math.max(8, cellSize);
		this.pitSize = Math.max(1, pitSize);
		this.chance = chance;
		this.salt = salt;
	}

	@Override
	public double sample(NoisePos pos) {
		int x = pos.blockX();
		int z = pos.blockZ();

		int cellX = Math.floorDiv(x, cellSize);
		int cellZ = Math.floorDiv(z, cellSize);
		long h = hash(cellX, cellZ, salt);

		if ((h & 0xFFFFL) / 65536.0 >= chance) return 0.0;

		int localX = Math.floorMod(x, cellSize);
		int localZ = Math.floorMod(z, cellSize);

		int pitX = (int) ((h >>> 16) & 0xFFL) * (cellSize - pitSize) / 256;
		int pitZ = (int) ((h >>> 24) & 0xFFL) * (cellSize - pitSize) / 256;

		if (localX >= pitX && localX < pitX + pitSize
				&& localZ >= pitZ && localZ < pitZ + pitSize) {
			return -100.0;
		}
		return 0.0;
	}

	private static long hash(int cellX, int cellZ, int salt) {
		long h = cellX * 0x9E3779B97F4A7C15L ^ cellZ * 0xC2B2AE3D27D4EB4FL ^ (long) salt * 0x165667B19E3779F9L;
		h ^= h >>> 33;
		h *= 0xFF51AFD7ED558CCDL;
		h ^= h >>> 33;
		h *= 0xC4CEB9FE1A85EC53L;
		h ^= h >>> 33;
		return h;
	}

	@Override
	public DensityFunction apply(DensityFunctionVisitor visitor) {
		return visitor.apply(this);
	}

	@Override
	public double minValue() {
		return -100.0;
	}

	@Override
	public double maxValue() {
		return 0.0;
	}

	@Override
	public CodecHolder<? extends DensityFunction> getCodecHolder() {
		return CODEC_HOLDER;
	}
}
