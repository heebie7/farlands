package dev.farlands;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.PlacedFeature;

public class Farlands implements ModInitializer {
	public static final String MOD_ID = "farlands";

	public static final WaterThinner WATER_THINNER = new WaterThinner(WaterThinner.Config.CODEC);
	public static final VoidShaftClear VOID_SHAFT_CLEAR = new VoidShaftClear(VoidShaftClear.Config.CODEC);

	public static final RegistryKey<PlacedFeature> WATER_THINNER_PLACED = RegistryKey.of(
			RegistryKeys.PLACED_FEATURE,
			Identifier.of(MOD_ID, "water_thinner")
	);

	public static final RegistryKey<PlacedFeature> VOID_SHAFT_CLEAR_PLACED = RegistryKey.of(
			RegistryKeys.PLACED_FEATURE,
			Identifier.of(MOD_ID, "void_shaft_clear")
	);

	@Override
	public void onInitialize() {
		Registry.register(
				Registries.DENSITY_FUNCTION_TYPE,
				Identifier.of(MOD_ID, "zone_grid"),
				ZoneGridDensityFunction.CODEC
		);
		Registry.register(
				Registries.DENSITY_FUNCTION_TYPE,
				Identifier.of(MOD_ID, "tunnel_noise"),
				TunnelNoiseDensityFunction.CODEC
		);
		Registry.register(
				Registries.DENSITY_FUNCTION_TYPE,
				Identifier.of(MOD_ID, "void_pit"),
				VoidPitDensityFunction.CODEC
		);
		Registry.register(
				Registries.DENSITY_FUNCTION_TYPE,
				Identifier.of(MOD_ID, "cavern_layers"),
				CavernLayersDensityFunction.CODEC
		);
		// Gates the trial-chamber towers to caverns zones. Structures are normally kept in place by
		// their biome list, and our zones have no biome of their own, so the gate lives here.
		Registry.register(
				Registries.STRUCTURE_PLACEMENT,
				Identifier.of(MOD_ID, "zone_spread"),
				ZoneSpreadPlacement.TYPE
		);
		Registry.register(
				Registries.FEATURE,
				Identifier.of(MOD_ID, "water_thinner"),
				WATER_THINNER
		);
		Registry.register(
				Registries.FEATURE,
				Identifier.of(MOD_ID, "void_shaft_clear"),
				VOID_SHAFT_CLEAR
		);

		// Zones get whatever vanilla biome the terrain lands in (v0.6.0 dropped our own biomes),
		// so water thinning has to hang on every overworld biome and decide for itself whether the
		// chunk is inside a zone. Hanging it on farlands:spires/classic did nothing at all: those
		// biomes are never placed any more, and the feature silently stopped being called.
		BiomeModifications.addFeature(
				BiomeSelectors.foundInOverworld(),
				GenerationStep.Feature.TOP_LAYER_MODIFICATION,
				WATER_THINNER_PLACED
		);
		// Same deal, and same cheap zone check in its first line: the bedrock lid and the aquifer
		// water inside a caverns shaft are written after final_density has had its say, so they get
		// removed here instead. Т: "пустота пустотная".
		BiomeModifications.addFeature(
				BiomeSelectors.foundInOverworld(),
				GenerationStep.Feature.TOP_LAYER_MODIFICATION,
				VOID_SHAFT_CLEAR_PLACED
		);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LocateZoneCommand.register(dispatcher);
		});
	}
}
