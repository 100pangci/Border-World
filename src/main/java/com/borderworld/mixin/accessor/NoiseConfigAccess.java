package com.borderworld.mixin.accessor;

import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 替换 {@link NoiseConfig} 内部的噪声路由。
 *
 * <p>替换时机：{@code ServerChunkLoadingManager} 构造完成、且该维度是 Overworld 时。
 * 由于每个维度各有独立的 {@link NoiseConfig} 实例，这样天然实现"只影响主世界"。
 */
@Mixin(NoiseConfig.class)
public interface NoiseConfigAccess {

    @Mutable
    @Accessor("noiseRouter")
    void borderworld$setNoiseRouter(NoiseRouter router);
}
