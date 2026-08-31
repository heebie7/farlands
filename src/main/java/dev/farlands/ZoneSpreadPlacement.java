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
 * random_spread, but only inside a Farlands zone of a given type.
 *
 * Structures are normally kept out of the wrong places by their biome list. Our zones have no
 * biome of their own since v0.6.0 - they sit in whatever vanilla biome the terrain lands in, which
 * was Т's call and is not being taken back - so the gate has to live in the placement instead.
 * Same ZoneGridDensityFunction.zoneTypeAt the terrain mask, /farlands locate and the two features
 * already use, so a tower cannot end up somewhere the zone is not.
 *
 * Everything else is delegated to a real RandomSpreadStructurePlacement rather than reimplemented,
 * so the chunk arithmetic cannot drift from vanilla's.
 *
 * Two knobs beyond the vanilla set:
 *  - offset_x / offset_z shift the whole spread lattice by whole chunks. Every tier of a tower
 *    shares one salt so they land on one column; giving tiers different offsets is what makes them
 *    lean out of each other instead of stacking like plates.
 *  - frequency is vanilla's, but it matters here: all tiers share a salt, so the frequency roll
 *    draws the same number for each of them. Handing the upper tiers a lower frequency therefore
 *    truncates a tower from the top, and the surviving tiers are always the bottom ones. That is
 *    how towers get different heights without any of them being left floating.
 */
public class ZoneSpreadPlacement extends StructurePlacement {
	public static final MapCodec<ZoneSpreadPlacement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.INT.fieldOf("spacing").forGetter(p -> p.spacing),
			Codec.INT.fieldOf("separation").forGetter(p -> p.separation),
			Codec.INT.fieldOf("salt").forGetter(p -> p.saltValue),
			Codec.INT.optionalFieldOf("zone_type", 2).forGetter(p -> p.zoneType),
			Codec.FLOAT.optionalFieldOf("frequency", 1.0F).forGetter(p -> p.frequencyValue),
			Codec.INT.optionalFieldOf("offset_x", 0).forGetter(p -> p.offsetX),
			Codec.INT.optionalFieldOf("offset_z", 0).forGetter(p -> p.offsetZ)
	).apply(instance, ZoneSpreadPlacement::new));

	public static final StructurePlacementType<ZoneSpreadPlacement> TYPE = () -> CODEC;

	private final int spacing;
	private final int separation;
	private final int saltValue;
	private final int zoneType;
	private final float frequencyValue;
	private final int offsetX;
	private final int offsetZ;
	private final RandomSpreadStructurePlacement delegate;

	public ZoneSpreadPlacement(int spacing, int separation, int salt, int zoneType,
	                           float frequency, int offsetX, int offsetZ) {
		super(Vec3i.ZERO, StructurePlacement.FrequencyReductionMethod.DEFAULT, frequency, salt, Optional.empty());
		this.spacing = Math.max(1, spacing);
		this.separation = Math.max(0, Math.min(separation, this.spacing - 1));
		this.saltValue = salt;
		this.zoneType = zoneType;
		this.frequencyValue = frequency;
		this.offsetX = offsetX;
		this.offsetZ = offsetZ;
		this.delegate = new RandomSpreadStructurePlacement(this.spacing, this.separation, SpreadType.LINEAR, salt);
	}

	@Override
	protected boolean isStartChunk(StructurePlacementCalculator calculator, int chunkX, int chunkZ) {
		if (ZoneGridDensityFunction.zoneTypeAt(chunkX * 16 + 8, chunkZ * 16 + 8) != zoneType) {
			return false;
		}
		int shiftedX = chunkX - offsetX;
		int shiftedZ = chunkZ - offsetZ;
		ChunkPos start = delegate.getStartChunk(calculator.getStructureSeed(), shiftedX, shiftedZ);
		return start.x == shiftedX && start.z == shiftedZ;
	}

	@Override
	public StructurePlacementType<?> getType() {
		return TYPE;
	}
}
