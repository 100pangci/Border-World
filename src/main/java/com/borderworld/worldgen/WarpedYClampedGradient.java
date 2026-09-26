package com.borderworld.worldgen;

import com.borderworld.core.FarlandsTransform;
import com.borderworld.core.SpawnRegion;
import com.borderworld.mixin.accessor.DensityFunctionTypesYClampedGradientAccess;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * {@code minecraft:y_clamped_gradient} 的 Far Lands 包装：把"竖直剖面"整体平移。
 *
 * <p>原版这个函数只做一件事：{@code clampedMap(y, fromY, toY, fromValue, toValue)}，
 * 即"这个 y 在剖面里算哪一档"。Far Lands 的垂直阶跃本质就是
 * <b>整条剖面跟着地形一起搬</b>：在抬高 {@code H} 格的区域，位置 y 处应当取
 * 剖面里 {@code y - H} 的值。因此这里用变换后的 y 重新求值即可。
 *
 * <p>只有把它和噪声叶子（{@code WarpedDensityFunction} / {@code WarpedInterpolatedNoiseSampler}）
 * 一起包装，密度函数的零点才会真正平移 H 格，地表随之出现 H 格高的竖直墙面；
 * 否则噪声部分被搬走、梯度部分留在原地，墙面会被压回斜坡。
 */
public final class WarpedYClampedGradient implements DensityFunction {

    private final DensityFunction delegate;
    private final DensityFunctionTypesYClampedGradientAccess source;

    public WarpedYClampedGradient(DensityFunction delegate, DensityFunctionTypesYClampedGradientAccess source) {
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
            // 正常区：走原实现，保证与原版逐位一致
            return this.delegate.sample(pos);
        }

        double warpedY = transform.transformY(x, y, z, alpha);
        if (warpedY == y) {
            return this.delegate.sample(pos);
        }

        return MathHelper.clampedMap(
            warpedY,
            (double) this.source.borderworld$fromY(),
            (double) this.source.borderworld$toY(),
            this.source.borderworld$fromValue(),
            this.source.borderworld$toValue()
        );
    }

    @Override
    public void fill(double[] densities, DensityFunction.EachApplier applier) {
        applier.fill(densities, this);
    }

    @Override
    public DensityFunction apply(DensityFunction.DensityFunctionVisitor visitor) {
        // 保持在原版逐区块重建之后的身份，确保包装不会被剥掉
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
        return "WarpedYClampedGradient[" + this.delegate + "]";
    }
}
