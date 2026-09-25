package com.borderworld.mixin.accessor;

import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code DensityFunctionTypes.ShiftedNoise}（package-private 记录）的只读访问器。
 *
 * <p>气候噪声（温度/湿度/大陆性/侵蚀/深度/怪异度）都走这里，
 * 对它做坐标变换可以让 biome 与地形共享同一套畸变空间。
 */
@Mixin(targets = "net.minecraft.world.gen.densityfunction.DensityFunctionTypes$ShiftedNoise")
public interface DensityFunctionTypesShiftedNoiseAccess {

    @Accessor("shiftX")
    DensityFunction borderworld$shiftX();

    @Accessor("shiftY")
    DensityFunction borderworld$shiftY();

    @Accessor("shiftZ")
    DensityFunction borderworld$shiftZ();

    @Accessor("xzScale")
    double borderworld$xzScale();

    @Accessor("yScale")
    double borderworld$yScale();

    @Accessor("noise")
    DensityFunction.Noise borderworld$noise();
}
