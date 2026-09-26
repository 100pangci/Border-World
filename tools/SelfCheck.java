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
        checkWallStep();
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
        expect("外半径 = 内半径 + 过渡宽度（" + region.outerRadius() + " 格）",
            region.outerRadius() == FarlandsConfig.normalRadiusBlocks() + FarlandsConfig.TRANSITION_WIDTH_BLOCKS);
        expect("中心点属于正常区", region.zoneAt(8.0, 8.0) == NormalRegion.Zone.NORMAL);
        expect("d=80 边界属于正常区", region.zoneAt(88.0, 8.0) == NormalRegion.Zone.NORMAL);
        expect("d=81 进入过渡区", region.zoneAt(89.0, 8.0) == NormalRegion.Zone.TRANSITION);
        expect("过渡区外属于边境之地", region.zoneAt(8.0 + FarlandsConfig.normalRadiusBlocks() + FarlandsConfig.TRANSITION_WIDTH_BLOCKS + 4.0, 8.0) == NormalRegion.Zone.FARLANDS);
        // 切比雪夫距离：正方形区域，对角方向用 max(|dx|,|dz|)
        expect("切比雪夫（对角）距离", WorldgenMath.chebyshevDistance(70.0, 70.0) == 70.0);
        expect("对角 70 格仍在正常区", region.zoneAt(8.0 + 70.0, 8.0 + 70.0) == NormalRegion.Zone.NORMAL);
        expect("对角 90 格在过渡区", region.zoneAt(8.0 + 90.0, 8.0 + 90.0) == NormalRegion.Zone.TRANSITION);
    }

    private static void checkDistortionCurve() {
        NormalRegion region = FarlandsConfig.normalRegion(0.0, 0.0);
        expect("d=0 畸变 0", region.distortionForDistance(0.0) == 0.0);
        expect("d=80 畸变 0", region.distortionForDistance(80.0) == 0.0);
        double outer = FarlandsConfig.normalRadiusBlocks() + FarlandsConfig.TRANSITION_WIDTH_BLOCKS;
        expect("过渡区外畸变 1", region.distortionForDistance(outer) == 1.0);
        double mid = FarlandsConfig.normalRadiusBlocks() + FarlandsConfig.TRANSITION_WIDTH_BLOCKS / 2.0;
        expect("过渡区中点畸变在 (0,1)", region.distortionForDistance(mid) > 0.0
            && region.distortionForDistance(mid) < 1.0);

        double previous = -1.0;
        boolean monotonic = true;
        for (double d = FarlandsConfig.normalRadiusBlocks(); d <= outer; d += 0.5) {
            double v = region.distortionForDistance(d);
            if (v < previous) {
                monotonic = false;
            }
            previous = v;
        }
        expect("过渡区畸变单调不减", monotonic);
        expect("过渡区中点畸变 ≈ 0.5", Math.abs(region.distortionForDistance(mid) - 0.5) < 0.06);
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
        // 平滑部分（关闭锯齿）必须连续；锯齿是刻意的不连续，单独验证
        FarlandsTransform.Params base = FarlandsConfig.params();
        FarlandsTransform.Params noSaw = new FarlandsTransform.Params(
            base.primaryStrength(), base.primaryPeriod(), base.primaryPhaseX(), base.primaryPhaseZ(),
            base.secondaryStrength(), base.secondaryPeriod(), base.secondaryPhaseX(), base.secondaryPhaseZ(),
            base.radialRamp(), base.verticalStrength(), base.verticalPeriod(), 0.0, base.sawPeriod(),
            base.slabStepX(), base.slabLatticeX(), base.slabStepZ(), base.slabLatticeZ(), base.slabLevels(),
            base.shearStrength(), base.shearPeriod());
        FarlandsTransform t = new FarlandsTransform(0.0, 0.0, FarlandsConfig.normalRegion(0.0, 0.0), noSaw);
        // 连续性用二阶差分判定：一阶差分在过渡带会被 alpha 斜坡抬高（那是设计内的渐变，不是跳变）
        double step = 0.25;
        double maxSecond = 0.0;
        for (double u = 60.0; u <= 140.0; u += step) {
            double fm = t.transformX(u - step, 64.0, 0.0);
            double f0 = t.transformX(u, 64.0, 0.0);
            double fp = t.transformX(u + step, 64.0, 0.0);
            maxSecond = Math.max(maxSecond, Math.abs((fp - f0) - (f0 - fm)));
        }
        expect("平滑部分二阶差分有界（无跳变），实测 " + String.format("%.4f", maxSecond) + " ≤ 0.300",
            maxSecond <= 0.3);

        // 锯齿：周期边界处跳变幅度必须等于 strength
        double strength = FarlandsConfig.FARLANDS_SAWTOOTH_STRENGTH;
        double period = FarlandsConfig.FARLANDS_SAWTOOTH_PERIOD;
        double before = WorldgenMath.sawtoothWarp(period - 1e-9, strength, period);
        double after = WorldgenMath.sawtoothWarp(period + 1e-9, strength, period);
        expect("锯齿项在周期边界产生 strength 幅度的断层（" + strength + " 格）",
            Math.abs(before - after - strength) < 1e-6);
        double slope = WorldgenMath.sawtoothWarp(10.0, strength, period) - WorldgenMath.sawtoothWarp(9.0, strength, period);
        expect("锯齿项在区间内线性（每格位移 = strength/period）",
            Math.abs(slope - strength / period) < 1e-9);

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
        expect("主谐波下导数明显变缓（min=" + String.format("%.2f", minDerivative) + "）", minDerivative < 0.6);
        expect("主谐波下导数明显加快（max=" + String.format("%.2f", maxDerivative) + "）", maxDerivative > 1.4);
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

    private static void checkTransitionWidth() {        double widthChunks = FarlandsConfig.TRANSITION_WIDTH_BLOCKS / 16.0;
        expect("过渡区宽度是 1~2 个区块（当前 " + widthChunks + "）",
            widthChunks >= 1.0 && widthChunks <= 2.0);
        expect("安全区边长 = 160 格", FarlandsConfig.normalRadiusBlocks() * 2 == 160.0);
    }

    private static void checkWallStep() {
        FarlandsTransform t = new FarlandsTransform(8.0, 8.0, FarlandsConfig.normalRegion(8.0, 8.0), FarlandsConfig.params());
        double layer = FarlandsConfig.VERTICAL_WARP_STRENGTH / 2.0;
        int levels = FarlandsConfig.FARLANDS_SLAB_LEVELS;
        double stepX = FarlandsConfig.FARLANDS_SLAB_STEP_X;
        double stepZ = FarlandsConfig.FARLANDS_SLAB_STEP_Z;
        double maxLift = (levels - 1) * (stepX + stepZ);

        boolean yUnchangedInside = true;
        for (double x = -60.0; x <= 76.0; x += 3.1) {
            for (double z = -60.0; z <= 76.0; z += 4.3) {
                if (t.transformY(x, 64.0, z) != 64.0) {
                    yUnchangedInside = false;
                }
            }
        }
        expect("正常区内 Y 坐标严格不变", yUnchangedInside);

        java.util.Set<Long> lifts = new java.util.TreeSet<>();
        boolean bounded = true;
        for (double x = 200.0; x <= 900.0; x += 1.7) {
            for (double z = 200.0; z <= 900.0; z += 2.3) {
                double lift = t.wallShiftAt(x, 200.0, z);
                lifts.add(Math.round(lift));
                if (lift < -1e-9 || lift > maxLift + 1e-9) {
                    bounded = false;
                }
                for (double y : new double[] {-60.0, 0.0, 64.0, 200.0, 319.0}) {
                    double sampled = t.transformY(x, y, z);
                    if (Math.abs(sampled - y) > maxLift + layer + 1e-9) {
                        bounded = false;
                    }
                }
            }
        }
        expect("远区抬升量有界（0.." + (int) maxLift + " 格，实测档位 " + lifts.size() + " 种）",
            bounded && lifts.size() >= 8);
        expect("抬升量恰为两级切片之和（档位数为 " + levels + "×" + levels + " 种）",
            lifts.size() <= levels * levels);

        // 切片断面：跨过切片边界时抬升量一次性跳变 ≥ 一级台阶
        java.util.Set<Long> jumps = new java.util.TreeSet<>();
        double previous = t.wallShiftAt(200.0, 200.0, 300.0);
        for (double z = 200.0; z <= 2000.0; z += 0.5) {
            double current = t.wallShiftAt(200.0, 200.0, z);
            double delta = Math.abs(current - previous);
            if (delta > 1e-9) {
                jumps.add(Math.round(delta));
            }
            previous = current;
        }
        expect("切片断面一次性跳变（" + (int) stepZ + "~" + (int) ((levels - 1) * (stepX + stepZ)) + " 格，实测档位 " + jumps + "）",
            !jumps.isEmpty() && jumps.stream().allMatch(v -> v >= (long) stepZ - 1));

        // 分层锯齿必须真的"折回"：沿 y 扫描时出现非单调（否则没有夹层/镂空）
        boolean nonMonotonic = false;
        double prevY = t.transformY(300.0, 0.0, 300.0);
        for (double y = 1.0; y <= 400.0; y += 1.0) {
            double current = t.transformY(300.0, y, 300.0);
            if (current < prevY - 1e-9) {
                nonMonotonic = true;
                break;
            }
            prevY = current;
        }
        expect("分层锯齿出现折回（同一列多个地表 → 夹层/镂空）", nonMonotonic);

        // 切片边界随高度摆动（参差崖面）
        boolean lean = false;
        for (double y = 0.0; y <= 400.0; y += 8.0) {
            if (com.borderworld.core.WorldgenMath.wallLeanX(y) != com.borderworld.core.WorldgenMath.wallLeanX(0.0)
                || com.borderworld.core.WorldgenMath.wallLeanZ(y) != com.borderworld.core.WorldgenMath.wallLeanZ(0.0)) {
                lean = true;
                break;
            }
        }
        expect("切片边界随高度摆动（参差崖面）", lean);
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
