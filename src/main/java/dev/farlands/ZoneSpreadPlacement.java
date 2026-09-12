package dev.farlands;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.SpreadType;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.chunk.placement.StructurePlacementType;

import java.util.Optional;

/**
 * random_spread, but only inside a Farlands zone of a given type, and only up to the height this
 * particular tower is allowed to reach.
 *
 * Structures are normally kept out of the wrong places by their biome list. Our zones have no biome
 * of their own since v0.6.0 - they sit in whatever vanilla biome the terrain lands in, which was
 * Т's call and is not being taken back - so the gate has to live in the placement instead. Same
 * ZoneGridDensityFunction.zoneTypeAt the terrain mask, /farlands locate and the two features
 * already use, so a tower cannot end up somewhere the zone is not.
 *
 * The spread arithmetic is delegated to a real RandomSpreadStructurePlacement rather than
 * reimplemented, so it cannot drift from vanilla's.
 *
 * Knobs beyond the vanilla set:
 *
 *  - offset_x / offset_z shift the whole spread lattice by whole chunks. Every tier of a tower
 *    shares one salt so they land on one column; giving tiers different offsets is what makes them
 *    lean out of each other instead of stacking like plates.
 *
 *  - tier / min_tiers / max_tiers decide how tall this tower is. v0.8.0 did that with vanilla's
 *    `frequency`, on the theory that all tiers share a salt and therefore draw the same random
 *    number, so a low frequency up top would truncate the tower at a clean line. That was wrong:
 *    vanilla seeds the frequency roll from the CHUNK coordinates, and the tiers sit on chunks that
 *    differ by their offsets, so every tier rolled independently and a tower could keep tier 14
 *    while dropping tier 11. Now the height is one number hashed from the lattice region - the
 *    same region for every tier, exactly, because a tier's start chunk is always its region's
 *    start chunk plus its own offset - so a tower is always a contiguous run from the bottom.
 */
public class ZoneSpreadPlacement extends StructurePlacement {
	/**
	 * ⚠ Must match the SPACING / SEPARATION / SALT in tools/gen_caverns_towers.py. Kept here so
	 * /farlands towers predicts the same columns the generator builds.
	 */
	public static final int TOWER_SPACING = 12;
	public static final int TOWER_SEPARATION = 3;
	public static final int TOWER_SALT = 8412577;
	public static final int TOWER_HEIGHT_SALT = 55311;
	public static final int CAVERNS_TYPE = 2;
	/** Geometry of the tier stack, mirrored from the same script. Only /farlands towers reads it. */
	public static final int TOWER_BASE_Y = -50;
	public static final int TOWER_TIER_STEP = 10;
	public static final int TOWER_MIN_TIERS = 20;
	public static final int TOWER_MAX_TIERS = 30;

	public static final MapCodec<ZoneSpreadPlacement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.INT.fieldOf("spacing").forGetter(p -> p.spacing),
			Codec.INT.fieldOf("separation").forGetter(p -> p.separation),
			Codec.INT.fieldOf("salt").forGetter(p -> p.saltValue),
			Codec.INT.optionalFieldOf("zone_type", CAVERNS_TYPE).forGetter(p -> p.zoneType),
			Codec.INT.optionalFieldOf("tier", 0).forGetter(p -> p.tier),
			Codec.INT.optionalFieldOf("min_tiers", 1).forGetter(p -> p.minTiers),
			Codec.INT.optionalFieldOf("max_tiers", 1).forGetter(p -> p.maxTiers),
			Codec.INT.optionalFieldOf("offset_x", 0).forGetter(p -> p.offsetX),
			Codec.INT.optionalFieldOf("offset_z", 0).forGetter(p -> p.offsetZ)
	).apply(instance, ZoneSpreadPlacement::new));

	public static final StructurePlacementType<ZoneSpreadPlacement> TYPE = () -> CODEC;

	private final int spacing;
	private final int separation;
	private final int saltValue;
	private final int zoneType;
	private final int tier;
	private final int minTiers;
	private final int maxTiers;
	private final int offsetX;
	private final int offsetZ;
	private final RandomSpreadStructurePlacement delegate;

	public ZoneSpreadPlacement(int spacing, int separation, int salt, int zoneType, int tier,
	                           int minTiers, int maxTiers, int offsetX, int offsetZ) {
		super(Vec3i.ZERO, StructurePlacement.FrequencyReductionMethod.DEFAULT, 1.0F, salt, Optional.empty());
		this.spacing = Math.max(1, spacing);
		this.separation = Math.max(0, Math.min(separation, this.spacing - 1));
		this.saltValue = salt;
		this.zoneType = zoneType;
		this.tier = tier;
		this.minTiers = Math.max(1, minTiers);
		this.maxTiers = Math.max(this.minTiers, maxTiers);
		this.offsetX = offsetX;
		this.offsetZ = offsetZ;
		this.delegate = new RandomSpreadStructurePlacement(this.spacing, this.separation, SpreadType.LINEAR, salt);
	}

	/** How many tiers the tower rooted in this lattice region gets. Same answer for every tier. */
	public static int towerTiers(int regionX, int regionZ, int minTiers, int maxTiers) {
		if (maxTiers <= minTiers) return minTiers;
		long h = ZoneGridDensityFunction.hash(regionX, regionZ, TOWER_HEIGHT_SALT);
		return minTiers + (int) (((h >>> 20) & 0xFFFFL) % (long) (maxTiers - minTiers + 1));
	}

	@Override
	protected boolean isStartChunk(StructurePlacementCalculator calculator, int chunkX, int chunkZ) {
		if (ZoneGridDensityFunction.zoneTypeAt(chunkX * 16 + 8, chunkZ * 16 + 8) != zoneType) {
			return false;
		}
		int shiftedX = chunkX - offsetX;
		int shiftedZ = chunkZ - offsetZ;
		ChunkPos start = delegate.getStartChunk(calculator.getStructureSeed(), shiftedX, shiftedZ);
		if (start.x != shiftedX || start.z != shiftedZ) {
			return false;
		}
		if (tier < minTiers) {
			return true;
		}
		int regionX = Math.floorDiv(shiftedX, spacing);
		int regionZ = Math.floorDiv(shiftedZ, spacing);
		return tier < towerTiers(regionX, regionZ, minTiers, maxTiers);
	}

	@Override
	public StructurePlacementType<?> getType() {
		return TYPE;
	}
}
