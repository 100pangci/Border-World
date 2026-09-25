package com.borderworld.mixin;

import com.borderworld.worldgen.WarpInstaller;
import com.mojang.datafixers.DataFixer;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.thread.ThreadExecutor;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.ChunkStatusChangeListener;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 安装点：Overworld 的区块加载管理器构造完成时。
 *
 * <p>每个 {@code ServerWorld} 各有一个 {@link ServerChunkLoadingManager}（以及各自的
 * {@link NoiseConfig}），因此"在这里、且只对 Overworld 安装"就等于
 * "只影响主世界，Nether / End 保持原版"。
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ServerChunkLoadingManagerMixin {

    @Shadow
    @Final
    private NoiseConfig noiseConfig;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void borderworld$installOverworldWarp(
        ServerWorld world,
        LevelStorage.Session session,
        DataFixer dataFixer,
        StructureTemplateManager structureTemplateManager,
        Executor executor,
        ThreadExecutor<Runnable> mainThreadExecutor,
        ChunkProvider chunkProvider,
        ChunkGenerator chunkGenerator,
        WorldGenerationProgressListener worldGenerationProgressListener,
        ChunkStatusChangeListener chunkStatusChangeListener,
        Supplier<PersistentStateManager> persistentStateManagerFactory,
        int viewDistance,
        boolean dsync,
        CallbackInfo ci
    ) {
        if (world.getRegistryKey() == World.OVERWORLD) {
            WarpInstaller.installOverworld(this.noiseConfig);
        }
    }
}
