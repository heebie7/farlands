package dev.farlands;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.FeatureConfig;
import net.minecraft.world.gen.feature.util.FeatureContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes some of the small standing water in a corrupted zone, after everything else generated.
 *
 * Terrain this tall leaks water from every exposed aquifer and spring, which pours down the
 * needles and pools on top of them - bad to look at and worse to fight on.
 *
 * Whole bodies, one roll each. Deleting a percentage of individual water BLOCKS does nothing:
 * a pool is made almost entirely of source blocks, and any gap touching two sources on the same
 * level turns back into a source - the infinite-water-bucket rule. Punch holes in a pool and it
 * refills itself. So a connected body is found by flood fill and either goes completely or stays
 * completely. Kelp and seagrass inside it count as part of the body and go with it, so nothing is
 * left standing in mid-air.
 *
 * Bodies bigger than max_body_size are never touched - a lake with a bite out of it looks worse
 * than the lake did. Neither are bodies that reach the edge of the chunk or the bottom of the
 * range, because the rest of them lives in a chunk this pass cannot see, and half a pool would
 * just refill from the other half.
 *
 * The feature is attached to every overworld biome (see Farlands#onInitialize), because the zones
 * sit in whatever vanilla biome the terrain lands in - they have no biome of their own since
 * v0.6.0. So the "are we inside a zone" test lives HERE instead: same hash the terrain mask runs
 * on, checked once per chunk before anything is scanned. Outside a zone this returns immediately
 * and costs one hash, which is what makes it safe to hang on the whole overworld.
 */
public class WaterThinner extends Feature<WaterThinner.Config> {
	public record Config(float chance, int minY, int maxY, int maxBodySize) implements FeatureConfig {
		public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.FLOAT.fieldOf("chance").forGetter(Config::chance),
				Codec.INT.fieldOf("min_y").forGetter(Config::minY),
				Codec.INT.fieldOf("max_y").forGetter(Config::maxY),
				Codec.INT.fieldOf("max_body_size").forGetter(Config::maxBodySize)
		).apply(instance, Config::new));
	}

	public WaterThinner(Codec<Config> codec) {
		super(codec);
	}

	private static boolean isWater(BlockState state) {
		return state.isOf(Blocks.WATER)
				|| state.isOf(Blocks.KELP)
				|| state.isOf(Blocks.KELP_PLANT)
				|| state.isOf(Blocks.SEAGRASS)
				|| state.isOf(Blocks.TALL_SEAGRASS);
	}

	@Override
	public boolean generate(FeatureContext<Config> context) {
		BlockPos origin = context.getOrigin();

		// Zone gate. Chunk centre decides, so the worst case is a 8-block fuzz at a zone wall that
		// is a thousand blocks across. Everything else in the overworld leaves after this line.
		if (ZoneGridDensityFunction.zoneTypeAt(origin.getX() + 8, origin.getZ() + 8) < 0) {
			return false;
		}

		StructureWorldAccess world = context.getWorld();
		Random random = context.getRandom();
		Config config = context.getConfig();

		int minX = origin.getX();
		int minZ = origin.getZ();
		int maxX = minX + 15;
		int maxZ = minZ + 15;

		Set<BlockPos> water = new HashSet<>();
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = config.minY(); y <= config.maxY(); y++) {
					cursor.set(x, y, z);
					if (isWater(world.getBlockState(cursor))) {
						water.add(cursor.toImmutable());
					}
				}
			}
		}
		if (water.isEmpty()) {
			return true;
		}

		BlockState air = Blocks.AIR.getDefaultState();
		Set<BlockPos> visited = new HashSet<>();

		for (BlockPos start : water) {
			if (!visited.add(start)) {
				continue;
			}

			List<BlockPos> body = new ArrayList<>();
			Deque<BlockPos> queue = new ArrayDeque<>();
			queue.add(start);
			boolean keep = false;

			while (!queue.isEmpty()) {
				BlockPos current = queue.poll();
				body.add(current);

				// Anything that runs off the edge of what we can see, or is simply large, is left
				// alone. Note we keep walking the body anyway, so the rest of it is not mistaken
				// for a separate small pool later.
				if (body.size() > config.maxBodySize()
						|| current.getX() <= minX || current.getX() >= maxX
						|| current.getZ() <= minZ || current.getZ() >= maxZ
						|| current.getY() <= config.minY()) {
					keep = true;
				}

				for (Direction direction : Direction.values()) {
					BlockPos next = current.offset(direction);
					if (water.contains(next) && visited.add(next)) {
						queue.add(next);
					}
				}
			}

			if (keep || random.nextFloat() >= config.chance()) {
				continue;
			}
			for (BlockPos pos : body) {
				world.setBlockState(pos, air, 2);
			}
		}
		return true;
	}
}
