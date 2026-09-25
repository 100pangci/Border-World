import com.borderworld.config.FarlandsConfig;
import com.borderworld.core.FarlandsTransform;
import com.borderworld.core.NormalRegion;
import com.borderworld.core.WorldgenMath;

/**
 * core/ 纯数学自检（不依赖 Minecraft，可用 javac/java 直接跑）。
 *
 * 编译运行：
 *   javac -d /tmp/opencode/selfcheck \
 *       src/main/java/com/borderworld/core/{WorldgenMath,NormalRegion,FarlandsTransform}.java \
 *       src/main/java/com/borderworld/config/FarlandsConfig.java tools/SelfCheck.java
 *   java -cp /tmp/opencode/selfcheck SelfCheck
 */
public final class SelfCheck {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("== Border World 纯数学自检 ==");
        System.out.println("配置: " + FarlandsConfig.describe());

        checkRegionGeometry();
        checkDistortionCurve();
        checkIdentityInsideNormalRegion();
        checkContinuity();
        checkStallBands();
        checkTransitionWidth();
        checkClassicReferenceMath();

        System.out.println();
        System.out.println("通过 " + passed + "，失败 " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    private static void checkRegionGeometry() {
        NormalRegion region = FarlandsConfig.normalRegion(8.0, 8.0);
        expect("正常区半宽 = 80 格", region.innerRadius() == 80.0);
        expect("外半径 = 112 格", region.outerRadius() == 112.0);
        expect("中心点属于正常区", region.zoneAt(8.0, 8.0) == NormalRegion.Zone.NORMAL);
        expect("d=80 边界属于正常区", region.zoneAt(88.0, 8.0) == NormalRegion.Zone.NORMAL);
        expect("d=81 进入过渡区", region.zoneAt(89.0, 8.0) == NormalRegion.Zone.TRANSITION);
        expect("d=112 属于边境之地", region.zoneAt(120.0, 8.0) == NormalRegion.Zone.FARLANDS);
        // 切比雪夫距离：正方形区域，对角方向用 max(|dx|,|dz|)
        expect("切比雪夫（对角）距离", WorldgenMath.chebyshevDistance(70.0, 70.0) == 70.0);
        expect("对角 70 格仍在正常区", region.zoneAt(8.0 + 70.0, 8.0 + 70.0) == NormalRegion.Zone.NORMAL);
        expect("对角 90 格在过渡区", region.zoneAt(8.0 + 90.0, 8.0 + 90.0) == NormalRegion.Zone.TRANSITION);
    }

    private static void checkDistortionCurve() {
        NormalRegion region = FarlandsConfig.normalRegion(0.0, 0.0);
        expect("d=0 畸变 0", region.distortionForDistance(0.0) == 0.0);
        expect("d=80 畸变 0", region.distortionForDistance(80.0) == 0.0);
        expect("d=112 畸变 1", region.distortionForDistance(112.0) == 1.0);
        expect("d=100 畸变在 (0,1)", region.distortionForDistance(100.0) > 0.0
            && region.distortionForDistance(100.0) < 1.0);

        double previous = -1.0;
        boolean monotonic = true;
        for (double d = 80.0; d <= 112.0; d += 0.5) {
            double v = region.distortionForDistance(d);
            if (v < previous) {
                monotonic = false;
            }
            previous = v;
        }
        expect("过渡区畸变单调不减", monotonic);
        expect("过渡区中点畸变 ≈ 0.5", Math.abs(region.distortionForDistance(96.0) - 0.5) < 0.06);
    }

    private static void checkIdentityInsideNormalRegion() {
        FarlandsTransform t = new FarlandsTransform(8.0, 8.0, FarlandsConfig.normalRegion(8.0, 8.0), FarlandsConfig.params());
        boolean same = true;
        for (double x = -80.0; x <= 96.0; x += 3.7) {
            for (double z = -80.0; z <= 96.0; z += 5.3) {
                if (t.getDistortionFactor(x, z) == 0.0) {
                    if (t.transformX(x, 64.0, z) != x || t.transformY(x, 64.0, z) != 64.0
                        || t.transformZ(x, 64.0, z) != z) {
                        same = false;
                    }
                }
            }
        }
        expect("正常区内变换严格恒等（逐位）", same);

        FarlandsTransform identity = FarlandsTransform.IDENTITY;
        expect("IDENTITY 恒定等", identity.transformX(12345.678, -20.0, -9999.5) == 12345.678);
        expect("IDENTITY 畸变恒 0", identity.getDistortionFactor(5000.0, 5000.0) == 0.0);
    }

    private static void checkContinuity() {
        FarlandsTransform t = new FarlandsTransform(0.0, 0.0, FarlandsConfig.normalRegion(0.0, 0.0), FarlandsConfig.params());
        double step = 0.25;
        double maxAnomaly = 0.0;
        for (double u = 70.0; u <= 130.0; u += step) {
            double prev = t.transformX(u - step, 64.0, 0.0);
            double curr = t.transformX(u, 64.0, 0.0);
            maxAnomaly = Math.max(maxAnomaly, Math.abs((curr - prev) - step));
        }
        // 导数上界 ≈ 1 + primary + secondary + |ramp| = 2.4；步长 0.25 → 允许偏差 0.35
        expect("过渡区边界连续（无跳变），最大偏差=" + String.format("%.4f", maxAnomaly),
            maxAnomaly < 0.35);

        double crossChunk = 0.0;
        for (double u = 88.0; u <= 120.0; u += 1.0) {
            crossChunk = Math.max(crossChunk, Math.abs(t.transformX(u, 64.0, 0.0) - t.transformX(u, 64.0, 0.0)));
        }
        expect("同一坐标重复采样结果一致", crossChunk == 0.0);
    }

    private static void checkStallBands() {
        FarlandsTransform t = new FarlandsTransform(0.0, 0.0, FarlandsConfig.normalRegion(0.0, 0.0), FarlandsConfig.params());
        double minDerivative = Double.MAX_VALUE;
        double maxDerivative = -Double.MAX_VALUE;
        double stallLength = 0.0;
        double stretchLength = 0.0;
        for (double u = 112.0; u <= 112.0 + 9600.0; u += 0.5) {
            double d = t.axisDerivative(u, FarlandsConfig.FARLANDS_PRIMARY_PHASE_X,
                FarlandsConfig.FARLANDS_SECONDARY_PHASE_X);
            minDerivative = Math.min(minDerivative, d);
            maxDerivative = Math.max(maxDerivative, d);
            if (d < 0.25) {
                stallLength += 0.5;
            }
            if (d > 1.5) {
                stretchLength += 0.5;
            }
        }
        double total = 9600.0;
        System.out.printf("  轴向导数 min=%.3f max=%.3f，停滞带(<0.25)占 %.1f%%，强拉伸(>1.5)占 %.1f%%%n",
            minDerivative, maxDerivative, stallLength / total * 100, stretchLength / total * 100);
        expect("存在停滞带（导数可近似为 0）", minDerivative < 0.25);
        expect("存在强拉伸带（导数 > 1.5）", maxDerivative > 1.5);
        expect("远区地形不会整体压缩/膨胀（平均导数 ≈ 1）",
            Math.abs(averageDerivative(t) - 1.0) < 0.05);
    }

    private static double averageDerivative(FarlandsTransform t) {
        double sum = 0.0;
        int n = 0;
        for (double u = 200.0; u <= 10200.0; u += 1.0) {
            sum += t.axisDerivative(u, FarlandsConfig.FARLANDS_PRIMARY_PHASE_X,
                FarlandsConfig.FARLANDS_SECONDARY_PHASE_X);
            n++;
        }
        return sum / n;
    }

    private static void checkTransitionWidth() {
        double widthChunks = FarlandsConfig.TRANSITION_WIDTH_BLOCKS / 16.0;
        expect("过渡区宽度是 1~2 个区块（当前 " + widthChunks + "）",
            widthChunks >= 1.0 && widthChunks <= 2.0);
        expect("安全区边长 = 160 格", FarlandsConfig.normalRadiusBlocks() * 2 == 160.0);
    }

    private static void checkClassicReferenceMath() {
        double boundaryCoord = WorldgenMath.classicNoiseCoordinate(WorldgenMath.CLASSIC_OVERFLOW_DISTANCE);
        System.out.printf("  经典参考：12,550,824 格处噪声坐标 = %.3e（2^31 = %.3e）%n",
            boundaryCoord, (double) Integer.MAX_VALUE);
        expect("经典溢出距离对应噪声坐标 ≈ 2^31",
            Math.abs(boundaryCoord - (double) Integer.MAX_VALUE) / (double) Integer.MAX_VALUE < 0.01);

        // 171.103 的乘积在 12,550,824 格时比 2^31 小约 8 个单位，真正越界发生在 12,550,824.05 格之后
        double overflowCoord = WorldgenMath.classicNoiseCoordinate(12_550_825.0);
        double inside = WorldgenMath.classicFraction(1_000_000.0);
        double outside = WorldgenMath.classicFraction(overflowCoord + 1_000.0);
        expect("溢出前插值权重 ∈ [0,1)", inside >= 0.0 && inside < 1.0);
        expect("溢出后格点索引饱和在 int 上限",
            WorldgenMath.classicLatticeIndex(overflowCoord) == Integer.MAX_VALUE);
        expect("溢出后插值权重线性增长（>900）", outside > 900.0);
        expect("插值权重随距离线性增长",
            Math.abs((WorldgenMath.classicFraction(overflowCoord + 2_000.0) - outside) - 1_000.0) < 1.0);

        double wrapped = WorldgenMath.classicWrapped(overflowCoord * 4);
        expect("经典修复把坐标折回 ±2^24", Math.abs(wrapped) <= 16_777_216.0);

        double harmonic = WorldgenMath.harmonicWarp(0.0, 1.0, 96.0, 0.0);
        expect("谐波调制在原点为 0", harmonic == 0.0);
        expect("谐波调制有界（|warp| ≤ strength·period/2π）",
            Math.abs(WorldgenMath.harmonicWarp(24.0, 1.05, 96.0, 0.0)) <= 1.05 * 96.0 / (2 * Math.PI) + 1e-9);
    }

    // ------------------------------------------------------------------

    private static void expect(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [OK] " + label);
        } else {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }
}
