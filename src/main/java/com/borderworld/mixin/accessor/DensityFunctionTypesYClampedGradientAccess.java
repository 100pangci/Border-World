/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.borderworld.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code DensityFunctionTypes$YClampedGradient} 的字段访问器。
 *
 * <p>这是"垂直阶跃"必须覆盖的节点：深度梯度（{@code depth}）与地表滑移项
 * （{@code applySurfaceSlides}）都用它表达"随高度线性变化"的剖面。
 * 只包装噪声叶子而不包装它们时，未变换的梯度项会把地形高度牢牢压在原位，
 * 垂直位移几乎被抵消——这是"墙抬不起来"的根因。
 */
@Mixin(targets = "net.minecraft.world.gen.densityfunction.DensityFunctionTypes$YClampedGradient")
public interface DensityFunctionTypesYClampedGradientAccess {

    @Accessor("fromY")
    int borderworld$fromY();

    @Accessor("toY")
    int borderworld$toY();

    @Accessor("fromValue")
    double borderworld$fromValue();

    @Accessor("toValue")
    double borderworld$toValue();
}
