package dev.farlands;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.FeatureConfig;
import net.minecraft.world.gen.feature.util.FeatureContext;

/**
 * Strips most of the water out of a corrupted zone, after everything else has generated.
 *
 * Terrain this tall leaks water from every exposed aquifer and spring, which pours down the
 * needles and pools on top of them - bad to look at and worse to fight on.
 *
 * The roll happens per COLUMN, not per block. A waterfall is a vertical stack fed from one place,
 * so clearing a whole column kills the entire fall in one go; rolling per block would just punch
 * holes that the surviving water flows straight back into. Anything at or below min_y is left
 * alone, so oceans and rivers at their normal level stay untouched.
 */
public class WaterThinner extends Feature<WaterThinner.Config> {
	public record Config(float chance, int minY, int maxY) implements FeatureConfig {
		public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.FLOAT.fieldOf("chance").forGetter(Config::chance),
				Codec.INT.fieldOf("min_y").forGetter(Config::minY),
				Codec.INT.fieldOf("max_y").forGetter(Config::maxY)
		).apply(instance, Config::new));
	}

	public WaterThinner(Codec<Config> codec) {
		super(codec);
	}

	@Override
	public boolean generate(FeatureContext<Config> context) {
		StructureWorldAccess world = context.getWorld();
		BlockPos origin = context.getOrigin();
		Random random = context.getRandom();
		Config config = context.getConfig();

		BlockState air = Blocks.AIR.getDefaultState();
		BlockPos.Mutable pos = new BlockPos.Mutable();

		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				if (random.nextFloat() >= config.chance()) {
					continue;
				}
				int x = origin.getX() + dx;
				int z = origin.getZ() + dz;
				for (int y = config.minY(); y <= config.maxY(); y++) {
					pos.set(x, y, z);
					if (world.getBlockState(pos).isOf(Blocks.WATER)) {
						world.setBlockState(pos, air, 2);
					}
				}
			}
		}
		return true;
	}
}
