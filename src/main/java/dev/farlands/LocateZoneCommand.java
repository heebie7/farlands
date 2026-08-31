package dev.farlands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.SpreadType;

/**
 * /farlands locate [spires|classic|caverns] — finds the nearest zone by type.
 * Knobs come from ZoneGridDensityFunction so this cannot drift away from the terrain mask.
 */
public class LocateZoneCommand {
	private static final int CELL_SIZE = ZoneGridDensityFunction.DEFAULT_CELL_SIZE;
	private static final double RARITY = ZoneGridDensityFunction.DEFAULT_RARITY;
	private static final int SALT = ZoneGridDensityFunction.DEFAULT_SALT;
	private static final int ZONE_TYPES = ZoneGridDensityFunction.DEFAULT_ZONE_TYPES;
	private static final String[] TYPE_NAMES = ZoneGridDensityFunction.TYPE_NAMES;

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("farlands")
			.then(CommandManager.literal("locate")
				.executes(ctx -> locateNearest(ctx, -1))
				.then(CommandManager.literal("spires")
					.executes(ctx -> locateNearest(ctx, 0)))
				.then(CommandManager.literal("classic")
					.executes(ctx -> locateNearest(ctx, 1)))
				.then(CommandManager.literal("caverns")
					.executes(ctx -> locateNearest(ctx, 2)))
			)
			.then(CommandManager.literal("towers")
				.executes(LocateZoneCommand::locateTower))
		);
	}

	/**
	 * /farlands towers — where the caverns trial-chamber towers are PREDICTED to stand, computed
	 * from the same placement arithmetic the generator uses.
	 *
	 * This exists because the towers cannot be debugged from here: there is no Java on this machine
	 * and no way to run the game. If Т stands on a coordinate this prints and there is no tower,
	 * the fault is in the structure (jigsaw, pools, biome check); if the tower is there, placement
	 * was never the problem and the old ones were simply too rare and too far below the roof.
	 */
	private static int locateTower(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource source = ctx.getSource();
		Vec3d v = source.getPosition();
		int playerX = (int) Math.floor(v.x);
		int playerZ = (int) Math.floor(v.z);
		long seed = source.getWorld().getSeed();

		RandomSpreadStructurePlacement spread = new RandomSpreadStructurePlacement(
				ZoneSpreadPlacement.TOWER_SPACING, ZoneSpreadPlacement.TOWER_SEPARATION,
				SpreadType.LINEAR, ZoneSpreadPlacement.TOWER_SALT);

		int spacing = ZoneSpreadPlacement.TOWER_SPACING;
		int playerRegionX = Math.floorDiv(Math.floorDiv(playerX, 16), spacing);
		int playerRegionZ = Math.floorDiv(Math.floorDiv(playerZ, 16), spacing);

		int bestX = 0, bestZ = 0, bestTiers = 0;
		double bestDist = Double.MAX_VALUE;

		for (int dx = -24; dx <= 24; dx++) {
			for (int dz = -24; dz <= 24; dz++) {
				int regionX = playerRegionX + dx;
				int regionZ = playerRegionZ + dz;
				ChunkPos start = spread.getStartChunk(seed, regionX * spacing, regionZ * spacing);
				int blockX = start.x * 16 + 8;
				int blockZ = start.z * 16 + 8;
				if (ZoneGridDensityFunction.zoneTypeAt(blockX, blockZ) != ZoneSpreadPlacement.CAVERNS_TYPE) {
					continue;
				}
				double dist = Math.sqrt((double) (playerX - blockX) * (playerX - blockX)
						+ (double) (playerZ - blockZ) * (playerZ - blockZ));
				if (dist < bestDist) {
					bestDist = dist;
					bestX = blockX;
					bestZ = blockZ;
					bestTiers = ZoneSpreadPlacement.towerTiers(regionX, regionZ,
							ZoneSpreadPlacement.TOWER_MIN_TIERS, ZoneSpreadPlacement.TOWER_MAX_TIERS);
				}
			}
		}

		if (bestDist == Double.MAX_VALUE) {
			source.sendFeedback(() -> Text.literal(
				"[Farlands] No predicted tower within ~7000 blocks. Try /farlands locate caverns first."), false);
			return 0;
		}

		final int fx = bestX, fz = bestZ, tiers = bestTiers;
		final int topY = ZoneSpreadPlacement.TOWER_BASE_Y + (tiers - 1) * ZoneSpreadPlacement.TOWER_TIER_STEP;
		final int dist = (int) bestDist;
		source.sendFeedback(() -> Text.literal(
			String.format("[Farlands] Nearest predicted tower at [%d, %d] (%d blocks), %d tiers, top ~y%d — /tp @s %d %d %d",
				fx, fz, dist, tiers, topY, fx, topY + 20, fz)
		), false);
		return 1;
	}

	private static int locateNearest(CommandContext<ServerCommandSource> ctx, int filterType) {
		ServerCommandSource source = ctx.getSource();
		Vec3d v = source.getPosition();
		int playerX = (int) Math.floor(v.x);
		int playerZ = (int) Math.floor(v.z);
		int playerCellX = Math.floorDiv(playerX, CELL_SIZE);
		int playerCellZ = Math.floorDiv(playerZ, CELL_SIZE);

		int bestCenterX = 0, bestCenterZ = 0;
		String bestType = "";
		double bestDist = Double.MAX_VALUE;

		for (int radius = 0; radius <= 50; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;

					int cellX = playerCellX + dx;
					int cellZ = playerCellZ + dz;
					long h = ZoneGridDensityFunction.hash(cellX, cellZ, SALT);

					if (!ZoneGridDensityFunction.cellHasZone(h, RARITY)) continue;

					int type = ZoneGridDensityFunction.cellZoneType(h, ZONE_TYPES);
					if (filterType >= 0 && type != filterType) continue;

					int width = ZoneGridDensityFunction.rectWidth(h, CELL_SIZE);
					int depth = ZoneGridDensityFunction.rectDepth(h, CELL_SIZE);
					int offsetX = ZoneGridDensityFunction.rectOffsetX(h, CELL_SIZE, width);
					int offsetZ = ZoneGridDensityFunction.rectOffsetZ(h, CELL_SIZE, depth);

					int centerX = cellX * CELL_SIZE + offsetX + width / 2;
					int centerZ = cellZ * CELL_SIZE + offsetZ + depth / 2;

					double dist = Math.sqrt(
						(double)(playerX - centerX) * (playerX - centerX)
						+ (double)(playerZ - centerZ) * (playerZ - centerZ)
					);
					if (dist < bestDist) {
						bestDist = dist;
						bestCenterX = centerX;
						bestCenterZ = centerZ;
						bestType = TYPE_NAMES[type];
					}
				}
			}
			if (bestDist < Double.MAX_VALUE) break;
		}

		if (bestDist == Double.MAX_VALUE) {
			source.sendFeedback(() -> Text.literal("No zones found within search radius"), false);
			return 0;
		}

		final String typeName = bestType;
		final int fx = bestCenterX, fz = bestCenterZ;
		final int dist = (int) bestDist;
		source.sendFeedback(() -> Text.literal(
			String.format("[Farlands] Nearest %s zone at [%d, %d] (%d blocks) — /tp @s %d 200 %d",
				typeName, fx, fz, dist, fx, fz)
		), false);
		return 1;
	}
}
