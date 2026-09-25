package com.borderworld;

import com.borderworld.config.FarlandsConfig;
import com.borderworld.core.SpawnRegion;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Border World 主入口。
 *
 * <p>设计目标：世界以<b>实际出生点</b>为中心保留一小块完全原版的正常区域，
 * 之外通过扭曲世界生成噪声坐标，让地形连续地"坠入"边境之地。
 *
 * <p>代码分层：
 * <ul>
 *   <li>{@code core/}：纯数学（{@code FarlandsTransform} / {@code NormalRegion} /
 *       {@code WorldgenMath}）+ 出生点锚定（{@code SpawnRegion}）；</li>
 *   <li>{@code worldgen/}：MC 桥接层（噪声叶子包装 + 安装器）；</li>
 *   <li>{@code mixin/}：注入点（噪声叶子访问器 + Overworld 安装 + 出生点捕获）；</li>
 *   <li>{@code config/}：全部可调参数。</li>
 * </ul>
 */
public final class BorderWorld implements ModInitializer {
    public static final String MOD_ID = "borderworld";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[BorderWorld] onInitialize (warpEnabled={})", FarlandsConfig.WARP_ENABLED);

        // 双保险：世界加载完成后再刷新一次出生点锚点。
        // 主路径是 ServerWorld / MinecraftServer 上的 mixin（保证在任何区块生成之前完成锚定）。
        ServerWorldEvents.LOAD.register((server, world) -> {
            SpawnRegion.refreshFrom(world);
            if (FarlandsConfig.isLogEnabled() && world.getRegistryKey().getValue().getPath().equals("overworld")) {
                LOGGER.info("[BorderWorld] world loaded, activeTransform={}", SpawnRegion.transform());
            }
            if (Boolean.getBoolean("borderworld.selfTest")) {
                com.borderworld.worldgen.DensityProbe.runOnce(world);
            }
        });
    }
}
