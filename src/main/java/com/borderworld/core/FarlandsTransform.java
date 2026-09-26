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
        double slabStepX,
        double slabLatticeX,
        double slabStepZ,
        double slabLatticeZ,
        int slabLevels,
        double shearStrength,
        double shearPeriod,
        double layerShiftStrength,
        double layerShiftPeriod,
        double verticalAmplify,
        double verticalPivot,
        double warp3dStrength,
        double warp3dScale,
        double stackPeriod,
        double cornerDiagonal,
        double cornerPeriodSwing,
        double layerOffset,
        double axisPin
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
        return new Params(0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 1, 0.0, 1.0, 0.0, 1.0, 1.0, 64.0, 0.0, 56.0, 0.0, 0.0, 0.0, 0.0, 0.0);
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
        return wallShiftAt(x, 64.0, z);
    }

    /**
     * 该列（该高度）的目标垂直位移（格，正数 = 地形整体抬高）。
     *
     * <p>老版本边境之地的形态来源：原地形沿轴被<b>切片</b>，每片整体错开不同高度
     * （沿 Z 的细切片 + 沿 X 的粗台阶，两级叠加），片与片之间是竖直石壁；
     * 片内仍是原来的地形（材质/形状不变）。最高几片会顶到建造上限被切平。
     *
     * <p>切片边界随高度摆动（{@link WorldgenMath#wallLeanX}），所以崖面参差不齐。
     */
    public double wallShiftAt(double x, double y, double z) {
        if (this.identity) {
            return 0.0;
        }
        double alpha = getDistortionFactor(x, z);
        if (alpha == 0.0) {
            return 0.0;
        }
        int levels = Math.max(1, this.params.slabLevels());
        double levelX = WorldgenMath.slabLevel(
            x + WorldgenMath.wallLeanX(y), this.params.slabLatticeX(), 0x2545F491, levels);
        double levelZ = WorldgenMath.slabLevel(
            z + WorldgenMath.wallLeanZ(y), this.params.slabLatticeZ(), 0x51ED270B, levels);
        // 小尺度起伏：墙面/台面被揉碎成参差的石堆，而不是光滑平面（CHAOS_RANGE = 0 时关闭）
        double chaos = com.borderworld.config.FarlandsConfig.CHAOS_RANGE <= 0.0
            ? 0.0
            : WorldgenMath.chaosLift(x, z) * (com.borderworld.config.FarlandsConfig.CHAOS_RANGE / 20.0);
        return levelX * this.params.slabStepX() + levelZ * this.params.slabStepZ() + chaos;
    }

    // ------------------------------------------------------------------
    // 坐标变换（用户要求的对外 API）
    // ------------------------------------------------------------------

    /**
     * 溢出坐标（X）：超出安全区的坐标被"钉死"在安全区边界，
     * 对应旧版噪声坐标转 int 时的饱和（该轴上噪声不再变化 → 沿轴无限的隧道）。
     */
    public int overflowX(double x) {
        double limit = this.region.innerRadius();
        double u = x - this.centerX;
        if (u > limit) {
            u = limit;
        } else if (u < -limit) {
            u = -limit;
        }
        return (int) Math.round(this.centerX + u);
    }

    /** 溢出坐标（Z），与 {@link #overflowX} 同理。 */
    public int overflowZ(double z) {
        double limit = this.region.innerRadius();
        double u = z - this.centerZ;
        if (u > limit) {
            u = limit;
        } else if (u < -limit) {
            u = -limit;
        }
        return (int) Math.round(this.centerZ + u);
    }

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
        // ① 整数饱和（可调）：旧版"索引钉死"只发生在低频地形骨架上，
        //    本地那块地的地表材质/起伏仍是本地的 → 因此默认 axisPin=0（完全跟随本地地形），
        //    需要旧版那种"沿轴无限隧道"时把 FARLANDS_AXIS_PIN 调向 1。
        if (this.params.axisPin() > 0.0) {
            double pinned = pin(x, this.centerX, this.region.innerRadius());
            x = x + this.params.axisPin() * (pinned - x);
        }
        // ★ 每层各自横向错位：旧版 Corner Far Lands 的层是一块块"错开的地皮"（砖墙式错位），
        //   不是上下对齐的千层饼
        x += layerOffsetX(x, y, z);
        double u = x - this.centerX;
        double warped = this.centerX + farAxis(u, this.params.primaryPhaseX(), this.params.secondaryPhaseX());
        double layerShift = WorldgenMath.layerShiftX(
            y, this.params.layerShiftPeriod(), this.params.layerShiftStrength());
        return x + alpha * ((warped - x) + shearX(y) + layerShift);
    }

    /** 随高度倾斜的剪切（X 方向）：不同高度的石壁错开 → 悬挑/空隙。 */
    private double shearX(double y) {
        if (this.params.shearStrength() == 0.0 || this.params.shearPeriod() == 0.0) {
            return 0.0;
        }
        return this.params.shearStrength() * Math.sin(y / this.params.shearPeriod() + 0.9);
    }

    /** 随高度倾斜的剪切（Z 方向）。 */
    private double shearZ(double y) {
        if (this.params.shearStrength() == 0.0 || this.params.shearPeriod() == 0.0) {
            return 0.0;
        }
        return this.params.shearStrength() * Math.sin(y / this.params.shearPeriod() + 3.1);
    }

    public double transformY(double x, double y, double z, double alpha) {
        if (this.identity || alpha == 0.0) {
            return y;
        }
        // 竖直方向不做渐变（硬开关）：正常区外直接"换一套地形"，
        // 这样墙面才是突然出现的峭壁，而不是过渡带里的阶梯斜坡。
        // 取负：采样点下移 N 格 = 该片地形整体抬高 N 格（切片边界随高度摆动 → 参差崖面）
        double lift = wallShiftAt(x, y, z);
        // 竖直放大：绕 pivot 把原地形的起伏放大 amplify 倍
        // （平地 → 丘陵，小坡 → 巨崖；这是"地形形状变极端"的来源，光靠平移做不出来）
        double amplify = 1.0 + alpha * (this.params.verticalAmplify() - 1.0);
        double pivot = this.params.verticalPivot();
        double sampledY = pivot + (y - lift - pivot) / amplify;
        if (this.params.hasVerticalWarp()) {
            // 竖直分层锯齿：产生夹层 / 镂空 / 悬空石板（老版本边境之地的外观特征）
            sampledY += WorldgenMath.layerWarp(
                y, this.params.verticalStrength(), this.params.verticalPeriod(), WorldgenMath.layerPhase(x, z));
        }
        if (this.params.stackPeriod() > 0.0) {
            // ★ 旧版边境之地的核心形态：竖直采样坐标"折返"
            //   同一段地形沿 Y 一层层重复堆叠（Wiki：layers of terrain stack on top of
            //   another repeatedly until it reaches the height limit）
            double base = this.params.verticalPivot();
            // ★ 角落（Corner Far Lands，两轴同时溢出）：
            //   旧版结构只取决于"两轴超出量的比值"（Wiki: consistent when the ratio ... is kept
            //   the same），因此层理是从角落放射出去的直线；层厚也随方向变化，
            //   表现为 Wiki 说的 layers "fusing together and splitting every so often"。
            double ratio = cornerRatio(x, z);
            double swing = 1.0 + this.params.cornerPeriodSwing() * (ratio - 0.5);
            double period = this.params.stackPeriod() * swing;
            double phase = this.params.cornerDiagonal() * (ratio - 0.5) * 2.0;
            double rel = sampledY - base + phase;
            sampledY = base + rel - Math.floor(rel / period) * period;
        }
        if (this.params.warp3dStrength() != 0.0) {
            // 3D 噪声位移：把采样高度整片搅乱 → 实心石体里从顶到底全是虚实相间的孔洞
            // （用"钉死"的 x/z 采样：孔洞图案沿溢出轴不变 = 老版本的隧道感）
            int ox = overflowX(x);
            int oz = overflowZ(z);
            sampledY += this.params.warp3dStrength()
                * WorldgenMath.spongeNoise(ox, y, oz, this.params.warp3dScale(), 0x9E3779B9);
        }
        return sampledY;
    }

    public double transformZ(double x, double y, double z, double alpha) {
        if (this.identity || alpha == 0.0) {
            return z;
        }
        // ① 整数饱和（Z 轴同理，默认关闭）
        if (this.params.axisPin() > 0.0) {
            double pinned = pin(z, this.centerZ, this.region.innerRadius());
            z = z + this.params.axisPin() * (pinned - z);
        }
        z += layerOffsetZ(x, y, z);
        double u = z - this.centerZ;
        double warped = this.centerZ + farAxis(u, this.params.primaryPhaseZ(), this.params.secondaryPhaseZ());
        double layerShift = WorldgenMath.layerShiftZ(
            y, this.params.layerShiftPeriod(), this.params.layerShiftStrength());
        return z + alpha * ((warped - z) + shearZ(y) + layerShift);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 角落比值 ∈ [0,1]：两轴"超出安全区的距离"之比。
     *
     * <p>旧版 Corner Far Lands 的地形只取决于这个比值（沿从角落射出的直线恒定），
     * 因此层理会呈放射状斜线；四个象限因取绝对值而天然镜像（Wiki 亦如此记载）。
     */
    public double cornerRatio(double x, double z) {
        double limit = this.region.innerRadius();
        double ux = Math.abs(x - this.centerX) - limit;
        double uz = Math.abs(z - this.centerZ) - limit;
        if (ux < 0.0) {
            ux = 0.0;
        }
        if (uz < 0.0) {
            uz = 0.0;
        }
        double sum = ux + uz;
        return sum <= 1e-9 ? 0.5 : ux / sum;
    }

    /** 该世界高度所在"层"的编号（与世界里的层一一对应）。 */
    private int layerIndex(double x, double y, double z) {
        double period = this.params.stackPeriod();
        if (period <= 0.0) {
            return 0;
        }
        double lift = wallShiftAt(x, y, z);
        return (int) Math.floor((y - lift - this.params.verticalPivot()) / period);
    }

    /** 每层各自的横向错位（X），旧版角落"错开的地皮"的来源。 */
    private double layerOffsetX(double x, double y, double z) {
        if (this.params.layerOffset() == 0.0 || this.params.stackPeriod() <= 0.0) {
            return 0.0;
        }
        return this.params.layerOffset() * 0.5 * WorldgenMath.hashNoise(layerIndex(x, y, z), 0x27D4EB2F);
    }

    /** 每层各自的横向错位（Z）。 */
    private double layerOffsetZ(double x, double y, double z) {
        if (this.params.layerOffset() == 0.0 || this.params.stackPeriod() <= 0.0) {
            return 0.0;
        }
        return this.params.layerOffset() * 0.5 * WorldgenMath.hashNoise(layerIndex(x, y, z), 0x165667B1);
    }

    /** 把坐标钉死在中心 ±limit 以内（对应旧版噪声坐标的 int 饱和）。 */
    private static double pin(double value, double center, double limit) {
        double u = value - center;
        if (u > limit) {
            return center + limit;
        }
        if (u < -limit) {
            return center - limit;
        }
        return value;
    }

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
            + ", slabStepX=" + this.params.slabStepX() + "@" + this.params.slabLatticeX()
            + ", slabStepZ=" + this.params.slabStepZ() + "@" + this.params.slabLatticeZ()
            + ", slabLevels=" + this.params.slabLevels() + "]";
    }
}
