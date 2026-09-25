package com.borderworld.worldgen;

import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * 位置工具：为域偏移查表提供合法的整数 {@link DensityFunction.NoisePos}。
 *
 * <p>{@code NoisePos} 只能表达整数坐标，而 Far Lands 变换会产出分数坐标，
 * 因此真正畸变后需要把坐标四舍五入到方块精度（域偏移场本身是低频场，
 * 1 格量化在视觉上没有影响）。未发生畸变时直接返回调用者传入的原始位置对象，
 * 保证正常区与 Vanilla 完全一致（也避免破坏依赖 sampler 实例的缓存语义）。
 */
final class WarpPositions {

    private static final int COORDINATE_LIMIT = 30_000_000;

    private WarpPositions() {
    }

    static DensityFunction.NoisePos forShift(DensityFunction.NoisePos original, double x, double y, double z) {
        if (x == original.blockX() && y == original.blockY() && z == original.blockZ()) {
            return original;
        }
        return new DensityFunction.UnblendedNoisePos(roundClamp(x), roundClamp(y), roundClamp(z));
    }

    private static int roundClamp(double value) {
        long rounded = Math.round(value);
        if (rounded > COORDINATE_LIMIT) {
            rounded = COORDINATE_LIMIT;
        } else if (rounded < -COORDINATE_LIMIT) {
            rounded = -COORDINATE_LIMIT;
        }
        return (int) rounded;
    }
}
