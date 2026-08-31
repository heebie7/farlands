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
			Codec.INT.optionalFieldOf("salt", 0).forGetter(f -> f.salt),
			Codec.INT.optionalFieldOf("zone_types", 1).forGetter(f -> f.zoneTypes)
	).apply(instance, ZoneGridDensityFunction::new));

	public static final CodecHolder<ZoneGridDensityFunction> CODEC_HOLDER = CodecHolder.of(CODEC);

	/**
	 * The knobs the rest of the mod asks about zones with. These MUST match the header of
	 * tools/gen_noise_settings.py - the JSON the terrain is built from comes from there, this is
	 * what the locate command and the water thinner use to answer "am I standing in a zone".
	 */
	public static final int DEFAULT_CELL_SIZE = 3000;
	public static final double DEFAULT_RARITY = 0.1;
	public static final int DEFAULT_SALT = 0;
	public static final int DEFAULT_ZONE_TYPES = 3;
	public static final String[] TYPE_NAMES = {"spires", "classic", "caverns"};

	// --- cell geometry, shared so nobody re-derives it slightly differently ---

	public static boolean cellHasZone(long h, double rarity) {
		return (h & 0xFFFFL) / 65536.0 < rarity;
	}

	public static int cellZoneType(long h, int zoneTypes) {
		return zoneTypes <= 1 ? 0 : (int) ((h >>> 48) & 0xFFL) % zoneTypes;
	}

	public static int rectWidth(long h, int cellSize) {
		return cellSize / 4 + (int) (((h >>> 16) & 0xFFL) * cellSize / 512L);
	}

	public static int rectDepth(long h, int cellSize) {
		return cellSize / 4 + (int) (((h >>> 24) & 0xFFL) * cellSize / 512L);
	}

	public static int rectOffsetX(long h, int cellSize, int width) {
		return (int) (((h >>> 32) & 0xFFL) * (cellSize - width) / 256L);
	}

	public static int rectOffsetZ(long h, int cellSize, int depth) {
		return (int) (((h >>> 40) & 0xFFL) * (cellSize - depth) / 256L);
	}

	/**
	 * Zone type at a block column, or -1 for "no zone here". Same math the terrain mask runs on,
	 * so it agrees with the generated shape block for block.
	 */
	public static int zoneTypeAt(int x, int z, int cellSize, double rarity, int salt, int zoneTypes) {
		cellSize = Math.max(16, cellSize);
		zoneTypes = Math.max(1, zoneTypes);

		int cellX = Math.floorDiv(x, cellSize);
		int cellZ = Math.floorDiv(z, cellSize);
		long h = hash(cellX, cellZ, salt);
		if (!cellHasZone(h, rarity)) return -1;

		int width = rectWidth(h, cellSize);
		int depth = rectDepth(h, cellSize);
		int offsetX = rectOffsetX(h, cellSize, width);
		int offsetZ = rectOffsetZ(h, cellSize, depth);

		int localX = Math.floorMod(x, cellSize);
		int localZ = Math.floorMod(z, cellSize);
		boolean inside = localX >= offsetX && localX < offsetX + width
				&& localZ >= offsetZ && localZ < offsetZ + depth;
		return inside ? cellZoneType(h, zoneTypes) : -1;
	}

	/** Zone type at a block column using the mod's own knobs. -1 = outside every zone. */
	public static int zoneTypeAt(int x, int z) {
		return zoneTypeAt(x, z, DEFAULT_CELL_SIZE, DEFAULT_RARITY, DEFAULT_SALT, DEFAULT_ZONE_TYPES);
	}

	private final int cellSize;
	private final double rarity;
	private final int salt;
	private final int zoneTypes;

	public ZoneGridDensityFunction(int cellSize, double rarity, int salt, int zoneTypes) {
		this.cellSize = Math.max(16, cellSize);
		this.rarity = rarity;
		this.salt = salt;
		this.zoneTypes = Math.max(1, zoneTypes);
	}

	@Override
	public double sample(NoisePos pos) {
		int type = zoneTypeAt(pos.blockX(), pos.blockZ(), cellSize, rarity, salt, zoneTypes);
		return type < 0 ? 0.0 : type + 1.0;
	}

	public static long hash(int cellX, int cellZ, int salt) {
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

	@Override
	public double minValue() {
		return 0.0;
	}

	@Override
	public double maxValue() {
		return zoneTypes;
	}

	@Override
	public CodecHolder<? extends DensityFunction> getCodecHolder() {
		return CODEC_HOLDER;
	}
}
