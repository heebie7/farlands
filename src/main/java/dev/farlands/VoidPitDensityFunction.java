package dev.farlands;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * Vertical shafts with dead-straight walls, punched clean through whatever terrain is above them.
 * Returns -100.0 inside a shaft (overwhelms any terrain density), 0.0 outside. Combined with the
 * terrain via add().
 *
 * Two modes:
 *  - plain (shape_variety = false, the default, what the spires zone has always used): one square
 *    of a fixed size per cell.
 *  - varied (shape_variety = true, used by caverns): size is rolled per cell between half and one
 *    and a half of pit_size, and the footprint is one of four shapes - square, a rectangle drawn
 *    out along X, the same along Z, or two squares overlapping corner to corner.
 *
 * Deliberately NOT here: a per-block ragged edge. Density functions are sampled on the noise grid
 * (4 blocks horizontally in the overworld settings) and interpolated between samples, so anything
 * finer than that grid does not survive to become blocks - it just aliases. Shape variety works
 * because it moves whole walls by tens of blocks.
 *
 * The footprint test is a public static so the feature that clears bedrock and aquifer water out
 * of the shaft (VoidShaftClear) tests exactly the same volume the terrain was carved from.
 */
public final class VoidPitDensityFunction implements DensityFunction.Base {
	public static final MapCodec<VoidPitDensityFunction> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.INT.fieldOf("cell_size").forGetter(f -> f.cellSize),
			Codec.INT.fieldOf("pit_size").forGetter(f -> f.pitSize),
			Codec.DOUBLE.fieldOf("chance").forGetter(f -> f.chance),
			Codec.INT.optionalFieldOf("salt", 0).forGetter(f -> f.salt),
			Codec.BOOL.optionalFieldOf("shape_variety", false).forGetter(f -> f.shapeVariety)
	).apply(instance, VoidPitDensityFunction::new));

	public static final CodecHolder<VoidPitDensityFunction> CODEC_HOLDER = CodecHolder.of(CODEC);

	private final int cellSize;
	private final int pitSize;
	private final double chance;
	private final int salt;
	private final boolean shapeVariety;

	public VoidPitDensityFunction(int cellSize, int pitSize, double chance, int salt, boolean shapeVariety) {
		this.cellSize = Math.max(8, cellSize);
		this.pitSize = Math.max(1, pitSize);
		this.chance = chance;
		this.salt = salt;
		this.shapeVariety = shapeVariety;
	}

	/** True if this block column falls inside a shaft. Shared with VoidShaftClear. */
	public static boolean inPit(int x, int z, int cellSize, int pitSize, double chance, int salt, boolean shapeVariety) {
		cellSize = Math.max(8, cellSize);
		pitSize = Math.max(1, pitSize);

		int cellX = Math.floorDiv(x, cellSize);
		int cellZ = Math.floorDiv(z, cellSize);
		long h = hash(cellX, cellZ, salt);

		if ((h & 0xFFFFL) / 65536.0 >= chance) return false;

		int localX = Math.floorMod(x, cellSize);
		int localZ = Math.floorMod(z, cellSize);

		if (!shapeVariety) {
			int pitX = (int) ((h >>> 16) & 0xFFL) * (cellSize - pitSize) / 256;
			int pitZ = (int) ((h >>> 24) & 0xFFL) * (cellSize - pitSize) / 256;
			return inRect(localX, localZ, pitX, pitZ, pitSize, pitSize);
		}

		// Half to one-and-a-half of pit_size, so shafts read as individuals rather than a stamp.
		int size = Math.max(4, pitSize / 2 + (int) (((h >>> 8) & 0xFFL) * pitSize / 256L));
		int mode = (int) ((h >>> 56) & 3L);

		int width = size;
		int depth = size;
		if (mode == 1) {
			width = size * 2;
			depth = Math.max(4, size / 2);
		} else if (mode == 2) {
			width = Math.max(4, size / 2);
			depth = size * 2;
		}

		int span = Math.max(width, depth) * 2;
		int room = Math.max(1, cellSize - span);
		int pitX = (int) ((h >>> 16) & 0xFFL) * room / 256;
		int pitZ = (int) ((h >>> 24) & 0xFFL) * room / 256;

		if (inRect(localX, localZ, pitX, pitZ, width, depth)) return true;

		// Mode 3 is two squares overlapping corner to corner: a wide irregular hole rather than a
		// clean box, and the only mode where the walls are not one straight run.
		if (mode == 3) {
			int shift = Math.max(2, size / 2);
			return inRect(localX, localZ, pitX + shift, pitZ + shift, width, depth);
		}
		return false;
	}

	private static boolean inRect(int x, int z, int originX, int originZ, int width, int depth) {
		return x >= originX && x < originX + width && z >= originZ && z < originZ + depth;
	}

	@Override
	public double sample(NoisePos pos) {
		return inPit(pos.blockX(), pos.blockZ(), cellSize, pitSize, chance, salt, shapeVariety) ? -100.0 : 0.0;
	}

	public static long hash(int cellX, int cellZ, int salt) {
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
