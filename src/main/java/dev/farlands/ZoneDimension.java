package dev.farlands;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * "Does this dimension have Farlands zones at all?"
 *
 * The zone mask is pure coordinate arithmetic: ZoneGridDensityFunction.zoneTypeAt(x, z) gives the
 * same answer in every dimension. The TERRAIN only breaks where a dimension's noise settings
 * actually route through farlands:zone_grid (test_zones today), but everything hung on the mask
 * from outside the noise router - the towers' placement, water thinning, the bedrock punch-out,
 * /farlands locate - fired in the ordinary overworld too, at the coordinates where test_zones
 * has a zone. That is how trial-chamber towers ended up standing in normal worlds.
 *
 * So the gate asks the dimension's own noise router whether a zone_grid is in it. Nothing is
 * keyed on a dimension id: when zones move into the real overworld (phase 2) this keeps working
 * without being touched.
 *
 * Walking the router rebuilds it once, so the answer is cached per NoiseConfig (one per world).
 */
public final class ZoneDimension {
	private static final Map<NoiseConfig, Boolean> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

	private ZoneDimension() {
	}

	public static boolean hasZones(NoiseConfig noiseConfig) {
		if (noiseConfig == null) {
			return false;
		}
		Boolean cached = CACHE.get(noiseConfig);
		if (cached != null) {
			return cached;
		}
		boolean[] found = {false};
		try {
			noiseConfig.getNoiseRouter().apply(new DensityFunction.DensityFunctionVisitor() {
				@Override
				public DensityFunction apply(DensityFunction densityFunction) {
					if (densityFunction instanceof ZoneGridDensityFunction) {
						found[0] = true;
					}
					return densityFunction;
				}
			});
		} catch (RuntimeException e) {
			// A router we cannot walk is not ours.
			found[0] = false;
		}
		CACHE.put(noiseConfig, found[0]);
		return found[0];
	}

	public static boolean hasZones(ServerWorld world) {
		return world != null && hasZones(world.getChunkManager().getNoiseConfig());
	}
}
