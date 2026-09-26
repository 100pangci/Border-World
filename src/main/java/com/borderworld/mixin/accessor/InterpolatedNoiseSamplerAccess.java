/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.borderworld.mixin.accessor;

import net.minecraft.util.math.noise.InterpolatedNoiseSampler;
import net.minecraft.util.math.noise.OctavePerlinNoiseSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读取 {@link InterpolatedNoiseSampler} 的内部噪声与缩放参数（供分数坐标重算使用）。 */
@Mixin(InterpolatedNoiseSampler.class)
public interface InterpolatedNoiseSamplerAccess {

    @Accessor("lowerInterpolatedNoise")
    OctavePerlinNoiseSampler borderworld$lowerInterpolatedNoise();

    @Accessor("upperInterpolatedNoise")
    OctavePerlinNoiseSampler borderworld$upperInterpolatedNoise();

    @Accessor("interpolationNoise")
    OctavePerlinNoiseSampler borderworld$interpolationNoise();

    @Accessor("scaledXzScale")
    double borderworld$scaledXzScale();

    @Accessor("scaledYScale")
    double borderworld$scaledYScale();

    @Accessor("xzFactor")
    double borderworld$xzFactor();

    @Accessor("yFactor")
    double borderworld$yFactor();

    @Accessor("smearScaleMultiplier")
    double borderworld$smearScaleMultiplier();
}
