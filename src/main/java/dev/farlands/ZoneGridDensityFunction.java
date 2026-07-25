package dev.farlands;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * Returns 1.0 inside a corrupted zone and 0.0 outside, with dead-straight axis-aligned edges.
 *
 * Vanilla density functions cannot see raw X/Z at all - every mask you can build out of them
 * follows noise contours, so it comes out as blobs. A zone that stands as a flat wall, like the
 * real Farlands bug did, needs the block coordinates, which is why this exists as Java.
 *
 * The world is cut into cells of cell_size. A hash decides whether a cell is corrupted, and a
 * second hash carves a rectangle of varying size and position inside it, so the zones do not read
 * as a grid. No world seed involved: the same rectangles land in the same places every time,
 * which is what you want while tuning.
 */
public final class ZoneGridDensityFunction implements DensityFunction.Base {
	public static final MapCodec<ZoneGridDensityFunction> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.INT.fieldOf("cell_size").forGetter(f -> f.cellSize),
			Codec.DOUBLE.fieldOf("rarity").forGetter(f -> f.rarity),
			Codec.INT.optionalFieldOf("salt", 0).forGetter(f -> f.salt)
	).apply(instance, ZoneGridDensityFunction::new));

	public static final CodecHolder<ZoneGridDensityFunction> CODEC_HOLDER = CodecHolder.of(CODEC);

	private final int cellSize;
	private final double rarity;
	private final int salt;

	public ZoneGridDensityFunction(int cellSize, double rarity, int salt) {
		this.cellSize = Math.max(16, cellSize);
		this.rarity = rarity;
		this.salt = salt;
	}

	@Override
	public double sample(NoisePos pos) {
		int x = pos.comp_371();
		int z = pos.comp_373();

		int cellX = Math.floorDiv(x, cellSize);
		int cellZ = Math.floorDiv(z, cellSize);
		long h = hash(cellX, cellZ, salt);

		if ((h & 0xFFFFL) / 65536.0 >= rarity) {
			return 0.0;
		}

		int localX = Math.floorMod(x, cellSize);
		int localZ = Math.floorMod(z, cellSize);

		// Rectangle spans a quarter to three quarters of the cell, placed anywhere inside it.
		int width = cellSize / 4 + (int) (((h >>> 16) & 0xFFL) * cellSize / 512L);
		int depth = cellSize / 4 + (int) (((h >>> 24) & 0xFFL) * cellSize / 512L);
		int offsetX = (int) (((h >>> 32) & 0xFFL) * (cellSize - width) / 256L);
		int offsetZ = (int) (((h >>> 40) & 0xFFL) * (cellSize - depth) / 256L);

		boolean inside = localX >= offsetX && localX < offsetX + width
				&& localZ >= offsetZ && localZ < offsetZ + depth;
		return inside ? 1.0 : 0.0;
	}

	private static long hash(int cellX, int cellZ, int salt) {
		long h = cellX * 0x9E3779B97F4A7C15L ^ cellZ * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
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

	/** minValue */
	@Override
	public double comp_377() {
		return 0.0;
	}

	/** maxValue */
	@Override
	public double comp_378() {
		return 1.0;
	}

	@Override
	public CodecHolder<? extends DensityFunction> getCodecHolder() {
		return CODEC_HOLDER;
	}
}
