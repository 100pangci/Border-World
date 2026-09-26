/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.borderworld.worldgen;

import com.borderworld.core.FarlandsTransform;
import com.borderworld.core.SpawnRegion;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * 噪声叶子节点的坐标变换包装器（MC 桥接层）。
 *
 * <p>包装对象本身不变换原版树的结构（不改类型、不加 Marker），只是把
 * <b>进入噪声的世界坐标</b>替换成 Far Lands 变换后的坐标：
 * <pre>
 *   原版：  noise.sample(pos)
 *   包装：  noise.sample(warp(pos))
 * </pre>
 *
 * <p>关键性质：
 * <ul>
 *   <li>{@code apply(visitor)} 返回自身，因此区块生成时原版对密度树做的
 *       逐区块重建（{@code ChunkNoiseSampler} 的 actual-density 缓存）不会丢失包装；</li>
 *   <li>正常区（畸变系数 = 0）时把<b>原始坐标</b>原样传下去，不产生任何数值差异，
 *       保证正常区与原版逐位一致；</li>
 *   <li>{@code minValue/maxValue} 直接沿用被包装函数，避免影响原版的范围推断。</li>
 * </ul>
 */
public final class WarpedDensityFunction implements DensityFunction {

    /** 用变换后的世界坐标取样一个噪声叶子。 */
    @FunctionalInterface
    public interface WarpedSampler {
        double sample(DensityFunction.NoisePos originalPos, double warpedX, double warpedY, double warpedZ);
    }

    private final DensityFunction delegate;
    private final WarpedSampler sampler;

    public WarpedDensityFunction(DensityFunction delegate, WarpedSampler sampler) {
        this.delegate = delegate;
        this.sampler = sampler;
    }

    /** 被包装的原始密度函数。 */
    public DensityFunction delegate() {
        return this.delegate;
    }

    @Override
    public double sample(DensityFunction.NoisePos pos) {
        FarlandsTransform transform = SpawnRegion.transform();

        double x = pos.blockX();
        double y = pos.blockY();
        double z = pos.blockZ();

        if (transform.isIdentity()) {
            return this.sampler.sample(pos, x, y, z);
        }

        double alpha = transform.getDistortionFactor(x, z);
        if (alpha == 0.0) {
            // 正常区：走原坐标，保证与原版完全一致
            return this.sampler.sample(pos, x, y, z);
        }

        return this.sampler.sample(
            pos,
            transform.transformX(x, y, z, alpha),
            transform.transformY(x, y, z, alpha),
            transform.transformZ(x, y, z, alpha)
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
        // 运行期包装，不参与数据包序列化；沿用底层 codec 仅用于调试输出
        return this.delegate.getCodecHolder();
    }

    @Override
    public String toString() {
        return "WarpedDensityFunction[" + this.delegate + "]";
    }
}
