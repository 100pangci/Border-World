package com.borderworld.core;

/**
 * 世界生成数学工具（零 Minecraft 依赖）。
 *
 * <p>包含两部分：
 * <ol>
 *   <li>通用数学：clamp / lerp / smoothstep / 切比雪夫距离 / 有界谐波相位调制；</li>
 *   <li><b>经典 Far Lands 参考数学</b>：旧版噪声格点整数溢出链条的精确复刻，
 *       仅用于文档、单元自检与未来的实验模式，不参与正常生成路径。</li>
 * </ol>
 *
 * <h2>经典 Far Lands 成因（参考实现依据）</h2>
 * <pre>
 *   噪声坐标 n = 世界坐标 × 171.103          （684.412 / 4，每方块）
 *   整数格点 i = 饱和取整(n)                 （Java double→int 在越界后钳在 ±2^31）
 *   插值权重 f = n - i                       （本应落在 [0,1)，越界后线性增长）
 *   噪声值 = 用 f 对格点梯度做插值 → 退化为大规模线性外推
 * </pre>
 * 溢出边界：{@code 2^31 / 171.103 ≈ 12,550,824} 格。
 * 经典修复（Beta 1.8+ / 现代 {@code OctavePerlinNoiseSampler.maintainPrecision}）为
 * {@code n - lfloor(n / 2^25 + 0.5) × 2^25}（即 mod 2^25）。
 */
public final class WorldgenMath {
    private WorldgenMath() {
    }

    // ------------------------------------------------------------------
    // 通用数学
    // ------------------------------------------------------------------

    public static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    public static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    /** 平滑阶跃：t∈[0,1] → 3t²-2t³，C¹ 连续（两端导数为 0）。 */
    public static double smoothstep(double t) {
        double x = clamp(t, 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    /** 切比雪夫距离（方块空间里的"方形半径"）。 */
    public static double chebyshevDistance(double dx, double dz) {
        double ax = Math.abs(dx);
        double az = Math.abs(dz);
        return ax > az ? ax : az;
    }

    /**
     * 有界谐波相位调制项（Far Lands 畸变的核心构件）。
     *
     * <pre>
     *   warp(u) = strength · (period / 2π) · sin(2π·u/period + phase)
     *   d warp / du = strength · cos(...)
     * </pre>
     *
     * <p>把它加到坐标映射上，会让映射导数在 {@code 1 ± strength} 之间摆动：
     * 导数为 0 的位置就是"停滞带"（地形沿该轴被挤出成平面/墙）。
     * 相比经典机制的无界线性外推，这里用有界正弦保证噪声值始终有限，
     * 原版密度管线（spline / rangeChoice / squeeze）全部照常工作。
     */
    public static double harmonicWarp(double u, double strength, double period, double phase) {
        if (strength == 0.0 || period == 0.0) {
            return 0.0;
        }
        double omega = TWO_PI / period;
        return strength * Math.sin(omega * u + phase) / omega;
    }

    /** {@link #harmonicWarp} 对 u 的导数。 */
    public static double harmonicDerivative(double u, double strength, double period, double phase) {
        if (strength == 0.0 || period == 0.0) {
            return 0.0;
        }
        double omega = TWO_PI / period;
        return strength * Math.cos(omega * u + phase);
    }

    /**
     * 锯齿相位调制：在 {@code u = k · period} 处产生幅度为 {@code strength} 的坐标跳变。
     *
     * <pre>
     *   saw(u) = strength · (frac(u / period) − 0.5)
     * </pre>
     *
     * <p>这是"墙"的来源：坐标在一个点上突然平移，噪声采样位置随之突变，
     * 地形出现一条竖直断层（经典 Far Lands 的巨墙正是坐标跳变/外推突变造成的）。
     * 注意它是<b>刻意的不连续</b>，跳变线是坐标的纯函数，与区块边界无关，不会产生接缝。
     */
    public static double sawtoothWarp(double u, double strength, double period) {
        if (strength == 0.0 || period == 0.0) {
            return 0.0;
        }
        double phase = u / period;
        double fraction = phase - Math.floor(phase);
        return strength * (fraction - 0.5);
    }

    /**
     * 竖直分层锯齿：把"采样的高度"按 {@code period} 分段、每段整体偏移，
     * 段与段之间跳变 {@code strength} 格。
     *
     * <p>这是老版本边境之地出现<b>夹层/镂空/悬空石板</b>的机制：高度采样被折回后，
     * 同一列会同时满足多个"地表"条件（密度零点不止一个），于是石壁被打出空洞与平台。
     *
     * @param y       竖直采样坐标
     * @param phase   相位（由 {@link #layerPhase} 提供，避免所有列在同一高度被切开）
     */
    public static double layerWarp(double y, double strength, double period, double phase) {
        if (strength == 0.0 || period == 0.0) {
            return 0.0;
        }
        double shifted = (y + phase) / period;
        double fraction = shifted - Math.floor(shifted);
        return strength * (fraction - 0.5);
    }

    /** 分层锯齿的位置相位：同一高度在不同 (x,z) 处错开，避免形成整齐的全球横带。 */
    public static double layerPhase(double x, double z) {
        return 32.0 * valueNoise(x, z, 112.0, 0x1B873593);
    }

    /**
     * 墙面阶跃 ∈ {0, 1}：沿 X / Z 各自的低频一维噪声取阈值。
     *
     * <p>这是"原版边境之墙"的现代做法：阈值一侧整片抬高一个档位，
     * 于是墙体是<b>笔直的轴向长墙</b>（沿 X 或 Z 延伸），位置不规则、
     * 高度只有一档——没有多层台阶、也没有渐变，越过墙就是整片高台。
     */
    public static double wallLevel(double x, double z) {
        boolean liftX = valueNoise1D(x, 112.0, 0x9E3779B9) > 0.0;
        boolean liftZ = valueNoise1D(z, 112.0, 0x85EBCA6B) > 0.0;
        return liftX != liftZ ? 1.0 : 0.0;
    }

    /**
     * 含"随高度摆动"的墙面阶跃：墙线的水平位置随 y 缓慢摆动。
     *
     * <p>这是老版本边境之地那种<b>参差崖面 / 叠檐 / 石柱</b>的来源：
     * 抬升量在竖直方向不再恒定，采样剖面随高度被推挤，
     * 崖面就从一张平板变成层层错开的石壁。
     */
    public static double wallLevelAt(double x, double y, double z) {
        return wallLevel(x + wallLeanX(y), z + wallLeanZ(y));
    }

    /**
     * 切片档位：一维低频值噪声量化成 {@code levels} 档（0 .. levels-1）。
     *
     * <p>老版本边境之地的地形就是被"切片"的：同一个原地形沿轴被错开成若干片，
     * 片与片之间是竖直断面（石壁），片内仍是原来的地形（草、土、石头都在）。
     */
    public static int slabLevel(double v, double lattice, int seed, int levels) {
        double n = valueNoise1D(v, lattice, seed);
        double t = (n + 1.0) * 0.5;
        int level = (int) Math.floor(t * levels);
        if (level >= levels) {
            level = levels - 1;
        }
        return level < 0 ? 0 : level;
    }

    /** 墙线在 X 方向的随高度偏移（格），两个频率叠加避免过于规则。 */
    public static double wallLeanX(double y) {
        return 26.0 * Math.sin(y / 41.0 + 0.6) + 9.0 * Math.sin(y / 13.0 + 2.3);
    }

    /** 墙线在 Z 方向的随高度偏移（格）。 */
    public static double wallLeanZ(double y) {
        return 26.0 * Math.sin(y / 37.0 + 2.1) + 9.0 * Math.sin(y / 17.0 + 0.4);
    }

    /** 一维值噪声，返回 [-1,1]。 */
    private static double valueNoise1D(double v, double scale, int seed) {
        double f = v / scale;
        int i0 = (int) Math.floor(f);
        double t = smoothstep(f - i0);
        return lerp(t, hash01(i0, 0, seed), hash01(i0 + 1, 0, seed));
    }

    /** 单八度 2D 值噪声，返回 [-1,1]。 */
    private static double valueNoise(double x, double z, double scale, int seed) {
        double fx = x / scale;
        double fz = z / scale;
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double tx = smoothstep(fx - x0);
        double tz = smoothstep(fz - z0);
        double v00 = hash01(x0, z0, seed);
        double v10 = hash01(x0 + 1, z0, seed);
        double v01 = hash01(x0, z0 + 1, seed);
        double v11 = hash01(x0 + 1, z0 + 1, seed);
        return lerp(tz, lerp(tx, v00, v10), lerp(tx, v01, v11));
    }

    private static double hash01(int x, int z, int seed) {
        int h = x * 374761393 + z * 668265263 + seed;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0x7FFFFFFF) / (double) 0x7FFFFFFF * 2.0 - 1.0;
    }

    public static final double TWO_PI = Math.PI * 2.0;

    // ------------------------------------------------------------------
    // 经典 Far Lands 参考数学（复刻旧版行为，仅作对照/实验）
    // ------------------------------------------------------------------

    /** 经典 3D 噪声在方块空间上的增量：684.412 / 4。 */
    public static final double CLASSIC_NOISE_UNITS_PER_BLOCK = 171.103;

    /** 经典 Far Lands 起始距离：2^31 / 171.103 ≈ 12,550,824 格。 */
    public static final double CLASSIC_OVERFLOW_DISTANCE = 12_550_824.0;

    /** 经典修复使用的取模周期：2^25。 */
    public static final double CLASSIC_WRAP_PERIOD = 33_554_432.0;

    /**
     * Java 的 double → int 转换语义：超出 int 范围时饱和（不是回绕）。
     * 这正是经典 Far Lands 里格点索引"卡住"的原因。
     */
    public static int saturatingInt(double value) {
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    /** 经典噪声的整数格点索引（饱和后）。 */
    public static double classicLatticeIndex(double noiseCoordinate) {
        return saturatingInt(Math.floor(noiseCoordinate));
    }

    /** 经典噪声的插值权重：正常时 ∈[0,1)，越过 2^31 后随坐标线性增长。 */
    public static double classicFraction(double noiseCoordinate) {
        return noiseCoordinate - classicLatticeIndex(noiseCoordinate);
    }

    /** 经典"修复"（Beta 1.8+ / 现代 maintainPrecision）：把坐标折回 ±2^24 区间。 */
    public static double classicWrapped(double noiseCoordinate) {
        return noiseCoordinate - Math.floor(noiseCoordinate / CLASSIC_WRAP_PERIOD + 0.5) * CLASSIC_WRAP_PERIOD;
    }

    /** 经典机制下一个方块造成的噪声坐标增量（用于复盘溢出距离）。 */
    public static double classicNoiseCoordinate(double blockCoordinate) {
        return blockCoordinate * CLASSIC_NOISE_UNITS_PER_BLOCK;
    }
}
