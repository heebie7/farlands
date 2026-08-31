package dev.farlands;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * Vertical shafts punched clean through whatever terrain is above them. Returns -100.0 inside a
 * shaft (overwhelms any terrain density), 0.0 outside. Combined with the terrain via add().
 *
 * Two footprint shapes, chosen by the "shape" field:
 *
 *  - "square": one axis-aligned square of a fixed size per cell. This is the small, dense pitting
 *    between the spires that has been in the mod since v0.3 and that Т likes; left alone.
 *
 *  - "organic": a closed blob with no straight edges anywhere. Radius varies with the angle around
 *    the centre through three harmonics whose phases and amplitude come out of the cell hash, so
 *    the outline is lobed and different in every cell, and the wall it leaves is a cliff at an
 *    arbitrary angle rather than a corner of a box. Т on the v0.8.0 shafts: "провалы - просто
 *    квадраты... хотелось бы, чтобы они выглядели больше как часть генерации, абстрактная форма,
 *    которая просто вот так обрывается, как обрыв".
 *
 * Deliberately NOT here: a per-block ragged edge. Density functions are sampled on the noise grid
 * (4 blocks horizontally in the overworld settings) and interpolated between samples, so anything
 * finer than that grid does not survive to become blocks - it just aliases. The harmonics work
 * because they move whole walls by tens of blocks.
 *
 * The footprint test is a public static so the feature that clears bedrock and aquifer water out
 * of a shaft (VoidShaftClear) tests exactly the same volume the terrain was carved from.
 */
public final class VoidPitDensityFunction implements DensityFunction.Base {
	public static final int SHAPE_SQUARE = 0;
	public static final int SHAPE_ORGANIC = 1;

	private static final double TAU = Math.PI * 2.0;
	/** Largest multiple of the base radius the harmonics can reach. Used for the cheap early-out. */
	private static final double ORGANIC_REACH = 1.8;

	public static final MapCodec<VoidPitDensityFunction> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.INT.fieldOf("cell_size").forGetter(f -> f.cellSize),
			Codec.INT.fieldOf("pit_size").forGetter(f -> f.pitSize),
			Codec.DOUBLE.fieldOf("chance").forGetter(f -> f.chance),
			Codec.INT.optionalFieldOf("salt", 0).forGetter(f -> f.salt),
			Codec.STRING.optionalFieldOf("shape", "square").forGetter(f -> f.shapeName)
	).apply(instance, VoidPitDensityFunction::new));

	public static final CodecHolder<VoidPitDensityFunction> CODEC_HOLDER = CodecHolder.of(CODEC);

	private final int cellSize;
	private final int pitSize;
	private final double chance;
	private final int salt;
	private final String shapeName;
	private final int shape;

	public VoidPitDensityFunction(int cellSize, int pitSize, double chance, int salt, String shapeName) {
		this.cellSize = Math.max(8, cellSize);
		this.pitSize = Math.max(1, pitSize);
		this.chance = chance;
		this.salt = salt;
		this.shapeName = shapeName;
		this.shape = shapeId(shapeName);
	}

	public static int shapeId(String name) {
		return "organic".equals(name) ? SHAPE_ORGANIC : SHAPE_SQUARE;
	}

	/** True if this block column falls inside a shaft. Shared with VoidShaftClear. */
	public static boolean inPit(int x, int z, int cellSize, int pitSize, double chance, int salt, int shape) {
		cellSize = Math.max(8, cellSize);
		pitSize = Math.max(1, pitSize);

		int cellX = Math.floorDiv(x, cellSize);
		int cellZ = Math.floorDiv(z, cellSize);
		long h = hash(cellX, cellZ, salt);

		if ((h & 0xFFFFL) / 65536.0 >= chance) return false;

		int localX = Math.floorMod(x, cellSize);
		int localZ = Math.floorMod(z, cellSize);

		if (shape != SHAPE_ORGANIC) {
			int pitX = (int) ((h >>> 16) & 0xFFL) * (cellSize - pitSize) / 256;
			int pitZ = (int) ((h >>> 24) & 0xFFL) * (cellSize - pitSize) / 256;
			return localX >= pitX && localX < pitX + pitSize
					&& localZ >= pitZ && localZ < pitZ + pitSize;
		}

		// Half to one-and-a-half of the nominal radius, so shafts read as individuals.
		double baseRadius = Math.max(3.0, pitSize * 0.5 * (0.6 + ((h >>> 8) & 0xFFL) / 255.0));
		// Keep the whole blob inside its cell: a blob clipped by the cell boundary would leave the
		// dead-straight edge this shape exists to avoid.
		double margin = baseRadius * (ORGANIC_REACH + 0.1);
		double room = Math.max(1.0, cellSize - 2.0 * margin);
		double centreX = margin + ((h >>> 16) & 0xFFL) / 256.0 * room;
		double centreZ = margin + ((h >>> 24) & 0xFFL) / 256.0 * room;

		double dx = localX + 0.5 - centreX;
		double dz = localZ + 0.5 - centreZ;
		double distanceSquared = dx * dx + dz * dz;

		double reach = baseRadius * ORGANIC_REACH;
		if (distanceSquared > reach * reach) return false;              // cheap out for most samples
		if (distanceSquared < baseRadius * 0.2 * baseRadius * 0.2) return true;

		double phase1 = ((h >>> 32) & 0x3FL) / 64.0 * TAU;
		double phase2 = ((h >>> 38) & 0x3FL) / 64.0 * TAU;
		double phase3 = ((h >>> 44) & 0x3FL) / 64.0 * TAU;
		double lobe = 0.22 + ((h >>> 50) & 0x1FL) / 31.0 * 0.22;

		double angle = Math.atan2(dz, dx);
		double radius = baseRadius * (1.0
				+ lobe * Math.sin(2.0 * angle + phase1)
				+ 0.20 * Math.sin(3.0 * angle + phase2)
				+ 0.11 * Math.sin(5.0 * angle + phase3));

		return distanceSquared < radius * radius;
	}

	@Override
	public double sample(NoisePos pos) {
		return inPit(pos.blockX(), pos.blockZ(), cellSize, pitSize, chance, salt, shape) ? -100.0 : 0.0;
	}

	/**
	 * Same fixed-point trap as ZoneGridDensityFunction.hash: without the leading constant, cell
	 * (0,0) with salt 0 hashes to exactly 0, which passes every chance test and rolls the smallest
	 * pit at offset 0. One shaft in the world would be identical in every world.
	 */
	public static long hash(int cellX, int cellZ, int salt) {
		long h = 0x27D4EB2F165667C5L
				^ cellX * 0x9E3779B97F4A7C15L
				^ cellZ * 0xC2B2AE3D27D4EB4FL
				^ (long) salt * 0x165667B19E3779F9L;
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
