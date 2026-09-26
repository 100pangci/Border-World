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

### 2.4 最终实现：坐标钉死 + 高度层叠（旧版机制的直接对应）

排查历程（每一步都有实测记录）：密度偏移（高度不可控）→ 水平位移（平地无高差）→
垂直位移但不包 `y_clamped_gradient`（被深度梯度抵消）→ 整片抬到天花板（实心石头，无形态）
→ 小尺度削碎（浮岛汤）→ **照 Wiki 描述实现旧版机制**（最终）。

```text
① 坐标钉死（对应旧版 int 饱和）
   FarlandsTransform.pin()：超出安全区的采样 x/z 夹在边界
   → 该轴上噪声不再变化 = 旧版"沿轴无限延伸的笔直隧道"

② 高度层叠（对应旧版 Corner Far Lands 的 stack）
   sampledY = base + mod(y − lift − base, STACK_PERIOD)      base=24, PERIOD=72
   → 同一段地形剖面沿高度重复堆叠，层间露出横切面与空隙

③ 角落（Corner Far Lands）
   两轴同时溢出时，旧版结构只取决于两轴"超出量"的比值（沿角落射线的直线恒定）
   → 层理相位 CORNER_DIAGONAL·(ratio−0.5)·2 倾斜（放射状斜线）；
     层厚 STACK_PERIOD·(1 + SWING·(ratio−0.5)) 随方向变化（层融合/分裂）；
     取绝对值 → 象限镜像（与 Wiki 记载一致）

④ 每层横向错位与参差
   每层的采样 x/z 按"层号哈希"再平移 LAYER_OFFSET（72 格）→ 层与层砖墙式错开
   （对照旧版截图 Corner_Far_Lands.png：地皮是一块块错开的，不是上下对齐的千层饼）
   另有切片抬升（X 40@96 + Z 28@48）与混沌（chaosLift，20 格）做细部参差
```

**尝试过但放弃的**（记录在案，避免重走）：
- 「二元化密度」：直接把 `finalDensity` 替换成某噪声的符号（最字面的复现）——
  会卡死生成器（原版含水层/地表搜索假设密度沿 Y 单调，被破坏后 RCON 无响应）。
  代码保留在 `worldgen/OverflowDensityFunction.java`，**未安装**。
- 竖直折回（fold）与 3D 噪声位移：能让石壁"打洞"，但只在地表附近有效、
  且容易把地形绞成浮岛，已默认关闭（参数仍在，可单独启用）。

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
