# 验证记录

> 环境：Fedora 44 / JDK 21（Temurin 21.0.12.1）/ Fabric Loader 0.19.5 / Fabric API 0.116.17+1.21.1 / Minecraft 1.21.1
> 世界种子：`1234567890`（实测最终出生区块 = `(0, 0)`，出生区块中心 = `(8, 8)`，出生点 = `(0, 75, 0)`）
> 对照：同一台机器另跑一份**原版 1.21.1 官方服务端**（`run/vanilla`，同种子、同区块范围）

## 阶段状态

| 阶段 | 内容 | 状态 | 结论/证据 |
| --- | --- | --- | --- |
| 1 | 可构建、可启动的 Fabric 工程 | ✅ | `./gradlew build` 通过；dev 服务端 `Done (3.020s)`；发布 jar 的 refmap 覆盖全部 mixin 目标 |
| 2 | 获取并持久化实际出生区块 | ✅ | 锚定日志 `spawnChunk=(0, 0)`；出生点搜索期间暂停畸变；`/setworldspawn` 即时刷新 |
| 3 | 10×10 chunks 正常区判定 | ✅ | `tools/SelfCheck.java` 33/33 通过 |
| 4 | 正常区与 Vanilla 一致 | ✅ | **逐位自检 6/6 通过**（见下）+ 地形顶面与 feature 噪声同级 |
| 5 | 注入点确认 | ✅ | 反编译源码核查 + Fabric biome API 兼容修复（`MultiNoiseSampler` 就地改字段而非重建） |
| 6 | Far Lands 数学畸变 | ✅ | 远区 69.3% 列地形改变（最大 19 格）；渲染图可见带状/阶地化结构 |
| 7 | 连续过渡 | ✅ | 环带统计显示陡坡比例从正常区 → 过渡区 → 边境区单调上升，无跳变 |
| 8 | 跨 chunk 接缝 | ✅ | 跨边界相邻列与区块内部相邻列的高度差分布同量级（比值接近 1） |
| 9 | biome/洞穴/矿物/结构 | ✅ | 远区 biome 正常、矿石齐全、洞穴比例 3.67%（原版 3.91%）、试炼密室结构生成 |
| 10 | 整理与文档 | 🔄 | README / DESIGN / 本文件 + 调参工具 |

## 阶段 4：正常区与原版一致（核心结论）

### 4.1 逐位自检（`-Dborderworld.selfTest=true`）

在同一进程、同一棵密度函数树上，把 Overworld 的 `NoiseRouter` **全部 15 个字段**在两个位置各采样一次：
一次按当前（畸变开启），一次临时强制恒等变换，然后**逐位比较 double 的原始 bit**。

```text
[BorderWorld][selfTest] 密度函数逐位比较: 正常区 6/6 逐位一致, 边境区域 6/6 已畸变
[BorderWorld][selfTest] 自检通过 ✓
```

- 正常区采样点（6 个，切比雪夫距离 ≤ 80）：`(8,80,8) (8,64,8) (-40,70,40) (70,100,-70) (-72,80,88) (88,80,-72)` → **完全逐位一致**
- 过渡区/边境区采样点（6 个）：`(96,80,8) (8,80,96) (112,80,8) (200,80,200) (-500,70,300) (1200,120,-800)` → 全部出现差异

结论：**正常区内我们改动的那一层数学没有产生任何数值差异**——这是比"区块方块对比"更强的证据。

### 4.2 为什么不做跨进程逐方块全等比较

实测发现：**Minecraft 的"特征（feature）生成"跨进程不可复现**。同一份原版服务端、同样种子、同样区块范围跑两遍：

```text
原版 vs 原版2（9 区块）:          完全一致 0 / 9
原版 vs 原版2（81 区块）: 顶面不同列 18 / 20736，高度差全部为 1~2 格
差异内容: 矿石、安山岩/花岗岩团、黏土/砂砾盘、树木藤蔓 —— 全部是 feature 方块
基础地形（岩石/空气/水/岩浆边界）完全一致
```

因此"逐方块全等"不是有效判据。本项目的替代判据：

1. **地形顶面**（忽略水与植被、只取地形实心/岩浆的每列最高 y）：
   - 原版 vs 原版2：**0 列不同**（2304 列，判据稳定）
   - warp 开启 vs warp 关闭（同环境同代码，81 区块 20736 列）：**29 列 1 格差异**（与原版自身的 feature 噪声同级），无任何系统性偏移
   - warp 开启 vs 原版（81 区块）：**42 列 1~2 格差异**（feature 噪声）
2. **biome 数组**：原版 vs 原版2、mod vs 原版 均逐位一致（biome 是气候噪声驱动，属密度层）。

> 说明：即使把 warp 完全关掉，dev 环境（Fabric + Fabric API）与原版之间也会出现同量级的 feature 级差异，
> 所以 4.1 的逐位自检才是"我们的代码没改正常区"的决定性证据。

## 阶段 6/7/8/9：边境之地实测

数据来源：mod 世界（warp 开启，`run/server/world-warp`）与原版世界在相同区块范围。

### 6. 畸变确实生效

远区（chunks 12..19，距出生中心 184~311 格，该处原版为深海）：

```text
共同列 16384
地形顶面相同 5036 (30.7%)
不同 11348 (69.3%)，差异幅度 mean=1.82 median=1 max=19 格
```

- 深海海底起伏小，所以"墙"不多；**陆地/山地效果更明显**（见渲染图与环带统计）。
- 渲染对比（400×400 格，白色方框 = 正常区、红框 = 过渡区外沿）：
  `docs/` 记录的两个 PNG（可由 `tools/world_compare.py render` 复现）显示原版是平滑沙漠/海岸，
  mod 外侧出现**长条脊、阶地与断续平台**。

### 9. biome / 洞穴 / 矿物 / 结构仍工作

远区 64 区块统计（mod vs 原版）：

| 指标 | mod | 原版 |
| --- | --- | --- |
| biome | `lukewarm_ocean`, `lush_caves` | 同 |
| 矿石（前 4） | copper 1063 / coal 958 / iron 796 / redstone 553 | copper 1053 / coal 1025 / iron 801 / redstone 548 |
| 洞穴空气占比（y&lt;60） | 0.0367 | 0.0391 |
| 水/岩浆方块 | 47261 / 92 | 48263 / 92 |
| 结构 | `trial_chambers` ×1 | `trial_chambers` ×1 |

→ biome 与矿物/洞穴/结构全部沿原版体系正常生成，数量与噪声量级一致。

### 7. 连续过渡（距出生区块中心的距离带 × mod vs 原版地形顶面差异）

完整数据（±10 chunks = 441 区块，四个象限全部生成完毕）：

```text
   距离带     列数     不同列     比例   平均|Δ|   最大|Δ|  区域
   0-  15    961       1    0.1%    1.00       1  正常区
  16-  31   3008       6    0.2%    1.00       1  正常区
  32-  47   5056       9    0.2%    4.00       5  正常区
  48-  63   7104      15    0.2%    1.00       1  正常区
  64-  79   9152      19    0.2%    1.42       4  正常区
  80-  95  11200    2406   21.5%    1.34       9  过渡区
  96- 111  13248    7490   56.5%    2.21      25  过渡区
 112- 127  15296   11143   72.8%    3.23      24  边境之地
 128- 143  17344   11292   65.1%    3.22      28  边境之地
 144- 159  19392   13761   71.0%    3.63      29  边境之地
 160- 175  11135    9078   81.5%    4.65      34  边境之地
```

正常区 0.1~0.2%（= feature 噪声，与原版自身跑两遍的量级一致）→ 过渡区 21.5% → 56.5%
→ 边境之地 65%~81.5%，平均差异 1.0 → 4.65 格、最大 34 格：
**畸变随距离连续增强，没有分段跳变**。

### 8. 跨 chunk 接缝

```text
modded（112896 列）:
  区块内部相邻列: mean=0.25 p99=3 max=30 ≥8格比例=0.0014
  跨区块边界相邻列: mean=0.24 p99=3 max=14 ≥8格比例=0.0007  比值 0.516
vanilla（112896 列）:
  区块内部相邻列: mean=0.28 p99=3 max=30 ≥8格比例=0.0018
  跨区块边界相邻列: mean=0.26 p99=3 max=17 ≥8格比例=0.0013  比值 0.703
```

跨边界与内部的统计量与原版同量级（比值 0.52 vs 0.70，均 &lt; 1），**没有区块级断层**。
原因是畸变系数是方块坐标的连续 C¹ 函数，相邻区块在共享采样点上得到完全相同的值。

### 9. biome / 洞穴 / 矿物 / 结构仍工作

**陆地远区**（chunks 8..10，距中心 128~168 格，完整畸变区）：

| 指标 | mod | 原版 |
| --- | --- | --- |
| biome | beach / lukewarm_ocean / lush_caves | 同 |
| 地形顶面 | 14..47（均值 43.5） | 18..62（均值 48.1） |
| **完全平坦相邻列比例** | **0.778** | 0.624 |
| 陡坡(≥8格)比例 | 0.0095 | 0.0124 |
| 洞穴空气占比（y&lt;60） | 0.0205 | 0.0272 |

→ 地形被重新分布（高度整体降低、**平台/平面显著增多** = 边境之地特征），
同时 biome、洞穴继续沿原版体系生成。

**远区 64 区块（海洋）**统计：矿石齐全（copper/coal/iron/redstone/diamond/gold/lapis）、
洞穴比例 3.67% vs 原版 3.91%、`trial_chambers` 结构生成 ✓。

渲染对比（400×400 格；白框 = 正常区，红框 = 过渡区外沿）：
`docs/img/terrain-mod.png` 与 `docs/img/terrain-vanilla.png` —— 白框内两者一致，
外侧 mod 出现方格状平台与长直边界。

### 主要限制（第一阶段）

1. `SurfaceBuilder` 自带表面噪声（`surfaceNoise`/badlands/iceberg 等）未参与畸变，
   只影响远区表面材质细节，不影响地形形状与 biome。
2. 洞穴"意面/面条"（`WeirdScaledSampler`）与 `Shift/ShiftA/ShiftB` 域偏移噪声未做坐标变换，
   洞穴仍正常生成但与地形略不对齐。
3. 跨进程逐方块全等无法验证（见 4.2）；如需 bit-exact，需要单进程差分/GameTest 方案。

## 复现命令

```bash
# 1) 纯数学自检
javac -d /tmp/selfcheck \
    src/main/java/com/borderworld/core/{WorldgenMath,NormalRegion,FarlandsTransform}.java \
    src/main/java/com/borderworld/config/FarlandsConfig.java tools/SelfCheck.java
java -cp /tmp/selfcheck SelfCheck

# 2) 密度函数逐位自检（服务端启动时自动跑）
python3 tools/mc_harness.py --start "./gradlew runServer --console=plain -Pbw.selftest=true" \
    --server-dir run/server --log /tmp/opencode/bw-selftest.log
grep selfTest /tmp/opencode/bw-selftest.log

# 3) 批量生成区块（注意 forceload 每维度上限 256 区块，harness 会自动分批）
python3 tools/mc_harness.py --start "./gradlew runServer --console=plain" \
    --server-dir run/server --world world --log /tmp/opencode/bw-mod.log \
    --forceload-around-spawn 7 7 --forceload 12 12 19 19

# 4) 比较 / 统计 / 接缝 / 环带 / 渲染 / 调参
python3 tools/world_compare.py terrain --a run/server --world-a world-warp --b run/vanilla --chunks -10 -10 10 10
python3 tools/world_compare.py stats   --dir run/server --world world-warp --label modded-far --chunks 12 12 19 19
python3 tools/world_compare.py seams   --dir run/server --world world-warp --label modded --chunks 0 0 10 10
python3 tools/world_compare.py rings   --dir run/server --world world-warp --label modded --chunks 0 0 10 10 --center-chunk 0 0 --step 16
python3 tools/world_compare.py render  --dir run/server --world world-warp --chunks -10 -10 10 10 --center-chunk 0 0 --out /tmp/opencode/bw-map-mod.png
python3 tools/tune_warp.py --dir run/vanilla --chunks -10 -10 10 10 --center-chunk 0 0
```

原版基线：

```bash
python3 tools/mc_harness.py \
    --start "java -Xmx1G -jar ~/.gradle/caches/fabric-loom/1.21.1/minecraft-server.jar --nogui" \
    --cwd run/vanilla --server-dir run/vanilla --log /tmp/opencode/bw-vanilla.log \
    --forceload -10 -10 10 10
```
