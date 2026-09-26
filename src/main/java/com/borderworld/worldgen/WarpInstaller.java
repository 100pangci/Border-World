package com.borderworld.worldgen;

import com.borderworld.BorderWorld;
import com.borderworld.mixin.accessor.DensityFunctionTypesNoiseAccess;
import com.borderworld.mixin.accessor.DensityFunctionTypesShiftedNoiseAccess;
import com.borderworld.mixin.accessor.DensityFunctionTypesYClampedGradientAccess;
import com.borderworld.mixin.accessor.InterpolatedNoiseSamplerAccess;
import com.borderworld.mixin.accessor.MultiNoiseSamplerAccess;
import com.borderworld.mixin.accessor.NoiseConfigAccess;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.util.math.noise.InterpolatedNoiseSampler;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;

/**
 * 把 Overworld 的噪声叶子替换成 Far Lands 坐标变换包装（一次性安装）。
 *
 * <p>为什么选这里而不是重写 {@code ChunkGenerator}：
 * <ul>
 *   <li>噪声叶子是气候、地形、洞穴、矿脉、含水层的<b>唯一公共入口</b>，
 *       在此变换即可让全部子系统共享同一个畸变后的坐标空间；</li>
 *   <li>树的形状、Marker（interpolated / flat_cache / cache_2d / cache_once /
 *       cache_all_in_cell）、Spline、rangeChoice 等原版结构完全不动，
 *       {@code ChunkNoiseSampler} 的插值与缓存路径保持原样；</li>
 *   <li>正常区变换返回原坐标，保证与原版逐位一致。</li>
 * </ul>
 *
 * <p>每个维度有独立的 {@link NoiseConfig}，因此只在 Overworld 的实例上安装，
 * 天然实现"第一阶段只影响主世界"。
 */
public final class WarpInstaller {

    private static final Set<NoiseConfig> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());

    private WarpInstaller() {
    }

    /** 给 Overworld 的 NoiseConfig 安装坐标变换；重复调用无副作用。 */
    public static void installOverworld(NoiseConfig config) {
        synchronized (INSTALLED) {
            if (!INSTALLED.add(config)) {
                return;
            }
        }

        // 捕获未被包装的原版 base_3d_noise（溢出机制要用它在"钉死坐标"处取符号）
        final DensityFunction[] baseHolder = new DensityFunction[1];

        DensityFunction.DensityFunctionVisitor visitor = new DensityFunction.DensityFunctionVisitor() {
            @Override
            public DensityFunction apply(DensityFunction function) {
                if (baseHolder[0] == null && function instanceof InterpolatedNoiseSampler) {
                    baseHolder[0] = function;
                }
                return wrapLeaf(function);
            }

            @Override
            public DensityFunction.Noise apply(DensityFunction.Noise noise) {
                return noise;
            }
        };

        NoiseRouter router = config.getNoiseRouter();
        NoiseRouter warpedRouter = new NoiseRouter(
            router.barrierNoise().apply(visitor),
            router.fluidLevelFloodednessNoise().apply(visitor),
            router.fluidLevelSpreadNoise().apply(visitor),
            router.lavaNoise().apply(visitor),
            router.temperature().apply(visitor),
            router.vegetation().apply(visitor),
            router.continents().apply(visitor),
            router.erosion().apply(visitor),
            router.depth().apply(visitor),
            router.ridges().apply(visitor),
            router.initialDensityWithoutJaggedness().apply(visitor),
            router.finalDensity().apply(visitor),
            router.veinToggle().apply(visitor),
            router.veinRidged().apply(visitor),
            router.veinGap().apply(visitor)
        );
        ((NoiseConfigAccess) (Object) config).borderworld$setNoiseRouter(warpedRouter);

        // biome / 结构放置 / 出生点搜索走的是独立的 MultiNoiseSampler，这里同步替换，
        // 否则"查询到的 biome"与"生成出来的 biome"会对不上。
        // 注意：只改六个气候字段，不重建对象（Fabric API 会给采样器注入 seed 字段，重建会丢）。
        MultiNoiseUtil.MultiNoiseSampler sampler = config.getMultiNoiseSampler();
        DensityFunction temperature = sampler.temperature().apply(visitor);
        DensityFunction humidity = sampler.humidity().apply(visitor);
        DensityFunction continentalness = sampler.continentalness().apply(visitor);
        DensityFunction erosion = sampler.erosion().apply(visitor);
        DensityFunction depth = sampler.depth().apply(visitor);
        DensityFunction weirdness = sampler.weirdness().apply(visitor);
        MultiNoiseSamplerAccess samplerAccess = (MultiNoiseSamplerAccess) (Object) sampler;
        samplerAccess.borderworld$setTemperature(temperature);
        samplerAccess.borderworld$setHumidity(humidity);
        samplerAccess.borderworld$setContinentalness(continentalness);
        samplerAccess.borderworld$setErosion(erosion);
        samplerAccess.borderworld$setDepth(depth);
        samplerAccess.borderworld$setWeirdness(weirdness);

        BorderWorld.LOGGER.info("[BorderWorld] 畸变参数: {}", com.borderworld.config.FarlandsConfig.describe());

        if (BorderWorld.LOGGER.isInfoEnabled()) {
            BorderWorld.LOGGER.info("[BorderWorld] 已为 Overworld 安装噪声坐标变换（NoiseRouter + MultiNoiseSampler）");
        }
    }

    /** 识别噪声叶子并包装；其他节点原样返回。 */
    private static DensityFunction wrapLeaf(DensityFunction function) {
        if (function instanceof DensityFunctionTypesNoiseAccess noise) {
            DensityFunction.Noise offsetNoise = noise.borderworld$noise();
            if (offsetNoise == null) {
                return function;
            }
            double xzScale = noise.borderworld$xzScale();
            double yScale = noise.borderworld$yScale();
            return new WarpedDensityFunction(
                function,
                (pos, x, y, z) -> offsetNoise.sample(x * xzScale, y * yScale, z * xzScale)
            );
        }

        if (function instanceof DensityFunctionTypesShiftedNoiseAccess shifted) {
            DensityFunction.Noise offsetNoise = shifted.borderworld$noise();
            if (offsetNoise == null) {
                return function;
            }
            double xzScale = shifted.borderworld$xzScale();
            double yScale = shifted.borderworld$yScale();
            return new WarpedDensityFunction(function, (pos, x, y, z) -> {
                // 正常区（坐标未变）时把原始 pos 原样传下去，保持与 Vanilla 完全一致；
                // 只有真正畸变后才需要为域偏移查表构造"量化位置"（NoisePos 只接受整数）。
                DensityFunction.NoisePos shiftPos = WarpPositions.forShift(pos, x, y, z);
                double d = x * xzScale + shifted.borderworld$shiftX().sample(shiftPos);
                double e = y * yScale + shifted.borderworld$shiftY().sample(shiftPos);
                double f = z * xzScale + shifted.borderworld$shiftZ().sample(shiftPos);
                return offsetNoise.sample(d, e, f);
            });
        }

        if (function instanceof InterpolatedNoiseSampler interpolated) {
            return new WarpedInterpolatedNoiseSampler(function, (InterpolatedNoiseSamplerAccess) (Object) interpolated);
        }

        // 竖直剖面（深度梯度 / 地表滑移）必须一起做垂直位移，
        // 否则地表高度会被未变换的梯度项拽回原位。
        if (function instanceof DensityFunctionTypesYClampedGradientAccess gradient) {
            return new WarpedYClampedGradient(function, gradient);
        }

        return function;
    }
}
