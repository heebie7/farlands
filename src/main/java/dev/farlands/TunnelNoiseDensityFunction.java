package dev.farlands;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * 2D value noise that ignores Z, producing tunnels that run along the Z axis.
 * Multi-octave with smoothstep interpolation.
 */
public final class TunnelNoiseDensityFunction implements DensityFunction.Base {
	public static final MapCodec<TunnelNoiseDensityFunction> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.DOUBLE.fieldOf("scale").forGetter(f -> f.scale),
			Codec.DOUBLE.fieldOf("y_scale").forGetter(f -> f.yScale),
			Codec.INT.optionalFieldOf("octaves", 4).forGetter(f -> f.octaves),
			Codec.INT.optionalFieldOf("seed", 0).forGetter(f -> f.seed)
	).apply(instance, TunnelNoiseDensityFunction::new));

	public static final CodecHolder<TunnelNoiseDensityFunction> CODEC_HOLDER = CodecHolder.of(CODEC);

	private final double scale;
	private final double yScale;
	private final int octaves;
	private final int seed;

	public TunnelNoiseDensityFunction(double scale, double yScale, int octaves, int seed) {
		this.scale = scale;
		this.yScale = yScale;
		this.octaves = Math.max(1, Math.min(octaves, 8));
		this.seed = seed;
	}

	@Override
	public double sample(NoisePos pos) {
		double x = pos.blockX() * scale;
		double y = pos.blockY() * yScale;

		double total = 0.0;
		double amplitude = 1.0;
		double frequency = 1.0;
		double maxAmplitude = 0.0;

		for (int i = 0; i < octaves; i++) {
			total += valueNoise2D(x * frequency, y * frequency, seed + i) * amplitude;
			maxAmplitude += amplitude;
			amplitude *= 0.5;
			frequency *= 2.0;
		}

		return total / maxAmplitude;
	}

	private static double valueNoise2D(double x, double y, int seed) {
		int ix = (int) Math.floor(x);
		int iy = (int) Math.floor(y);
		double fx = x - ix;
		double fy = y - iy;
		fx = fx * fx * (3.0 - 2.0 * fx);
		fy = fy * fy * (3.0 - 2.0 * fy);

		double n00 = hash(ix, iy, seed);
		double n10 = hash(ix + 1, iy, seed);
		double n01 = hash(ix, iy + 1, seed);
		double n11 = hash(ix + 1, iy + 1, seed);

		double nx0 = n00 + fx * (n10 - n00);
		double nx1 = n01 + fx * (n11 - n01);
		return nx0 + fy * (nx1 - nx0);
	}

	private static double hash(int x, int y, int seed) {
		long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ (long) seed * 0x165667B19E3779F9L;
		h ^= h >>> 33;
		h *= 0xFF51AFD7ED558CCDL;
		h ^= h >>> 33;
		return (h & 0xFFFFFFL) / (double) 0x1000000L * 2.0 - 1.0;
	}

	@Override
	public DensityFunction apply(DensityFunctionVisitor visitor) {
		return visitor.apply(this);
	}

	@Override
	public double minValue() {
		return -1.0;
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
