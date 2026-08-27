package dev.farlands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * /farlands locate [spires|classic] — finds the nearest zone by type.
 * Values here must match the knobs in gen_noise_settings.py.
 */
public class LocateZoneCommand {
	private static final int CELL_SIZE = 3000;
	private static final double RARITY = 0.1;
	private static final int SALT = 0;
	private static final int ZONE_TYPES = 2;
	private static final String[] TYPE_NAMES = {"spires", "classic"};

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("farlands")
			.then(CommandManager.literal("locate")
				.executes(ctx -> locateNearest(ctx, -1))
				.then(CommandManager.literal("spires")
					.executes(ctx -> locateNearest(ctx, 0)))
				.then(CommandManager.literal("classic")
					.executes(ctx -> locateNearest(ctx, 1)))
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

					if ((h & 0xFFFFL) / 65536.0 >= RARITY) continue;

					int type = 0;
					if (ZONE_TYPES > 1) {
						type = (int) ((h >>> 48) & 0xFFL) % ZONE_TYPES;
					}
					if (filterType >= 0 && type != filterType) continue;

					int width = CELL_SIZE / 4 + (int) (((h >>> 16) & 0xFFL) * CELL_SIZE / 512L);
					int depth = CELL_SIZE / 4 + (int) (((h >>> 24) & 0xFFL) * CELL_SIZE / 512L);
					int offsetX = (int) (((h >>> 32) & 0xFFL) * (CELL_SIZE - width) / 256L);
					int offsetZ = (int) (((h >>> 40) & 0xFFL) * (CELL_SIZE - depth) / 256L);

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
