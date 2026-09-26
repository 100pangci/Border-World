package com.borderworld.worldgen;

import com.borderworld.core.FarlandsTransform;
import com.borderworld.core.WorldgenMath;
import com.borderworld.core.SpawnRegion;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * 旧版边境之地机制在现代生成器上的复现（核心）。
 *
 * <p><b>旧版为什么会这样</b>（Beta 1.7.3 及更早的地形噪声）：
 * <pre>
 *   ① 坐标 → 32 位整数（溢出时夹取到 ±(2^31-1)）
 *   ② 减 1 保证向下取整
 *   ③ 原坐标 − 整数 = 插值余数
 * </pre>
 * 一旦坐标超过 ±2^31（低噪声约 12,550,824 格处），<b>整数索引被钉死</b>：
 * 该轴上永远取同一个格点值（→ 老版本"笔直不变的隧道"），而余数继续以
 * 171.103/格 增长 → 插值退化为外推，输出增长到 10^11 量级，
 * <b>完全压过所有其它项</b>——Minecraft Wiki 的原话："effectively passing only
 * the sign of this one noise function through"（实际只剩这个噪声的符号）。
 *
 * <p>本类在现代密度管线里复现同一件事：
 * <ul>
 *   <li><b>坐标钉死</b>：超出安全区的水平坐标被夹在安全区边界（
 *       {@link FarlandsTransform#overflowX}/{@link FarlandsTransform#overflowZ}），
 *       于是该轴上的噪声不再变化 → 老版本那种沿轴无限的"隧道"；</li>
 *   <li><b>只留符号</b>：远区密度被替换成 {@code sign(base_3d_noise) × BIG}，
 *       BIG 远大于原版挤压（squeeze）范围，于是地形形状完全由这一个噪声决定
 *       → 海绵状石壁、镂空、竖井，与其它气候/地形项无关。</li>
 * </ul>
 *
 * <p>过渡带里用畸变系数 α 与原版密度插值，因此边界处是"老版本那种突然出现的石壁"。
 */
public final class OverflowDensityFunction implements DensityFunction {

    /** 只保留符号后赋予的密度量级：远大于 squeeze 的 ±0.458，确保主导地形。 */
    private static final double BINARY_MAGNITUDE = 1.0;

    /** 海绵图案的尺度（格）：与老版本边境之地里孔洞的尺度相当。 */
    private static final double SPONGE_SCALE = 32.0;
    private static final int SPONGE_SEED = 0x5BD1E995;

    private final DensityFunction delegate;

    public OverflowDensityFunction(DensityFunction delegate) {
        this.delegate = delegate;
    }

    @Override
    public double sample(DensityFunction.NoisePos pos) {
        FarlandsTransform transform = SpawnRegion.transform();
        int x = pos.blockX();
        int y = pos.blockY();
        int z = pos.blockZ();

        if (transform.isIdentity()) {
            return this.delegate.sample(pos);
        }
        double alpha = transform.getDistortionFactor(x, z);
        if (alpha == 0.0) {
            // 正常区：原样，保证与原版逐位一致
            return this.delegate.sample(pos);
        }

        // ① 坐标钉死：溢出轴上的采样坐标被夹在安全区边界（模拟 int 饱和）
        int overflowX = transform.overflowX(x);
        int overflowZ = transform.overflowZ(z);

        // ② 只留符号：溢出后的地形完全由一个噪声的符号决定（旧版真实行为）
        double sampled = WorldgenMath.spongeNoise(overflowX, y, overflowZ, SPONGE_SCALE, SPONGE_SEED);
        double binary = sampled >= 0.0 ? BINARY_MAGNITUDE : -BINARY_MAGNITUDE;

        // ③ 用 α 与原版密度插值（边界处是突然出现的石壁）
        double original = this.delegate.sample(pos);
        return original + alpha * (binary - original);
    }

    @Override
    public void fill(double[] densities, DensityFunction.EachApplier applier) {
        applier.fill(densities, this);
    }

    @Override
    public DensityFunction apply(DensityFunction.DensityFunctionVisitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public double minValue() {
        return Math.min(this.delegate.minValue(), -BINARY_MAGNITUDE);
    }

    @Override
    public double maxValue() {
        return Math.max(this.delegate.maxValue(), BINARY_MAGNITUDE);
    }

    @Override
    public CodecHolder<? extends DensityFunction> getCodecHolder() {
        return this.delegate.getCodecHolder();
    }

    @Override
    public String toString() {
        return "OverflowDensityFunction[" + this.delegate + "]";
    }
}
