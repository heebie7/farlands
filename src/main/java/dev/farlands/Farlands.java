package dev.farlands;

import net.fabricmc.api.ModInitializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class Farlands implements ModInitializer {
	public static final String MOD_ID = "farlands";

	@Override
	public void onInitialize() {
		Registry.register(
				Registries.DENSITY_FUNCTION_TYPE,
				Identifier.of(MOD_ID, "zone_grid"),
				ZoneGridDensityFunction.CODEC_HOLDER
		);
	}
}
