package com.borderworld.mixin.accessor;

import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code DensityFunctionTypes.Noise}（package-private 记录）的只读访问器。
 *
 * <p>该记录是绝大多数噪声的叶子节点：含水层、洞穴、矿脉、jagged 等。
 */
@Mixin(targets = "net.minecraft.world.gen.densityfunction.DensityFunctionTypes$Noise")
public interface DensityFunctionTypesNoiseAccess {

    @Accessor("noise")
    DensityFunction.Noise borderworld$noise();

    @Accessor("xzScale")
    double borderworld$xzScale();

    @Accessor("yScale")
    double borderworld$yScale();
}
