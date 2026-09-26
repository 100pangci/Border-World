package com.borderworld.core;

/**
 * Far Lands 坐标变换（零 Minecraft 依赖，可移植到任意版本）。
 *
 * <p>设计目标：在现代 noise / density function 体系里复现经典 Far Lands 的
 * <b>数学行为</b>——坐标映射出现"停滞带"（导数 → 0）与"拉伸带"，使地形沿轴被
 * 挤出成平台/巨墙/隧道，同时全部数值保持有界（不会像旧版那样外推到 1e11 量级，
 * 也就不需要改写原版密度管线）。
 *
 * <p>单轴映射（u = 相对出生区块中心的轴向偏移）：
 * <pre>
 *   v(u) = u + Σ  strengthᵢ · (periodᵢ / 2π) · sin(2π·u/periodᵢ + phaseᵢ) + ramp·u
 *   v'(u) = 1 + Σ strengthᵢ · cos(...) + ramp
 * </pre>
 *
 * <p>轴向变换互相独立（x' = F(x)，z' = G(z)），因此会产生沿轴延伸的墙/隧道
 * 以及两轴叠加后的方格状平台——与经典 Far Lands 的形态一致。
 *
 * <p>所有方法都是纯函数；{@link #IDENTITY} 表示"未锚定 / 已禁用"，
 * 直接返回原坐标（保证正常区逐位原版）。
 */
public final class FarlandsTransform {

    /** 变换参数包（与 {@code FarlandsConfig} 一一对应）。 */
    public record Params(
        double primaryStrength,
        double primaryPeriod,
        double primaryPhaseX,
        double primaryPhaseZ,
        double secondaryStrength,
        double secondaryPeriod,
        double secondaryPhaseX,
        double secondaryPhaseZ,
        double radialRamp,
        double verticalStrength,
        double verticalPeriod,
        double sawStrength,
        double sawPeriod,
        double wallHeight
    ) {
        public boolean hasVerticalWarp() {
            return this.verticalStrength != 0.0 && this.verticalPeriod != 0.0;
        }

        public boolean hasSawtooth() {
            return this.sawStrength != 0.0 && this.sawPeriod != 0.0;
        }
    }

    /** 未锚定 / 禁用时的恒等变换。 */
    public static final FarlandsTransform IDENTITY =
        new FarlandsTransform(0.0, 0.0, new NormalRegion(0.0, 0.0, 0.0, 0.0), defaultParams(), true);

    private final double centerX;
    private final double centerZ;
    private final NormalRegion region;
    private final Params params;
    private final boolean identity;

    public FarlandsTransform(double centerX, double centerZ, NormalRegion region, Params params) {
        this(centerX, centerZ, region, params, false);
    }

    private FarlandsTransform(double centerX, double centerZ, NormalRegion region, Params params, boolean identity) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.region = region;
        this.params = params;
        this.identity = identity;
    }

    private static Params defaultParams() {
        return new Params(0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0);
    }

    public boolean isIdentity() {
        return this.identity;
    }

    public NormalRegion region() {
        return this.region;
    }

    public Params params() {
        return this.params;
    }

    public double centerX() {
        return this.centerX;
    }

    public double centerZ() {
        return this.centerZ;
    }

    // ------------------------------------------------------------------
    // 畸变系数
    // ------------------------------------------------------------------

    /**
     * 畸变系数 ∈ [0,1]：0 = 完全原版，1 = 完整边境之地。
     * 基于出生区块中心的切比雪夫距离，过渡带内用 smoothstep 连续过渡。
     */
    public double getDistortionFactor(double x, double z) {
        if (this.identity) {
            return 0.0;
        }
        return this.region.distortionAt(x, z);
    }

    public double getDistortionFactorForDistance(double distance) {
        if (this.identity) {
            return 0.0;
        }
        return this.region.distortionForDistance(distance);
    }

    /**
     * 该列的目标垂直位移（格，正数 = 地形整体抬高）。
     *
     * <p>这是"边境之墙"的目标量：由 {@link WorldgenMath#wallLevel} 给出 0/1 的轴向阶跃，
     * 因此越过墙线时该值会<b>突然</b>从 0 变成 {@code wallHeight}（默认 30 格）。
     * 实际生效方式是 {@link #transformY} 把整条竖直剖面（噪声 + yClampedGradient）
     * 平移该格数：在抬高的区域按 {@code y - shift} 采样，等价于地形整体抬升。
     */
    public double wallShiftAt(double x, double z) {
        if (this.identity || this.params.wallHeight() == 0.0) {
            return 0.0;
        }
        double alpha = getDistortionFactor(x, z);
        if (alpha == 0.0) {
            return 0.0;
        }
        return alpha * this.params.wallHeight() * WorldgenMath.wallLevel(x, z);
    }

    // ------------------------------------------------------------------
    // 坐标变换（用户要求的对外 API）
    // ------------------------------------------------------------------

    /** 变换 X 轴坐标（内部自行计算畸变系数）。 */
    public double transformX(double x, double y, double z) {
        return transformX(x, y, z, getDistortionFactor(x, z));
    }

    /**
     * 变换 Y 轴坐标（内部自行计算畸变系数）。
     *
     * <p>远区按 {@link #wallShiftAt} 把整列地形抬高/压低：越过墙线时位移从 0
     * <b>突然</b>变成 wallHeight 格，形成经典 Far Lands 那种"拔地而起的巨墙"。
     * 返回 {@code y - lift}：在当前位置采样更低处的剖面 = 地形整体抬高 lift 格。
     */
    public double transformY(double x, double y, double z) {
        return transformY(x, y, z, getDistortionFactor(x, z));
    }

    /** 变换 Z 轴坐标（内部自行计算畸变系数）。 */
    public double transformZ(double x, double y, double z) {
        return transformZ(x, y, z, getDistortionFactor(x, z));
    }

    /**
     * 批量采样优化版：调用者已算好畸变系数时使用，避免重复计算距离。
     */
    public double transformX(double x, double y, double z, double alpha) {
        if (this.identity || alpha == 0.0) {
            return x;
        }
        double u = x - this.centerX;
        double warped = this.centerX + farAxis(u, this.params.primaryPhaseX(), this.params.secondaryPhaseX());
        return x + alpha * (warped - x);
    }

    public double transformY(double x, double y, double z, double alpha) {
        if (this.identity || alpha == 0.0) {
            return y;
        }
        double displacement = 0.0;
        if (this.params.wallHeight() != 0.0) {
            // 取负：采样点下移 N 格 = 该列地形整体抬高 N 格
            displacement -= this.params.wallHeight() * WorldgenMath.wallLevel(x, z);
        }
        if (this.params.hasVerticalWarp()) {
            displacement += WorldgenMath.harmonicWarp(y, this.params.verticalStrength(), this.params.verticalPeriod(), 0.0);
        }
        return y + alpha * displacement;
    }

    public double transformZ(double x, double y, double z, double alpha) {
        if (this.identity || alpha == 0.0) {
            return z;
        }
        double u = z - this.centerZ;
        double warped = this.centerZ + farAxis(u, this.params.primaryPhaseZ(), this.params.secondaryPhaseZ());
        return z + alpha * (warped - z);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 单轴 Far Lands 映射：u → v(u)。 */
    private double farAxis(double u, double primaryPhase, double secondaryPhase) {
        double v = u;
        v += WorldgenMath.harmonicWarp(u, this.params.primaryStrength(), this.params.primaryPeriod(), primaryPhase);
        v += WorldgenMath.harmonicWarp(u, this.params.secondaryStrength(), this.params.secondaryPeriod(), secondaryPhase);
        v += this.params.radialRamp() * u;
        v += WorldgenMath.sawtoothWarp(u, this.params.sawStrength(), this.params.sawPeriod());
        return v;
    }

    /** 单轴映射的导数（用于自检/调参分析）。 */
    public double axisDerivative(double u, double primaryPhase, double secondaryPhase) {
        double d = 1.0 + this.params.radialRamp();
        d += WorldgenMath.harmonicDerivative(u, this.params.primaryStrength(), this.params.primaryPeriod(), primaryPhase);
        d += WorldgenMath.harmonicDerivative(u, this.params.secondaryStrength(), this.params.secondaryPeriod(), secondaryPhase);
        return d;
    }

    @Override
    public String toString() {
        if (this.identity) {
            return "FarlandsTransform[identity]";
        }
        return "FarlandsTransform[center=(" + this.centerX + ", " + this.centerZ
            + "), normal=" + this.region.innerRadius() + ", transition=" + this.region.transitionWidth()
            + ", primary=" + this.params.primaryStrength() + "@" + this.params.primaryPeriod()
            + ", secondary=" + this.params.secondaryStrength() + "@" + this.params.secondaryPeriod()
            + ", ramp=" + this.params.radialRamp()
            + ", vertical=" + this.params.verticalStrength() + "@" + this.params.verticalPeriod()
            + ", sawtooth=" + this.params.sawStrength() + "@" + this.params.sawPeriod()
            + ", wallHeight=" + this.params.wallHeight() + "]";
    }
}
