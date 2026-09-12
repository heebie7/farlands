package dev.farlands;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.FeatureConfig;
import net.minecraft.world.gen.feature.util.FeatureContext;

/**
 * Makes the void shafts actually bottomless: clears bedrock and any standing fluid out of a shaft
 * footprint after the terrain is built.
 *
 * A density function cannot do this on its own. The bedrock floor is written by a surface rule
 * (bedrock_floor in vanilla's overworld settings), long after final_density has had its say, so a
 * shaft carved by VoidPitDensityFunction reaches y = -64 and lands on a ragged bedrock lid. And
 * aquifers fill air below the local water table, so a shaft through a wet column comes out as a
 * very deep well instead of a hole. Both are removed here.
 *
 * Т asked for "пустота пустотная" - fall in and you die - first for caverns (v0.8.0) and then for
 * spires as well (v0.9.0: "дыры в пустоту не такого большого размера, но тоже абстрактных форм...
 * чтобы сделать падение более опасным: не только урон от падения, но и полная смерть от пустоты").
 *
 * Only bedrock and fluids are touched, never solid blocks: a tower piece that happens to overlap a
 * shaft keeps its floor instead of being punched out from under itself.
 *
 * Like WaterThinner this hangs on every overworld biome, so the zone test is the first line of
 * generate() and costs one hash outside a zone.
 *
 * ⚠ SHAFTS below must stay identical to the *_PIT_* block in the header of
 * tools/gen_noise_settings.py, which is what the terrain is carved from. Change one, change the
 * other, or this clears bedrock where there is no shaft and leaves it where there is.
 */
public class VoidShaftClear extends Feature<VoidShaftClear.Config> {
	/** One entry per zone type that has bedrock-piercing shafts. */
	public record Shaft(int zoneType, int cellSize, int pitSize, double chance, int salt, int shape) {}

	public static final Shaft[] SHAFTS = {
			// caverns: wide holes, one per 180x180, straight through every tier
			new Shaft(2, 180, 40, 0.92, 7717, VoidPitDensityFunction.SHAPE_ORGANIC),
			// spires: smaller calibre, rarer, same abstract outline
			new Shaft(0, 260, 18, 0.55, 5501, VoidPitDensityFunction.SHAPE_ORGANIC),
	};

	public record Config(int minY, int maxY) implements FeatureConfig {
		public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.INT.fieldOf("min_y").forGetter(Config::minY),
				Codec.INT.fieldOf("max_y").forGetter(Config::maxY)
		).apply(instance, Config::new));
	}

	public VoidShaftClear(Codec<Config> codec) {
		super(codec);
	}

	@Override
	public boolean generate(FeatureContext<Config> context) {
		BlockPos origin = context.getOrigin();
		int zone = ZoneGridDensityFunction.zoneTypeAt(origin.getX() + 8, origin.getZ() + 8);
		if (zone < 0) return false;

		Shaft shaft = null;
		for (Shaft candidate : SHAFTS) {
			if (candidate.zoneType() == zone) {
				shaft = candidate;
				break;
			}
		}
		if (shaft == null) return false;

		StructureWorldAccess world = context.getWorld();
		Config config = context.getConfig();
		BlockState air = Blocks.AIR.getDefaultState();
		BlockPos.Mutable cursor = new BlockPos.Mutable();

		int minX = origin.getX();
		int minZ = origin.getZ();
		boolean touched = false;

		for (int x = minX; x < minX + 16; x++) {
			for (int z = minZ; z < minZ + 16; z++) {
				if (!VoidPitDensityFunction.inPit(x, z, shaft.cellSize(), shaft.pitSize(),
						shaft.chance(), shaft.salt(), shaft.shape())) {
					continue;
				}
				for (int y = config.minY(); y <= config.maxY(); y++) {
					cursor.set(x, y, z);
					BlockState state = world.getBlockState(cursor);
					if (state.isOf(Blocks.BEDROCK) || !state.getFluidState().isEmpty()) {
						world.setBlockState(cursor, air, 2);
						touched = true;
					}
				}
			}
		}
		return touched;
	}
}
