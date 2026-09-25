package com.borderworld.worldgen;

import com.borderworld.core.FarlandsTransform;
import com.borderworld.core.SpawnRegion;
import com.borderworld.mixin.accessor.InterpolatedNoiseSamplerAccess;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.noise.OctavePerlinNoiseSampler;
import net.minecraft.util.math.noise.PerlinNoiseSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * {@code minecraft:overworld/base_3d_noise}（{@link net.minecraft.util.math.noise.InterpolatedNoiseSampler}）
 * 的 Far Lands 包装。
 *
 * <p>这是经典 Far Lands 的直系噪声：坐标增量 = {@code 684.412 × xz_scale(0.25) = 171.103}/格，
 * 也就是 {@code 2^31 / 171.103 ≈ 12,550,824} 那个著名数字的来源。
 *
 * <p>{@code InterpolatedNoiseSampler.sample} 只接受整数 {@code NoisePos}，无法直接传入
 * 分数坐标，所以这里<b>按其原始算法</b>用变换后的分数坐标重算一遍（仅远区走这条路径，
 * 正常区仍然直接委托原版实现，保证逐位一致）。
 *
 * <p>算法与 1.21.1 原版逐行对应（三个 OctavePerlinNoiseSampler + 16 个八度 + smear scale），
 * 因此移植到新版本时只需同步这一个类。
 */
public final class WarpedInterpolatedNoiseSampler implements DensityFunction {

    private final InterpolatedNoiseSamplerAccess source;
    private final DensityFunction delegate;

    public WarpedInterpolatedNoiseSampler(DensityFunction delegate, InterpolatedNoiseSamplerAccess source) {
        this.delegate = delegate;
        this.source = source;
    }

    @Override
    public double sample(DensityFunction.NoisePos pos) {
        FarlandsTransform transform = SpawnRegion.transform();

        double x = pos.blockX();
        double y = pos.blockY();
        double z = pos.blockZ();

        if (transform.isIdentity()) {
            return this.delegate.sample(pos);
        }

        double alpha = transform.getDistortionFactor(x, z);
        if (alpha == 0.0) {
            return this.delegate.sample(pos);
        }

        return this.sampleWarped(
            transform.transformX(x, y, z, alpha),
            transform.transformY(x, y, z, alpha),
            transform.transformZ(x, y, z, alpha)
        );
    }

    /** 与 {@code InterpolatedNoiseSampler.sample} 等价的分数坐标版本。 */
    private double sampleWarped(double worldX, double worldY, double worldZ) {
        double scaledXzScale = this.source.borderworld$scaledXzScale();
        double scaledYScale = this.source.borderworld$scaledYScale();
        double xzFactor = this.source.borderworld$xzFactor();
        double yFactor = this.source.borderworld$yFactor();
        double smearScaleMultiplier = this.source.borderworld$smearScaleMultiplier();

        double d = worldX * scaledXzScale;
        double e = worldY * scaledYScale;
        double f = worldZ * scaledXzScale;
        double g = d / xzFactor;
        double h = e / yFactor;
        double i = f / xzFactor;
        double j = scaledYScale * smearScaleMultiplier;
        double k = j / yFactor;

        double lower = 0.0;
        double upper = 0.0;
        double interpolation = 0.0;
        double octaveScale = 1.0;

        OctavePerlinNoiseSampler interpolationNoise = this.source.borderworld$interpolationNoise();
        for (int octave = 0; octave < 8; octave++) {
            PerlinNoiseSampler sampler = interpolationNoise.getOctave(octave);
            if (sampler != null) {
                interpolation += sampler.sample(
                        OctavePerlinNoiseSampler.maintainPrecision(g * octaveScale),
                        OctavePerlinNoiseSampler.maintainPrecision(h * octaveScale),
                        OctavePerlinNoiseSampler.maintainPrecision(i * octaveScale),
                        k * octaveScale,
                        h * octaveScale
                    )
                    / octaveScale;
            }
            octaveScale /= 2.0;
        }

        double blend = (interpolation / 10.0 + 1.0) / 2.0;
        boolean onlyLower = blend >= 1.0;
        boolean onlyUpper = blend <= 0.0;
        octaveScale = 1.0;

        OctavePerlinNoiseSampler lowerNoise = this.source.borderworld$lowerInterpolatedNoise();
        OctavePerlinNoiseSampler upperNoise = this.source.borderworld$upperInterpolatedNoise();
        for (int octave = 0; octave < 16; octave++) {
            double s = OctavePerlinNoiseSampler.maintainPrecision(d * octaveScale);
            double t = OctavePerlinNoiseSampler.maintainPrecision(e * octaveScale);
            double u = OctavePerlinNoiseSampler.maintainPrecision(f * octaveScale);
            double v = j * octaveScale;

            if (!onlyLower) {
                PerlinNoiseSampler sampler = lowerNoise.getOctave(octave);
                if (sampler != null) {
                    lower += sampler.sample(s, t, u, v, e * octaveScale) / octaveScale;
                }
            }
            if (!onlyUpper) {
                PerlinNoiseSampler sampler = upperNoise.getOctave(octave);
                if (sampler != null) {
                    upper += sampler.sample(s, t, u, v, e * octaveScale) / octaveScale;
                }
            }
            octaveScale /= 2.0;
        }

        return MathHelper.clampedLerp(lower / 512.0, upper / 512.0, blend) / 128.0;
    }

    @Override
    public void fill(double[] densities, DensityFunction.EachApplier applier) {
        applier.fill(densities, this);
    }

    @Override
    public DensityFunction apply(DensityFunction.DensityFunctionVisitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public double minValue() {
        return this.delegate.minValue();
    }

    @Override
    public double maxValue() {
        return this.delegate.maxValue();
    }

    @Override
    public CodecHolder<? extends DensityFunction> getCodecHolder() {
        return this.delegate.getCodecHolder();
    }

    @Override
    public String toString() {
        return "WarpedInterpolatedNoiseSampler";
    }
}
