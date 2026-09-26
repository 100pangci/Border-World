/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.borderworld.worldgen;

import com.borderworld.BorderWorld;
import com.borderworld.core.SpawnRegion;
import java.util.Locale;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;

/**
 * 密度函数自检探针（{@code -Dborderworld.selfTest=true} 时在服务端启动后运行一次）。
 *
 * <p>做法：在<b>同一个进程、同一棵树</b>上采样 Overworld 的 NoiseRouter 全部 15 个字段，
 * 先按当前（畸变开启）采样，再临时强制恒等变换采样，然后<b>逐位</b>比较采样结果。
 *
 * <ul>
 *   <li>正常区内的采样点：必须逐位一致（证明畸变没有改动原版生成逻辑）；</li>
 *   <li>过渡区/边境之地内的采样点：必须出现差异（证明畸变确实生效）。</li>
 * </ul>
 *
 * <p>这个判据不受"MC 特征生成跨运行不可复现"的影响——它直接比较我们改动的那一层数学。
 */
public final class DensityProbe {

    private DensityProbe() {
    }

    /** (x, y, z, 期望是否应受畸变影响) */
    private static final int[][] POINTS = {
        {8, 80, 8, 0},
        {8, 64, 8, 0},
        {-40, 70, 40, 0},
        {70, 100, -70, 0},
        {-72, 80, 88, 0},
        {88, 80, -72, 0},
        {96, 80, 8, 1},
        {8, 80, 96, 1},
        {112, 80, 8, 1},
        {200, 80, 200, 1},
        {-500, 70, 300, 1},
        {1200, 120, -800, 1},
    };

    private static volatile boolean ran;

    public static void runOnce(ServerWorld world) {
        if (ran) {
            return;
        }
        ran = true;
        if (world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) {
            return;
        }

        NoiseConfig config = world.getChunkManager().getNoiseConfig();
        NoiseRouter router = config.getNoiseRouter();
        int pointCount = POINTS.length;

        long[][] warped = new long[pointCount][];
        long[][] plain = new long[pointCount][];

        for (int i = 0; i < pointCount; i++) {
            warped[i] = sampleRouter(router, POINTS[i][0], POINTS[i][1], POINTS[i][2]);
        }
        SpawnRegion.setForceIdentity(true);
        try {
            for (int i = 0; i < pointCount; i++) {
                plain[i] = sampleRouter(router, POINTS[i][0], POINTS[i][1], POINTS[i][2]);
            }
        } finally {
            SpawnRegion.setForceIdentity(false);
        }

        int normalOk = 0;
        int normalBad = 0;
        int farOk = 0;
        int farBad = 0;
        for (int i = 0; i < pointCount; i++) {
            boolean identical = java.util.Arrays.equals(warped[i], plain[i]);
            boolean shouldDiffer = POINTS[i][3] != 0;
            if (!shouldDiffer) {
                if (identical) {
                    normalOk++;
                } else {
                    normalBad++;
                    BorderWorld.LOGGER.warn("[BorderWorld][selfTest] 正常区采样点 ({}, {}, {}) 出现差异！",
                        POINTS[i][0], POINTS[i][1], POINTS[i][2]);
                }
            } else {
                if (identical) {
                    farBad++;
                    BorderWorld.LOGGER.warn("[BorderWorld][selfTest] 边境区域采样点 ({}, {}, {}) 没有畸变！",
                        POINTS[i][0], POINTS[i][1], POINTS[i][2]);
                } else {
                    farOk++;
                }
            }
        }
        BorderWorld.LOGGER.info(
            "[BorderWorld][selfTest] 密度函数逐位比较: 正常区 {}/{} 逐位一致, 边境区域 {}/{} 已畸变",
            normalOk, normalOk + normalBad, farOk, farOk + farBad);
        if (normalBad > 0 || farBad > 0) {
            BorderWorld.LOGGER.error("[BorderWorld][selfTest] 自检未通过！");
        } else {
            BorderWorld.LOGGER.info("[BorderWorld][selfTest] 自检通过 ✓");
        }
    }

    private static long[] sampleRouter(NoiseRouter router, int x, int y, int z) {
        DensityFunction.NoisePos pos = new DensityFunction.UnblendedNoisePos(x, y, z);
        DensityFunction[] functions = {
            router.barrierNoise(), router.fluidLevelFloodednessNoise(), router.fluidLevelSpreadNoise(),
            router.lavaNoise(), router.temperature(), router.vegetation(), router.continents(),
            router.erosion(), router.depth(), router.ridges(), router.initialDensityWithoutJaggedness(),
            router.finalDensity(), router.veinToggle(), router.veinRidged(), router.veinGap(),
        };
        long[] out = new long[functions.length];
        for (int i = 0; i < functions.length; i++) {
            out[i] = Double.doubleToRawLongBits(functions[i].sample(pos));
        }
        return out;
    }

    /** 便于日志查看的最终密度值（调试用）。 */
    public static String describeFinalDensity(ServerWorld world, int x, int y, int z) {
        NoiseConfig config = world.getChunkManager().getNoiseConfig();
        double value = config.getNoiseRouter().finalDensity()
            .sample(new DensityFunction.UnblendedNoisePos(x, y, z));
        return String.format(Locale.ROOT, "finalDensity(%d,%d,%d)=%.6f", x, y, z, value);
    }
}
