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
 * Makes the caverns shafts actually bottomless: clears bedrock and any standing fluid out of the
 * shaft footprint after the terrain is built.
 *
 * A density function cannot do this on its own. The bedrock floor is written by a surface rule
 * (bedrock_floor in vanilla's overworld settings), long after final_density has had its say, so a
 * shaft carved by VoidPitDensityFunction reaches y = -64 and lands on a ragged bedrock lid. And
 * aquifers fill air below the local water table, so a shaft through a wet column comes out as a
 * very deep well instead of a hole. Both are removed here.
 *
 * Т asked for "пустота пустотная" - fall in and you die - so this exists.
 *
 * Only bedrock and fluids are touched, never solid blocks: a tower piece that happens to overlap a
 * shaft keeps its floor instead of being punched out from under itself.
 *
 * Like WaterThinner this hangs on every overworld biome, so the zone test is the first line of
 * generate() and costs one hash outside a zone.
 *
 * ⚠ The shaft geometry constants below are the same numbers as the CAVERNS_PIT_* block in the
 * header of tools/gen_noise_settings.py. Change one, change the other, or this clears bedrock
 * where there is no shaft and leaves it where there is.
 */
public class VoidShaftClear extends Feature<VoidShaftClear.Config> {
	public static final int CAVERNS_PIT_CELL = 544;
	public static final int CAVERNS_PIT_SIZE = 34;
	public static final double CAVERNS_PIT_CHANCE = 0.9;
	public static final int CAVERNS_PIT_SALT = 7717;
	public static final int CAVERNS_TYPE = 2;

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
		if (ZoneGridDensityFunction.zoneTypeAt(origin.getX() + 8, origin.getZ() + 8) != CAVERNS_TYPE) {
			return false;
		}

		StructureWorldAccess world = context.getWorld();
		Config config = context.getConfig();
		BlockState air = Blocks.AIR.getDefaultState();
		BlockPos.Mutable cursor = new BlockPos.Mutable();

		int minX = origin.getX();
		int minZ = origin.getZ();
		boolean touched = false;

		for (int x = minX; x < minX + 16; x++) {
			for (int z = minZ; z < minZ + 16; z++) {
				if (!VoidPitDensityFunction.inPit(x, z, CAVERNS_PIT_CELL, CAVERNS_PIT_SIZE,
						CAVERNS_PIT_CHANCE, CAVERNS_PIT_SALT, true)) {
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
