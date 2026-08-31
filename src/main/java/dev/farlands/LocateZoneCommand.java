package dev.farlands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

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
		);
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
