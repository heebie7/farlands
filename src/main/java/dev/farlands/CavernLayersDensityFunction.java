package dev.farlands;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * Stacked floor slabs: a function of Y only, positive inside a slab and negative in the open space
 * between slabs. This is what turns a caverns zone into tier after tier of huge open cave, one on
 * top of the other, all the way up.
 *
 * Why Java and not minecraft:noise with xz_scale = 0.0 (which also collapses to a function of Y):
 * a perlin sampler's real amplitude depends on its octave list in a way you cannot read off the
 * json, so the thickness of the floors would be a guess, and thin floors either vanish entirely or
 * turn into a solid block depending on how the amplitude lands. Here period and thickness are
 * literal block counts and stay what you typed.
 *
 * Shape, per period:
 *   +1.0 at the centre of a slab
 *    0.0 at its top and bottom face
 *   negative, falling linearly, through the open cave until the next slab
 * The slope is normalised by half the thickness, so adding a noise of amplitude A to the result
 * moves a floor surface by about A * thickness/2 blocks. That is the knob that stops the floors
 * from being dead flat.
 *
 * Sampling note: vanilla's overworld noise grid is 8 blocks tall (size_vertical 2), so anything
 * thinner than ~12 blocks aliases badly - some slabs would disappear, others double. Keep
 * thickness comfortably above that; the leftover jitter reads as broken terrain, which is the
 * point of this mod.
 */
public final class CavernLayersDensityFunction implements DensityFunction.Base {
	public static final MapCodec<CavernLayersDensityFunction> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.DOUBLE.fieldOf("period").forGetter(f -> f.period),
			Codec.DOUBLE.fieldOf("thickness").forGetter(f -> f.thickness),
			Codec.DOUBLE.optionalFieldOf("base_y", 0.0).forGetter(f -> f.baseY)
	).apply(instance, CavernLayersDensityFunction::new));

	public static final CodecHolder<CavernLayersDensityFunction> CODEC_HOLDER = CodecHolder.of(CODEC);

	private final double period;
	private final double thickness;
	private final double baseY;
	private final double halfThickness;
	private final double minimum;

	public CavernLayersDensityFunction(double period, double thickness, double baseY) {
		this.period = Math.max(8.0, period);
		this.thickness = Math.max(1.0, Math.min(thickness, this.period));
		this.baseY = baseY;
		this.halfThickness = this.thickness / 2.0;
		this.minimum = -(this.period / 2.0 - this.halfThickness) / this.halfThickness;
	}

	@Override
	public double sample(NoisePos pos) {
		double y = pos.blockY() - baseY;
		double phase = y - Math.floor(y / period) * period;
		double distanceToSlabCentre = Math.min(phase, period - phase);
		return (halfThickness - distanceToSlabCentre) / halfThickness;
	}

	@Override
	public DensityFunction apply(DensityFunctionVisitor visitor) {
		return visitor.apply(this);
	}

	@Override
	public double minValue() {
		return minimum;
	}

	@Override
	public double maxValue() {
		return 1.0;
	}

	@Override
	public CodecHolder<? extends DensityFunction> getCodecHolder() {
		return CODEC_HOLDER;
	}
}
