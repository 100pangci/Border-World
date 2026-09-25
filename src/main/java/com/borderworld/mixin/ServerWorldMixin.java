package com.borderworld.mixin;

import com.borderworld.core.SpawnRegion;
import com.borderworld.config.FarlandsConfig;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.RandomSequencesState;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.spawner.SpecialSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 出生点锚定：让"正常区中心"始终等于<b>实际 world spawn</b>，而不是固定 (0,0)。
 *
 * <ul>
 *   <li>世界加载（构造完成）时读取 {@code level.dat} 里持久化的出生点；</li>
 *   <li>{@code /setworldspawn} 之后即时刷新锚点。</li>
 * </ul>
 *
 * <p>这里刻意使用 {@code properties.getSpawnPos()}（原始值）而不是
 * {@code World.getSpawnPos()}：后者会做世界边界检查，极端情况下可能触发区块加载，
 * 不适合在构造阶段调用。
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void borderworld$captureSpawnOnLoad(
        MinecraftServer server,
        Executor workerExecutor,
        LevelStorage.Session session,
        ServerWorldProperties properties,
        RegistryKey<World> worldKey,
        DimensionOptions dimensionOptions,
        WorldGenerationProgressListener worldGenerationProgressListener,
        boolean debugWorld,
        long seed,
        List<SpecialSpawner> spawners,
        boolean shouldTickTime,
        RandomSequencesState randomSequencesState,
        CallbackInfo ci
    ) {
        if (worldKey == World.OVERWORLD && FarlandsConfig.WARP_ENABLED) {
            SpawnRegion.updateSpawnPos(properties.getSpawnPos());
        }
    }

    @Inject(method = "setSpawnPos", at = @At("TAIL"))
    private void borderworld$captureSpawnChange(BlockPos pos, float angle, CallbackInfo ci) {
        SpawnRegion.updateSpawnPos(pos);
    }
}
