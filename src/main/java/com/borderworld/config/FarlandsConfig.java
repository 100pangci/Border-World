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

    /** 过渡区宽度（方块）。1~2 个区块，默认 16 格 = 1 chunk（墙在进入过渡区后很快到满高）。 */
    public static final double TRANSITION_WIDTH_BLOCKS = 16.0;

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
    public static final double FARLANDS_PRIMARY_STRENGTH = 0.5;

    /** 主谐波周期（方块）。决定墙/平台的间距尺度。 */
    public static final double FARLANDS_PRIMARY_PERIOD = 128.0;

    /** 主谐波在 X / Z 轴的相位（弧度）。相同相位 → 轴对称的方格状地形。 */
    public static final double FARLANDS_PRIMARY_PHASE_X = 0.0;
    public static final double FARLANDS_PRIMARY_PHASE_Z = 0.0;

    /**
     * 次谐波强度/周期。默认 0 = 关闭。
     *
     * <p>开启后会大幅拉长位移尺度（相当于把 biome/地形格局也一起打乱），
     * 属于"更激进"的效果；只想要干净的边境之墙时保持 0。
     */
    public static final double FARLANDS_SECONDARY_STRENGTH = 0.0;
    public static final double FARLANDS_SECONDARY_PERIOD = 320.0;
    public static final double FARLANDS_SECONDARY_PHASE_X = 0.0;
    public static final double FARLANDS_SECONDARY_PHASE_Z = 1.7;

    /**
     * 径向线性斜坡：v(u) += RAMP · u，模拟经典机制里"插值权重随距离线性增长"的倾斜项。
     * 0 = 关闭（默认）；调大后地形会沿径向整体倾斜。
     */
    public static final double FARLANDS_RADIAL_RAMP = 0.0;

    /**
     * 竖直分层锯齿：远区每 {@code PERIOD} 格把高度采样折回一次、折回量 {@code STRENGTH} 格。
     *
     * <p>这是老版本边境之地那种<b>夹层 / 镂空 / 悬空石板</b>石壁的来源：
     * 折回会让密度剖面在同一列出现多个零点 → 石壁被打出空洞与平台。
     * 0 = 关闭（墙面会是一块干净的整体峭壁）。
     */
    public static final double VERTICAL_WARP_STRENGTH = 128.0;
    public static final double VERTICAL_WARP_PERIOD = 48.0;

    /**
     * 锯齿断层：每 {@code period} 格产生一次幅度 {@code strength} 的<b>水平</b>坐标跳变。
     *
     * <p>默认 0 = 关闭。它搬的是"采样到的地形"，墙有多高取决于两地地形差多少：
     * 在平坦地带几乎看不到，在起伏地带又会把不同 biome 的地形硬拼在一起（显得碎片化）。
     * "突然抬上去"改由切片抬升（见 {@link #FARLANDS_SLAB_STEP_X}）负责，
     * 高度恒定、与当地起伏无关。
     */
    public static final double FARLANDS_SAWTOOTH_STRENGTH = 0.0;
    public static final double FARLANDS_SAWTOOTH_PERIOD = 128.0;

    // ------------------------------------------------------------------
    // 切片抬升（"边境之墙"的形态来源）
    // ------------------------------------------------------------------

    /**
     * 沿 X 的粗台阶：每 {@code FARLANDS_SLAB_LATTICE_X} 格一片，片与片之间错开
     * {@code FARLANDS_SLAB_STEP_X} 格高度。
     *
     * <p>两级叠加（X 粗 + Z 细）复现老版本边境之地的层层石壁：
     * 原地形被切片整体错开，片内材质不变，片间是竖直断面。
     */
    public static final double FARLANDS_SLAB_STEP_X = 56.0;
    public static final double FARLANDS_SLAB_LATTICE_X = 96.0;

    /** 沿 Z 的细切片：更密的竖壁（老版本那种一眼看不到头的密集石壁）。 */
    public static final double FARLANDS_SLAB_STEP_Z = 40.0;
    public static final double FARLANDS_SLAB_LATTICE_Z = 32.0;

    /** 每轴切片档数：抬升量 = 档位(0..n-1) × STEP。4 档 ⇒ 最高约 3×56+3×40 = 288 格（地表正好顶到建造上限）。 */
    public static final int FARLANDS_SLAB_LEVELS = 4;

    /** 小尺度起伏的幅度上限（格）：墙面/台面被揉碎的程度（见 WorldgenMath#chaosLift）。 */
    public static final double CHAOS_RANGE = 46.0;

    /**
     * 随高度倾斜的剪切：不同高度把地形水平错开该幅度（格）。
     * 让石壁出现悬挑、空隙、倾斜的层理（老版本那种"整块地皮被推歪"的观感）。0 = 关闭。
     */
    public static final double FARLANDS_SHEAR_STRENGTH = 48.0;
    public static final double FARLANDS_SHEAR_PERIOD = 96.0;

    /**
     * 分层横向错位：每 {@code PERIOD} 格高度，水平采样位置跳变一次，
     * 幅度最大 {@code STRENGTH} 格（随机）。
     *
     * <p>这是"边境之地错位感"的来源：地形被切成水平层，每层整体左右挪开，
     * 层间出现悬挑、错缝、错开露出的层理。0 = 关闭。
     */
    public static final double FARLANDS_LAYER_SHIFT_STRENGTH = 96.0;
    public static final double FARLANDS_LAYER_SHIFT_PERIOD = 32.0;

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
            VERTICAL_WARP_PERIOD,
            FARLANDS_SAWTOOTH_STRENGTH,
            FARLANDS_SAWTOOTH_PERIOD,
            FARLANDS_SLAB_STEP_X,
            FARLANDS_SLAB_LATTICE_X,
            FARLANDS_SLAB_STEP_Z,
            FARLANDS_SLAB_LATTICE_Z,
            FARLANDS_SLAB_LEVELS,
            FARLANDS_SHEAR_STRENGTH,
            FARLANDS_SHEAR_PERIOD,
            FARLANDS_LAYER_SHIFT_STRENGTH,
            FARLANDS_LAYER_SHIFT_PERIOD
        );
    }

    /** 一行配置摘要，用于日志。 */
    public static String describe() {
        return "normal=" + NORMAL_REGION_CHUNKS + "chunks(" + (int) normalRadiusBlocks() * 2 + "格)"
            + ", transition=" + (int) TRANSITION_WIDTH_BLOCKS + "格"
            + ", primary=" + FARLANDS_PRIMARY_STRENGTH + "@" + (int) FARLANDS_PRIMARY_PERIOD + "格"
            + ", secondary=" + FARLANDS_SECONDARY_STRENGTH + "@" + (int) FARLANDS_SECONDARY_PERIOD + "格"
            + ", ramp=" + FARLANDS_RADIAL_RAMP
            + ", vertical=" + VERTICAL_WARP_STRENGTH + "@" + (int) VERTICAL_WARP_PERIOD + "格"
            + ", sawtooth=" + FARLANDS_SAWTOOTH_STRENGTH + "@" + (int) FARLANDS_SAWTOOTH_PERIOD + "格"
            + ", slabX=" + FARLANDS_SLAB_STEP_X + "@" + (int) FARLANDS_SLAB_LATTICE_X + "格"
            + ", slabZ=" + FARLANDS_SLAB_STEP_Z + "@" + (int) FARLANDS_SLAB_LATTICE_Z + "格"
            + " x" + FARLANDS_SLAB_LEVELS + "档"
            + ", shear=" + FARLANDS_SHEAR_STRENGTH + "@" + (int) FARLANDS_SHEAR_PERIOD + "格"
            + ", layerShift=" + FARLANDS_LAYER_SHIFT_STRENGTH + "@" + (int) FARLANDS_LAYER_SHIFT_PERIOD + "格";
    }
}
