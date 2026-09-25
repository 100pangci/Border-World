package com.borderworld.config;

import com.borderworld.core.FarlandsTransform;
import com.borderworld.core.NormalRegion;

/**
 * Border World 全部可调参数。
 *
 * <p>这些常量刻意集中在一个纯数据类里，方便之后整体搬到配置文件
 * （例如 JSON / TOML）：届时只需把 {@code static final} 换成实例字段并加读取逻辑，
 * {@code core/} 里的数学实现完全不用改。
 *
 * <p>单位说明：
 * <ul>
 *   <li>距离、周期：方块（blocks）</li>
 *   <li>强度：无量纲，作用于坐标映射的导数（1 = 与原生坐标同步，&lt;1 = 压缩，&gt;1 = 拉伸/折叠）</li>
 *   <li>相位：弧度</li>
 * </ul>
 */
public final class FarlandsConfig {
    private FarlandsConfig() {
    }

    // ------------------------------------------------------------------
    // 正常区 / 过渡区
    // ------------------------------------------------------------------

    /** 正常区边长（区块）。10 × 10 chunks = 160 × 160 格。 */
    public static final int NORMAL_REGION_CHUNKS = 10;

    /** 过渡区宽度（方块）。1~2 个区块，默认 32 格 = 2 chunks。 */
    public static final double TRANSITION_WIDTH_BLOCKS = 32.0;

    // ------------------------------------------------------------------
    // Far Lands 畸变（核心数学参数）
    // ------------------------------------------------------------------

    /**
     * 主谐波强度。含义：坐标映射导数 v'(u) = 1 + A·cos(...)。
     * <ul>
     *   <li>A = 0：无畸变</li>
     *   <li>A &lt; 1：压缩带（地形被挤向平台/墙体，不会折叠）</li>
     *   <li>A = 1：导数触 0（出现理想"停滞带"，即经典 Far Lands 的挤出平面）</li>
     *   <li>A &gt; 1：局部折叠（出现镜像重复地形，更接近经典"重复结构"）</li>
     * </ul>
     */
    public static final double FARLANDS_PRIMARY_STRENGTH = 1.05;

    /** 主谐波周期（方块）。决定墙/平台的间距尺度。 */
    public static final double FARLANDS_PRIMARY_PERIOD = 96.0;

    /** 主谐波在 X / Z 轴的相位（弧度）。相同相位 → 轴对称的方格状地形。 */
    public static final double FARLANDS_PRIMARY_PHASE_X = 0.0;
    public static final double FARLANDS_PRIMARY_PHASE_Z = 0.0;

    /**
     * 次谐波强度/周期：模拟经典 Far Lands 中"多个八度在不同距离逐级溢出"的效果，
     * 用来打破单一正弦的规则性，使地形更不规则。
     */
    public static final double FARLANDS_SECONDARY_STRENGTH = 0.35;
    public static final double FARLANDS_SECONDARY_PERIOD = 37.0;
    public static final double FARLANDS_SECONDARY_PHASE_X = 0.0;
    public static final double FARLANDS_SECONDARY_PHASE_Z = 1.7;

    /**
     * 径向线性斜坡：v(u) += RAMP · u，模拟经典机制里"插值权重随距离线性增长"的倾斜项。
     * 0 = 关闭（默认）；调大后地形会沿径向整体倾斜。
     */
    public static final double FARLANDS_RADIAL_RAMP = 0.0;

    /**
     * 垂直（Y 轴）畸变强度与周期。默认 0 = 不畸变。
     *
     * <p>竖直方向的畸变会带来"千层饼"一样的水平平台，但也可能让含水层/基岩层
     * 位置偏移，属于实验性参数；启用前建议单独测试。
     */
    public static final double VERTICAL_WARP_STRENGTH = 0.0;
    public static final double VERTICAL_WARP_PERIOD = 64.0;

    // ------------------------------------------------------------------
    // 运行开关
    // ------------------------------------------------------------------

    /**
     * 世界生成畸变总开关，可用系统属性 {@code -Dborderworld.warpEnabled=false} 临时关闭
     * （用于 A/B 对照测试）。
     */
    public static final String WARP_ENABLED_PROPERTY = "borderworld.warpEnabled";

    /** 是否在日志中输出出生点锚定/配置摘要。 */
    public static final String LOG_PROPERTY = "borderworld.log";

    /** 启动时读取一次的开关（热路径会频繁访问，不能每次查系统属性）。 */
    public static final boolean WARP_ENABLED = readBoolean(WARP_ENABLED_PROPERTY, true);
    public static final boolean LOG_ENABLED = readBoolean(LOG_PROPERTY, true);

    private static boolean readBoolean(String key, boolean fallback) {
        String value = System.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public static boolean isLogEnabled() {
        return LOG_ENABLED;
    }

    // ------------------------------------------------------------------
    // 派生值
    // ------------------------------------------------------------------

    /** 正常区半宽（方块）：10 chunks / 2 = 80 格。 */
    public static double normalRadiusBlocks() {
        return NORMAL_REGION_CHUNKS * 16.0 / 2.0;
    }

    /** 以出生区块中心为原点构造正常区几何。 */
    public static NormalRegion normalRegion(double centerX, double centerZ) {
        return new NormalRegion(centerX, centerZ, normalRadiusBlocks(), TRANSITION_WIDTH_BLOCKS);
    }

    /** 构造 Far Lands 数学变换的参数包。 */
    public static FarlandsTransform.Params params() {
        return new FarlandsTransform.Params(
            FARLANDS_PRIMARY_STRENGTH,
            FARLANDS_PRIMARY_PERIOD,
            FARLANDS_PRIMARY_PHASE_X,
            FARLANDS_PRIMARY_PHASE_Z,
            FARLANDS_SECONDARY_STRENGTH,
            FARLANDS_SECONDARY_PERIOD,
            FARLANDS_SECONDARY_PHASE_X,
            FARLANDS_SECONDARY_PHASE_Z,
            FARLANDS_RADIAL_RAMP,
            VERTICAL_WARP_STRENGTH,
            VERTICAL_WARP_PERIOD
        );
    }

    /** 一行配置摘要，用于日志。 */
    public static String describe() {
        return "normal=" + NORMAL_REGION_CHUNKS + "chunks(" + (int) normalRadiusBlocks() * 2 + "格)"
            + ", transition=" + (int) TRANSITION_WIDTH_BLOCKS + "格"
            + ", primary=" + FARLANDS_PRIMARY_STRENGTH + "@" + (int) FARLANDS_PRIMARY_PERIOD + "格"
            + ", secondary=" + FARLANDS_SECONDARY_STRENGTH + "@" + (int) FARLANDS_SECONDARY_PERIOD + "格"
            + ", ramp=" + FARLANDS_RADIAL_RAMP
            + ", vertical=" + VERTICAL_WARP_STRENGTH + "@" + (int) VERTICAL_WARP_PERIOD + "格";
    }
}
