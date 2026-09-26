/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.borderworld.mixin;

import com.borderworld.core.SpawnRegion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.level.ServerWorldProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 新世界出生点搜索期间的畸变开关。
 *
 * <p>原版 {@code setupSpawn} 会用 {@code MultiNoiseSampler} 找一个"适合出生"的位置：
 * 如果此时已经启用畸变，搜索本身就发生在畸变后的噪声里，可能把出生点选到奇怪的地方。
 * 因此搜索开始前暂停畸变（等价于原版行为），搜索结束后立刻用<b>最终出生点</b>锚定并恢复。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "setupSpawn", at = @At("HEAD"))
    private static void borderworld$beginSpawnSetup(
        ServerWorld world,
        ServerWorldProperties worldProperties,
        boolean bonusChest,
        boolean debugWorld,
        CallbackInfo ci
    ) {
        SpawnRegion.beginSpawnSetup();
    }

    @Inject(method = "setupSpawn", at = @At("RETURN"))
    private static void borderworld$endSpawnSetup(
        ServerWorld world,
        ServerWorldProperties worldProperties,
        boolean bonusChest,
        boolean debugWorld,
        CallbackInfo ci
    ) {
        SpawnRegion.endSpawnSetup(worldProperties.getSpawnPos());
    }
}
