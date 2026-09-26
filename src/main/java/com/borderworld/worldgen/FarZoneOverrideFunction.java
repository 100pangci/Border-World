package com.borderworld.worldgen;

import com.borderworld.core.FarlandsTransform;
import com.borderworld.core.SpawnRegion;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * 远区取值覆盖：把某个密度函数在边境之地里的取值强制拉向 {@code farValue}（按 α 混合）。
 *
 * <p>用途：旧版边境之地"海平面以下全部灌水"（Wiki：*any area beneath sea level,
 * excluding regular caves, are flooded with water*）。原版含水层的灌水条件在
 * {@code AquiferSamplerImpl#getFluidBlockY}：
 * <pre>
 *   g = clamp(fluidLevelFloodednessNoise, -1, 1)
 *   e = g - h        // e &gt; 0 时就填到海平面
 * </pre>
 * 于是把 {@code fluidLevelFloodednessNoise} 在远区抬到 1，就会像旧版那样大面积灌水。
 */
public final class FarZoneOverrideFunction implements DensityFunction {

    private final DensityFunction delegate;
    private final double farValue;

    public FarZoneOverrideFunction(DensityFunction delegate, double farValue) {
        this.delegate = delegate;
        this.farValue = farValue;
    }

    @Override
    public double sample(DensityFunction.NoisePos pos) {
        FarlandsTransform transform = SpawnRegion.transform();
        if (transform.isIdentity()) {
            return this.delegate.sample(pos);
        }
        double alpha = transform.getDistortionFactor(pos.blockX(), pos.blockZ());
        if (alpha == 0.0) {
            return this.delegate.sample(pos);
        }
        double original = this.delegate.sample(pos);
        return original + alpha * (this.farValue - original);
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
        return Math.min(this.delegate.minValue(), this.farValue);
    }

    @Override
    public double maxValue() {
        return Math.max(this.delegate.maxValue(), this.farValue);
    }

    @Override
    public CodecHolder<? extends DensityFunction> getCodecHolder() {
        return this.delegate.getCodecHolder();
    }

    @Override
    public String toString() {
        return "FarZoneOverrideFunction[" + this.delegate + " -> " + this.farValue + "]";
    }
}
