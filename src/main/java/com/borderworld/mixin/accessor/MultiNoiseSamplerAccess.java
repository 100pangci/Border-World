/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.borderworld.mixin.accessor;

import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 就地把 Overworld 的 {@code MultiNoiseSampler} 六个气候函数替换成畸变版本。
 *
 * <p>注意：这里刻意<b>不重建采样器对象</b>，只改字段。原因是 Fabric API 的
 * {@code fabric-biome-api-v1} 会给采样器注入一个 {@code seed} 字段
 * （{@code MultiNoiseSamplerHooks}），重建实例会丢掉它并导致
 * {@code ChunkNoiseSampler} 初始化时 NPE。改字段则对所有注入保持兼容。
 */
@Mixin(targets = "net.minecraft.world.biome.source.util.MultiNoiseUtil$MultiNoiseSampler")
public interface MultiNoiseSamplerAccess {

    @Mutable
    @Accessor("temperature")
    void borderworld$setTemperature(DensityFunction function);

    @Mutable
    @Accessor("humidity")
    void borderworld$setHumidity(DensityFunction function);

    @Mutable
    @Accessor("continentalness")
    void borderworld$setContinentalness(DensityFunction function);

    @Mutable
    @Accessor("erosion")
    void borderworld$setErosion(DensityFunction function);

    @Mutable
    @Accessor("depth")
    void borderworld$setDepth(DensityFunction function);

    @Mutable
    @Accessor("weirdness")
    void borderworld$setWeirdness(DensityFunction function);
}
