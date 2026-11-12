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
 * Bodies that are bigger than max_body_size, or that reach the edge of the chunk or the bottom of
 * the range, cannot be rolled here: the rest of them lives in a chunk this pass cannot see, and
 * half a pool would just refill from the other half. Until v0.11.0 they were simply left alone.
 * Now they get a smaller chance, decided by a hash every chunk holding a piece agrees on - see
 * the comment in generate().
 *
 * The feature is attached to every overworld biome (see Farlands#onInitialize), because the zones
 * sit in whatever vanilla biome the terrain lands in - they have no biome of their own since
 * v0.6.0. So the "are we inside a zone" test lives HERE instead: same hash the terrain mask runs
 * on, checked once per chunk before anything is scanned. Outside a zone this returns immediately
 * and costs one hash, which is what makes it safe to hang on the whole overworld.
 */
public class WaterThinner extends Feature<WaterThinner.Config> {
	public record Config(float chance, int minY, int maxY, int maxBodySize,
	                     float bigChance, int bigCell, float seaChance) implements FeatureConfig {
		public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.FLOAT.fieldOf("chance").forGetter(Config::chance),
				Codec.INT.fieldOf("min_y").forGetter(Config::minY),
				Codec.INT.fieldOf("max_y").forGetter(Config::maxY),
				Codec.INT.fieldOf("max_body_size").forGetter(Config::maxBodySize),
				Codec.FLOAT.optionalFieldOf("big_chance", 0.0F).forGetter(Config::bigChance),
				Codec.INT.optionalFieldOf("big_cell", 128).forGetter(Config::bigCell),
				Codec.FLOAT.optionalFieldOf("sea_chance", 0.0F).forGetter(Config::seaChance)
		).apply(instance, Config::new));
	}

	private static final int BIG_SALT = 90417;
	private static final int SEA_SALT = 31873;

	/** 0..1 out of the shared cell hash, so every chunk that asks about the same thing agrees. */
	private static float roll(int a, int b, int salt) {
		return ((ZoneGridDensityFunction.hash(a, b, salt) >>> 24) & 0xFFFFL) / 65536.0F;
	}

	public WaterThinner(Codec<Config> codec) {
		super(codec);
	}

	private static boolean isWater(BlockState state) {
		return state.isOf(Blocks.WATER)
				|| state.isOf(Blocks.BUBBLE_COLUMN)
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
		// ...and only in a dimension that has zones in its terrain at all. The mask is coordinate
		// arithmetic and answers the same everywhere, including the ordinary overworld.
		if (!ZoneDimension.hasZones(world.toServerWorld())) {
			return false;
		}
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

		// Big bodies. Т 2026-11-12: "чем больше водоём, тем меньше шанс, но чтобы это не прям-таки
		// очень сильно влияло... чтобы шанс оставался даже на невероятных подводных морях. Море
		// 100 тысяч блоков, может быть больше, тоже имело, хоть и небольшой, но шанс удалиться."
		//
		// A feature sees one chunk, so a lake that crosses a chunk border cannot be measured or
		// rolled as a whole. What CAN be done is to make every chunk that holds a piece of it
		// reach the same verdict without talking to each other - by rolling on something they
		// all share:
		//
		//   sea level water (oceans, rivers, everything joined to them) - one roll for the whole
		//     ZONE, `sea_chance`. The biggest thing there is, the smallest chance; when it hits,
		//     the entire sea in that zone is gone and stops dead at the zone wall.
		//   any other big body (cave lakes, mountain lakes) - one roll per `big_cell` square,
		//     `big_chance`. A lake smaller than the square goes whole; a larger
		//     one can lose a square-edged piece, which here counts as the look, not a defect.
		//   small pools - unchanged, `chance` each.
		int centreX = origin.getX() + 8;
		int centreZ = origin.getZ() + 8;
		int bigCell = Math.max(16, config.bigCell());
		boolean bigGone = roll(Math.floorDiv(centreX, bigCell), Math.floorDiv(centreZ, bigCell), BIG_SALT)
				< config.bigChance();
		int seaTop = world.getSeaLevel() - 1;
		boolean seaGone = roll(
				Math.floorDiv(centreX, ZoneGridDensityFunction.DEFAULT_CELL_SIZE),
				Math.floorDiv(centreZ, ZoneGridDensityFunction.DEFAULT_CELL_SIZE),
				SEA_SALT) < config.seaChance();

		BlockState air = Blocks.AIR.getDefaultState();
		Set<BlockPos> visited = new HashSet<>();

		for (BlockPos start : water) {
			if (!visited.add(start)) {
				continue;
			}

			List<BlockPos> body = new ArrayList<>();
			Deque<BlockPos> queue = new ArrayDeque<>();
			queue.add(start);
			boolean big = false;
			int top = Integer.MIN_VALUE;

			while (!queue.isEmpty()) {
				BlockPos current = queue.poll();
				body.add(current);

				// A body that runs off the edge of what this pass can see, or is simply large, is
				// a BIG one: its fate cannot be rolled here, because the next chunk would roll
				// again and disagree. Note we keep walking it anyway, so the rest of it is not
				// mistaken for a separate small pool later.
				if (body.size() > config.maxBodySize()
						|| current.getX() <= minX || current.getX() >= maxX
						|| current.getZ() <= minZ || current.getZ() >= maxZ
						|| current.getY() <= config.minY()) {
					big = true;
				}
				top = Math.max(top, current.getY());

				for (Direction direction : Direction.values()) {
					BlockPos next = current.offset(direction);
					if (water.contains(next) && visited.add(next)) {
						queue.add(next);
					}
				}
			}

			boolean remove;
			if (!big) {
				remove = random.nextFloat() < config.chance();
			} else if (top >= seaTop) {
				remove = seaGone;
			} else {
				// One verdict per square for everything big in it, whatever level it stands
				// at: a flooded cave shows a different top in every chunk, and keying on the
				// level made neighbouring chunks disagree about the same lake.
				remove = bigGone;
			}
			if (!remove) {
				continue;
			}
			for (BlockPos pos : body) {
				world.setBlockState(pos, air, 2);
			}
		}
		return true;
	}
}
