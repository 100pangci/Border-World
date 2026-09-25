package com.borderworld.core;

/**
 * 正常区几何（零 Minecraft 依赖）。
 *
 * <p>以出生区块中心为原点，用<b>切比雪夫距离</b>划出方形区域：
 * <pre>
 *   d ≤ innerRadius                  → NORMAL      完全原版
 *   innerRadius &lt; d &lt; outerRadius    → TRANSITION  畸变随 smoothstep 连续增强
 *   d ≥ outerRadius                  → FARLANDS    完整边境之地
 * </pre>
 *
 * <p>注意：畸变系数是<b>位置（方块坐标）的连续函数</b>，而不是"每个区块一个值"，
 * 因此相邻区块在共享采样点上得到完全相同的系数，不会出现区块级接缝。
 */
public final class NormalRegion {

    /** 区域分类。 */
    public enum Zone {
        /** 完全原版。 */
        NORMAL,
        /** 过渡区：畸变程度 0 → 1 平滑增长。 */
        TRANSITION,
        /** 完整边境之地。 */
        FARLANDS
    }

    private final double centerX;
    private final double centerZ;
    private final double innerRadius;
    private final double transitionWidth;
    private final double outerRadius;

    public NormalRegion(double centerX, double centerZ, double innerRadius, double transitionWidth) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.innerRadius = Math.max(0.0, innerRadius);
        this.transitionWidth = Math.max(0.0, transitionWidth);
        this.outerRadius = this.innerRadius + this.transitionWidth;
    }

    public double centerX() {
        return this.centerX;
    }

    public double centerZ() {
        return this.centerZ;
    }

    public double innerRadius() {
        return this.innerRadius;
    }

    public double transitionWidth() {
        return this.transitionWidth;
    }

    public double outerRadius() {
        return this.outerRadius;
    }

    /** 到区域中心的切比雪夫距离。 */
    public double distanceTo(double x, double z) {
        return WorldgenMath.chebyshevDistance(x - this.centerX, z - this.centerZ);
    }

    public Zone zoneAt(double x, double z) {
        double d = distanceTo(x, z);
        if (d <= this.innerRadius) {
            return Zone.NORMAL;
        }
        return d >= this.outerRadius ? Zone.FARLANDS : Zone.TRANSITION;
    }

    public boolean isNormal(double x, double z) {
        return distanceTo(x, z) <= this.innerRadius;
    }

    /** 由距离直接求畸变系数（0 = 原版，1 = 完整畸变）。 */
    public double distortionForDistance(double distance) {
        if (distance <= this.innerRadius) {
            return 0.0;
        }
        if (distance >= this.outerRadius) {
            return 1.0;
        }
        if (this.transitionWidth <= 0.0) {
            return 1.0;
        }
        return WorldgenMath.smoothstep((distance - this.innerRadius) / this.transitionWidth);
    }

    /** 畸变系数：0 = 原版，1 = 完整边境之地。 */
    public double distortionAt(double x, double z) {
        return distortionForDistance(distanceTo(x, z));
    }
}
