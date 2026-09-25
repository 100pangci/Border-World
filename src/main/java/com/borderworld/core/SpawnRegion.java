package com.borderworld.core;

import com.borderworld.BorderWorld;
import com.borderworld.config.FarlandsConfig;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 出生区块锚点（core/ 中唯一接触 Minecraft 的类）。
 *
 * <p>职责：
 * <ol>
 *   <li>从服务端 Overworld 读取<b>实际出生点</b>，换算出出生区块；</li>
 *   <li>构造/缓存 {@link FarlandsTransform}（纯数学）；</li>
 *   <li>通过 volatile 字段给世界生成热路径提供当前变换（无锁、无分支风险）。</li>
 * </ol>
 *
 * <p>持久化说明：出生点本身由原版写进 {@code level.dat}（{@code ServerWorldProperties}），
 * 因此重启后锚点自动恢复，无需自建存档；{@code /setworldspawn} 会即时刷新锚点。
 *
 * <p>初始化时序（重要）：
 * <pre>
 *   新世界： ServerWorld 构造（临时锚点） → setupSpawn 开始（暂停畸变）
 *            → setupSpawn 结束（用最终出生点刷新锚点并恢复畸变） → 生成出生点保护区块
 *   旧世界： ServerWorld 构造（直接读到 level.dat 里的真实出生点）
 * </pre>
 * 出生点搜索期间畸变被暂停，因此搜索行为与原版一致，锚点也一定落在真实的出生区块上。
 */
public final class SpawnRegion {

    private SpawnRegion() {
    }

    /** 锚定结果：出生区块 + 区域几何 + 数学变换。 */
    public record Anchor(
        int spawnChunkX,
        int spawnChunkZ,
        double centerX,
        double centerZ,
        NormalRegion region,
        FarlandsTransform transform
    ) {
        public String describe() {
            return "spawnChunk=(" + this.spawnChunkX + ", " + this.spawnChunkZ + ")"
                + " center=(" + this.centerX + ", " + this.centerZ + ")"
                + " normalRadius=" + this.region.innerRadius()
                + " outerRadius=" + this.region.outerRadius();
        }
    }

    private static volatile Anchor anchor;
    private static volatile FarlandsTransform active = FarlandsTransform.IDENTITY;
    private static volatile boolean spawnSetupInProgress;

    // ------------------------------------------------------------------
    // 热路径
    // ------------------------------------------------------------------

    /** 当前生效的坐标变换；未锚定/已禁用时返回恒等变换。 */
    public static FarlandsTransform transform() {
        return active;
    }

    public static Anchor anchor() {
        return anchor;
    }

    public static boolean isActive() {
        return !active.isIdentity();
    }

    // ------------------------------------------------------------------
    // 锚点更新（由 mixin / 事件调用）
    // ------------------------------------------------------------------

    /** 从服务端世界刷新（仅 Overworld 有效）。 */
    public static void refreshFrom(ServerWorld world) {
        if (world.getRegistryKey() != World.OVERWORLD) {
            return;
        }
        updateSpawnPos(world.getSpawnPos());
    }

    /** 用原始出生点坐标（方块）刷新锚点。 */
    public static void updateSpawnPos(BlockPos spawnPos) {
        int chunkX = spawnPos.getX() >> 4;
        int chunkZ = spawnPos.getZ() >> 4;

        Anchor previous = anchor;
        if (previous != null && previous.spawnChunkX() == chunkX && previous.spawnChunkZ() == chunkZ) {
            return;
        }

        double centerX = chunkX * 16.0 + 8.0;
        double centerZ = chunkZ * 16.0 + 8.0;
        NormalRegion region = FarlandsConfig.normalRegion(centerX, centerZ);
        FarlandsTransform transform = new FarlandsTransform(centerX, centerZ, region, FarlandsConfig.params());
        Anchor updated = new Anchor(chunkX, chunkZ, centerX, centerZ, region, transform);
        anchor = updated;
        recomputeActive();

        if (FarlandsConfig.isLogEnabled()) {
            BorderWorld.LOGGER.info("[BorderWorld] 出生区块锚定: {}", updated.describe());
            BorderWorld.LOGGER.info("[BorderWorld] 世界生成配置: {}", FarlandsConfig.describe());
        }
    }

    /** 出生点搜索开始：暂停畸变，保证搜索与原版一致。 */
    public static void beginSpawnSetup() {
        spawnSetupInProgress = true;
        recomputeActive();
    }

    /** 出生点搜索结束：用最终出生点锚定并恢复畸变。 */
    public static void endSpawnSetup(BlockPos finalSpawnPos) {
        spawnSetupInProgress = false;
        updateSpawnPos(finalSpawnPos);
        recomputeActive();
    }

    private static void recomputeActive() {
        Anchor current = anchor;
        if (current == null || spawnSetupInProgress || !FarlandsConfig.WARP_ENABLED) {
            active = FarlandsTransform.IDENTITY;
        } else {
            active = current.transform();
        }
    }
}
