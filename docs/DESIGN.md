# Border World — 设计文档

> Fabric 1.21.1 / Java 21 · 出生点为中心的"正常区 + 过渡区 + 边境之地"

## 1. 经典 Far Lands 成因分析（事实基础）

经典 Far Lands（Beta 1.7.3 及更早）的成因是一处**噪声格点整数溢出**，链条如下：

1. 世界生成调用 3D 噪声（"low/high noise"）时，噪声坐标每格增加 `171.103`
   （= 684.412 / 4；噪声每 4 格采样一次，684.412 是采样步长上的倍率）。
2. 噪声实现（Perlin）把坐标拆成 **整数格点 + [0,1) 小数**：
   ```text
   i = (int) floor(n);        // 32 位整数
   frac = n - i;              // 期望落在 [0,1)
   value = lerp(grad[i & 255], grad[...], frac)
   ```
3. `2^31 / 171.103 ≈ 12,550,824`。超过该距离后，`floor(n)` 超出 int 范围，
   Java 的 double→int 转换**饱和**在 `Integer.MAX_VALUE`：
   - 格点索引永远是 `2^31-1`（`& 255` 后恒为 255）→ 梯度表不再变化；
   - `frac` 不再落在 [0,1)，而是**随世界坐标线性增长**（每个八度按自己的频率增长）。
   - 插值退化为**线性外推**：噪声值被一个量级 1e11 的项淹没，
     最终地形只剩"某一个噪声的符号"在起作用 → 巨墙、隧道、平面、浮空板块。
4. 八度越低频率越高 → 溢出距离按 12.5M / 25M / 50M… 分级出现，
   所以 Far Lands 有"逐级恶化"的结构（Far Lands → Farther Lands → 更远）。
5. 1.8 的官方修复 = 采样前对噪声坐标取 `mod 2^25`（整数 x 永不溢出）。
   1.21.1 中该修复在噪声采样链路里（见 `ChunkNoiseSampler` / `NormalNoise` 侧实现）。

**官方现代实验（2026，参考）**：`-DMC_DEBUG_ENABLE_FARLANDS` 只还原了"取消 mod 修复"，
但在现代 Overworld 会因 `rangeChoice(-1e6, 1.5625)` 门控把异常值裁掉，
官方 bug 报告 MC-311599 确认它**不再产生经典外观**。
→ 结论：直接复刻旧机制在 1.21.1 不可行，必须按用户要求设计"数学/视觉行为相近"的现代实现。

## 2. 现代实现思路（本 Mod 方案）

### 2.1 核心类比

| 经典机制 | 本 Mod 的现代类比 |
| --- | --- |
| 噪声坐标在格点间"停滞"（索引饱和），插值权重线性漂移 | 让噪声坐标相对世界坐标出现**导数为 0 的停滞带**（`d v/d u = 0`） |
| 停滞后进入外推 → 局部数值剧变 | 停滞带边界处世界侧梯度 → ∞，形成**垂直巨墙** |
| 停滞带内噪声沿该轴不变 → 长隧道/平面 | 停滞带内地形沿该轴被**拉伸/挤出** → 平台、隧道 |
| 线性外推无界（1e11），淹没其他项 | 用**有界周期相位调制**替代无界外推（sin 调制），保留"停滞+剧烈变化"的结构但不产生 inf/NaN |
| 多八度在不同距离逐级溢出 | 多个不同周期的调制项叠加（主谐波 + 次谐波），形成不规则格状 |
| 溢出只发生在一个轴 → 沿轴挤出 | 变换**逐轴分离**（`x' = F(x)`, `z' = G(z)`），两轴各自调制 → 网格状墙体 |

数学形式（单轴，相对出生区块中心的偏移 `u`）：

```text
v(u) = u + A1 * (P1/2π) * sin(2π u / P1 + φ1)
         + A2 * (P2/2π) * sin(2π u / P2 + φ2)
         + R * u                                  (可选径向斜坡)

d v / d u = 1 + A1 cos(...) + A2 cos(...) + R
```

- `A1 ≈ 1`：导数可降至 0（停滞带，产生墙/平面）；`A1 > 1` 时局部折叠（镜像地形，更像经典"重复"结构）。
- `P1` 控制"墙/平台"的间距尺度（默认 96 格）。
- 次谐波 `A2/P2` 打破完美周期性，模拟多八度叠加后的不规则外观。
- 变换是**有界、连续、C¹**的，因此不会产生 NaN / 极端值，原版管线（spline /
  rangeChoice / squeeze / aquifer）全部照常工作。

### 2.2 注入点选择（最小侵入）

**不重写 `ChunkGenerator`**。选择在密度函数树的**噪声叶子节点**处做世界坐标 → 噪声坐标的变换：

- `DensityFunctionTypes$Noise#sample(NoisePos)`（`base_3d_noise`、cave/vein 噪声等）
- `DensityFunctionTypes$ShiftedNoise#sample(NoisePos)`（气候噪声：温度/湿度/大陆性/侵蚀…）
- `DensityFunctionTypes$YClampedGradient#sample(NoisePos)`（深度梯度 / 地表滑移项，
  竖直剖面——**垂直阶跃墙必需**，见 2.4）
- `InterpolatedNoiseSampler`（`base_3d_noise`）：因其 `sample` 只接受整数坐标，
  按原版算法用分数坐标重算一遍（`WarpedInterpolatedNoiseSampler`）

优点：

1. 注入点是**所有噪声的唯一公共入口**，气候、地形、洞穴、矿脉、含水层全部经过同一变换 → 畸变空间自洽
   （biome、洞穴、矿物、结构全部继续走原版体系）。
2. `Marker`（INTERPOLATED / CACHE_ALL_IN_CELL）、spline、rangeChoice 等原版结构**完全不动**，
   `ChunkNoiseSampler` 的插值路径不变。
3. 正常区（distortion = 0）**短路返回原坐标**，与原版逐位一致。

### 2.3 区域划分（连续，无接缝）

以**出生区块中心** `(cx*16+8, cz*16+8)` 为原点，切比雪夫距离 `d = max(|dx|,|dz|)`：

```text
d <= R0            : 正常区（原版），R0 = NORMAL_REGION_CHUNKS*16/2 = 80 格（160×160 安全区）
R0 < d < R0 + W    : 过渡区，W = TRANSITION_WIDTH_BLOCKS = 16 格（1 chunk）
d >= R0 + W        : 完整边境之地
alpha = smoothstep(clamp((d - R0)/W, 0, 1))     // C¹ 连续
```

坐标变换按 `alpha` 混合：`p' = p + alpha * (far(p) - p)`；`alpha == 0` 时**不做任何浮点运算**。

- 连续函数 → 跨 chunk 无断层（相邻 chunk 在共享采样点得到相同值）。
- 过渡带 16 格内畸变幅度平滑增强。

### 2.4 边境之墙的最终实现（原地形切片 + 竖直折回）

目标观感：**老版本那种把原地形切成参差石壁、夹层、镂空，且最高的墙顶到建造上限**。
中间排除过若干方案（均有实测记录）：

| 方案 | 结果 |
| --- | --- |
| A. 密度偏移（加常数，高度 = 偏移 ÷ 当地梯度） | 高度完全不可控：梯度小的位置顶到 y=319，整片饱和 |
| B. 只看水平位移（谐波 / 锯齿断层） | 平坦地形"搬"不到高差（本 seed 出生点是平坦沙漠，只有 5~10 格）；锯齿还会把不同 biome 硬拼在一起 |
| C. 垂直位移但只包装噪声叶子 | 被未包装的 `y_clamped_gradient`（深度梯度/滑移项）抵消：请求 36 格只抬约 12 格 |
| D. 整片抬到天花板（单一阶跃） | 墙确实到顶了，但墙那边是一整块无特征的实心石头，不像老版本 |
| E. **原地形切片 + 竖直折回（最终）** | ✅ 见下 |

最终方案由三部分组成：

```text
1) 切片抬升（墙的骨架）
   lift(x,z,y) = levelX(x+leanX(y))·STEP_X + levelZ(z+leanZ(y))·STEP_Z
   level ∈ {0..3}：一维值噪声量化成 4 档；y' = y − lift
   → 原地形被切片整体错开；片内材质/形状不变，片间是竖直断面；
     最高几片抬升 ~288 格后地表超过 y=320，被建造上限切平 = "看不到顶的墙"

2) 竖直分层锯齿（老版本的夹层/镂空）
   y' += SAW·(frac((y+phase(x,z))/SAW_PERIOD) − 0.5)，SAW(104) > SAW_PERIOD(72)
   → 采样高度被折回 ⇒ 同一列出现多个密度零点 ⇒ 石壁里的夹层、镂空、拱洞

3) 参差化
   切片边界随高度摆动（lean，±26+9 格）+ 随高度倾斜的剪切（shear，48 格）
   → 崖面参差、有悬挑与倾斜层理，不再是"一张平板"
```

正常区内 `alpha == 0`，三段全部短路，坐标逐位等于原版（自检覆盖）。
水平方向另有一条很轻的主谐波（0.5 @128 格）用于把地形搬运得自然些。

## 3. 工程结构

```text
com.borderworld
  BorderWorld                    Mod 入口
  config/FarlandsConfig          全部可调常量（已按"未来可改配置文件"组织）
  core/SpawnRegion               实际出生区块获取 / 缓存 / 持久化（MC 侧）
  core/NormalRegion              正常区/过渡区判定（距离 → distortion）
  core/FarlandsTransform         纯数学：transformX/Y/Z, getDistortionFactor
  core/WorldgenMath              纯数学工具 + 经典 Far Lands 参考数学
  mixin/                         世界生成注入（噪声叶子 + 出生点捕获）
```

`core/` 中的 `FarlandsTransform`、`WorldgenMath`、`NormalRegion` **零 Minecraft 依赖**，
可直接复用到 26.x（迁移时只需重写 mixin 与 SpawnRegion 的 MC 侧接口）。

## 4. 分阶段验证

| 阶段 | 内容 | 验证方式 |
| --- | --- | --- |
| 1 | 工程可构建 | `./gradlew build` + 启动 dev server |
| 2 | 出生区块获取/持久化 | 服务端日志 + 重启后一致 |
| 3 | 10×10 正常区判定 | 单元自测 + 日志 |
| 4 | 正常区与 Vanilla 一致 | 同种子双服务端（原版/带 Mod）区块 NBT 全量比对 |
| 5 | 注入点确认 | 反编译源码核查 + 采样探针日志 |
| 6 | 边境之地数学 | 区块高度统计（局部陡坡/停滞带比例） |
| 7 | 连续过渡 | 沿射线采样 distortion/高度曲线，检查分段连续性 |
| 8 | 跨 chunk 接缝 | 边界两侧高度场比对，无 chunk 级跳变 |
| 9 | biome/洞穴/矿物/结构 | 区块 NBT 中 biome 数组、洞穴空洞、矿石方块、structure starts 统计 |
| 10 | 整理配置与文档 | 代码走查 + README |

> 阶段 4 的比对脚本放 `tools/`，用 Python 解析 region 文件（block states / biomes），
> 避免依赖 Minecraft 本体。
