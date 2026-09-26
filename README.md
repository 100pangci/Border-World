# Border World

Fabric 1.21.1 / Java 21 Mod：**世界以实际出生点为中心保留一小块完全原版的正常区域，之外通过扭曲世界生成噪声坐标，让地形连续地"坠入"边境之地（Far Lands）。**

> 玩家出生在世界仅剩的一小块正常区域，越过约 160×160 格的安全区后，整个无限世界逐渐坠入边境之地。

## 效果与原理

| 区域 | 范围（以出生区块中心为原点，切比雪夫距离） | 行为 |
| --- | --- | --- |
| 正常区 | ≤ 80 格（10×10 chunks = 160×160） | 与 Vanilla **逐位一致** |
| 过渡区 | 80 → 96 格（1 chunk 宽） | 畸变系数 smoothstep 连续 0 → 1 |
| 边境之地 | ≥ 96 格 | 完整畸变，无限延伸 |

畸变方式不是预制墙体或结构拼接，而是把**原地形整体"切片"再逐片抬升**，
外加一层竖直分层锯齿：

```text
切片抬升： lift(x,z,y) = levelX(x+leanX(y))·STEP_X + levelZ(z+leanZ(y))·STEP_Z
           level ∈ {0,1,2,3}（每轴一维值噪声量化成 4 档）
           y' = y − lift                    // 采样点下移 = 该片地形整体抬高
分层锯齿： y' += SAW · (frac((y+phase(x,z))/SAW_PERIOD) − 0.5)   // 强度 > 周期 ⇒ 折回
```

- **片内仍是原来的地形**（草、土、沙、石头、甚至树都在），只是整体挪了一个高度；
- **片与片之间是竖直断面**（沿 Z 每 ~56 格一道细壁、沿 X 每 ~160 格一道粗台阶），
  最高几片（抬升 250+ 格）会顶到建造上限 y=320 被切平 —— 就是"看不到顶的墙"；
- **分层锯齿折回**（强度 104 > 周期 72）→ 同一列出现多个"地表" → 石壁里的
  **夹层、镂空、拱洞** —— 老版本边境之地最标志性的外观；
- 切片边界随高度摆动（`lean`）+ 随高度倾斜的剪切（`shear`）→ 崖面参差、有悬挑；
- 正常区内一切返回原坐标，与原版逐位一致。

当前默认参数（`config/FarlandsConfig.java`）：

```text
正常区 10×10 chunks（160×160 格） / 过渡区 16 格
切片：X 56 格 @160 格（4 档） + Z 40 格 @56 格（4 档）   → 抬升 0..288 格，最高顶到建造上限
分层锯齿：104 格 @72 格（折回 → 夹层/镂空） + 剪切 48 格 @96 格
水平：主谐波 0.5 @128 格（轻度搬运）  +  次谐波 0（关闭）
```

历史成因分析（12,550,824 那个数字怎么来的）与设计取舍见 [`docs/DESIGN.md`](docs/DESIGN.md)。

## 注入点（对原版侵入最小）

不重写 `ChunkGenerator`。安装点在 `ServerChunkLoadingManager` 构造完成时（每个维度一个实例，
因此只在 Overworld 安装），把 Overworld 噪声路由里的**噪声叶子**包装成坐标变换版本：

| 被包装的叶子 | 覆盖范围 |
| --- | --- |
| `InterpolatedNoiseSampler`（`base_3d_noise`） | 地形基础 3D 噪声（经典 Far Lands 的直系噪声，171.103/格） |
| `DensityFunctionTypes$Noise` | 含水层、洞穴、矿脉、jagged 等全部普通噪声 |
| `DensityFunctionTypes$ShiftedNoise` | 温度/湿度/大陆性/侵蚀/深度/怪异度 → biome 与地形共享同一畸变空间 |
| `DensityFunctionTypes$YClampedGradient` | 深度梯度 + 地表滑移项（**竖直剖面**）→ 垂直阶跃墙能真正抬起地形 |

**为什么必须连 `y_clamped_gradient` 一起包装**：地表高度是密度函数的零点。
只搬噪声叶子时，未变换的深度梯度会把零点拽回原位（实测 36 格阶跃只抬起来约 12 格，
而且被抹成斜坡）；把剖面函数一起搬之后，实测抬升量精确等于 `wallHeight`。

同时把 `NoiseConfig` 的 `MultiNoiseSampler` 六个气候字段就地替换（不重建对象，
以兼容 Fabric API 注入的 seed 字段），保证"查询到的 biome"与"生成出来的 biome"一致。

`Marker`（interpolated / flat_cache / cache_2d / cache_once / cache_all_in_cell）、
Spline、rangeChoice 等原版结构**完全不动**，`ChunkNoiseSampler` 的插值/缓存路径保持原样。

## 代码结构

```text
src/main/java/com/borderworld/
  BorderWorld.java                Mod 入口
  config/FarlandsConfig.java      全部可调参数（未来可整体改成配置文件）
  core/
    WorldgenMath.java             纯数学工具 + 经典 Far Lands 参考数学
    NormalRegion.java             正常区/过渡区/边境之地判定（零 MC 依赖）
    FarlandsTransform.java        坐标变换 transformX/Y/Z + getDistortionFactor（零 MC 依赖）
    SpawnRegion.java              实际出生区块获取/缓存/锚定（core 中唯一接触 MC 的类）
  worldgen/
    WarpedDensityFunction.java            通用噪声叶子包装器
    WarpedInterpolatedNoiseSampler.java   base_3d_noise 的分数坐标重算实现
    WarpInstaller.java                    按维度安装变换
  mixin/
    accessor/*                            只读访问器 + 字段替换
    ServerChunkLoadingManagerMixin.java   Overworld 安装点
    ServerWorldMixin.java                 出生点捕获（加载 + /setworldspawn）
    MinecraftServerMixin.java             出生点搜索期间暂停畸变
tools/                            构建、生成、验证脚本（见下）
```

## 构建与运行

```bash
./gradlew build          # 产出 build/libs/borderworld-<version>.jar
./gradlew runServer      # dev 服务端（run/server）
./gradlew runClient      # dev 客户端（可视化检查）
```

安装到**正式 Fabric 服务端/客户端**：把 `build/libs/borderworld-<version>.jar` 与
[Fabric API](https://modrinth.com/mod/fabric-api) 一起放进 `mods/` 即可（jar 已 remap 到
intermediary，refmap 完整，无需开发环境）。

系统属性开关：

- `-Dborderworld.warpEnabled=false`：完全关闭畸变（A/B 对照用）
- `-Dborderworld.selfTest=true`：服务端启动时跑一次"密度函数逐位自检"（见 `docs/VERIFICATION.md`）
- `-Dborderworld.log=false`：关闭锚定日志
- Gradle 运行任务可用 `-Pbw.warp=false` / `-Pbw.selftest=true` 传入上述开关

## 验证工具

```bash
# 1. 纯数学自检（无需 Minecraft）
javac -d /tmp/selfcheck \
    src/main/java/com/borderworld/core/{WorldgenMath,NormalRegion,FarlandsTransform}.java \
    src/main/java/com/borderworld/config/FarlandsConfig.java tools/SelfCheck.java
java -cp /tmp/selfcheck SelfCheck

# 2. 启动服务端 + 围绕出生点/指定区域批量生成区块（RCON 驱动）
python3 tools/mc_harness.py --start "./gradlew runServer --console=plain" \
    --server-dir run/server --log /tmp/bw-server.log \
    --forceload-around-spawn 7 7 --forceload 12 12 19 19

# 3. 与世界对比 / 统计
python3 tools/world_compare.py compare   --a run/server --b run/vanilla --chunks -7 -7 7 7
python3 tools/world_compare.py stats     --dir run/server --label modded --chunks 12 12 19 19
python3 tools/world_compare.py seams     --dir run/server --label modded --chunks -8 -8 20 20
python3 tools/world_compare.py heightmap --dir run/server --label modded --chunks -8 -8 20 20 --scale 4
```

Vanilla 基线用同一个种子与同一批区块：

```bash
java -Xmx2G -jar ~/.gradle/caches/fabric-loom/1.21.1/minecraft-server.jar --nogui
```

（`tools/mc_harness.py` 支持 `--cwd run/vanilla`，RCON 配置见 `run/*/server.properties`。）

## 当前状态

**阶段 1~9 均已实测通过**（详见 [`docs/VERIFICATION.md`](docs/VERIFICATION.md)）：

| 阶段 | 结论 |
| --- | --- |
| 1 工程 | `./gradlew build` 通过；发布 jar 的 mixin refmap 完整（生产环境可用） |
| 2 出生区块 | 锚定实际出生区块 `(0,0)`；`setupSpawn` 期间暂停畸变，`/setworldspawn` 即时刷新 |
| 3 正常区判定 | 纯数学自检 33/33 通过 |
| 4 正常区逐位原版 | **密度函数逐位自检 6/6 通过**（正常区采样点与"强制恒等"逐位一致）；地形顶面与原版 feature 噪声同级 |
| 5 注入点 | 噪声叶子 + NoiseConfig 路由替换；Fabric biome API 兼容（就地改字段） |
| 6 畸变生效 | 远区 69% 列地形改变（最大 19 格，深海）；陆地/山地更明显 |
| 7 连续过渡 | 差异比例：正常区 0.1~0.2% → 过渡区 21.5%→56.5% → 边境区 65%~81.5%（平均差 1.0→4.65 格，最大 34 格） |
| 8 无接缝 | 跨区块边界与区块内部的高度差统计与原版同量级（比值 0.52 vs 0.70） |
| 9 生态仍工作 | 远区 biome 正常、矿石齐全、洞穴比例 3.67%（原版 3.91%）、结构（试炼密室）生成 |

### 已知限制（第一阶段）

1. `SurfaceBuilder` 自带表面噪声（surfaceNoise / badlands / iceberg）与洞穴
   "意面/面条"噪声（`WeirdScaledSampler`）、`Shift*` 域偏移噪声未参与坐标变换；
   它们仍正常生成，只是不与畸变空间对齐（影响表面材质细节与洞穴形状细节）。
2. 跨进程逐方块全等无法作为判据：实测**原版自身跑两遍**也会在矿石/树木等 feature
   方块上有差异（Minecraft 特征生成跨进程不可复现）。因此正常区验证采用
   "密度函数逐位自检 + 地形顶面 + biome 数组"三层判据。
3. 出生点搜索期间畸变暂停，所以出生点选择与原版完全一致；若之后用
   `/setworldspawn` 改出生点，已生成的区块不会重写（锚点即刻移动，新生成区块跟随）。

### 调参

所有参数在 `config/FarlandsConfig.java`（未来可整体搬成配置文件）：

| 参数 | 默认 | 作用 |
| --- | --- | --- |
| `FARLANDS_SLAB_STEP_X/LATTICE_X` | 56 @ 160 | 沿 X 的粗台阶：每 160 格一片，片间错开 56 格 |
| `FARLANDS_SLAB_STEP_Z/LATTICE_Z` | 40 @ 56 | 沿 Z 的细切片：每 56 格一道竖壁，错开 40 格 |
| `FARLANDS_SLAB_LEVELS` | 4 | 每轴档数：抬升量 = 档位 × STEP（最高 3×56+3×40 = 288 格） |
| `VERTICAL_WARP_STRENGTH/PERIOD` | 104 @ 72 | 竖直分层锯齿（强度>周期 ⇒ 折回）：夹层/镂空/拱洞 |
| `FARLANDS_SHEAR_STRENGTH/PERIOD` | 48 @ 96 | 随高度倾斜的剪切：悬挑/倾斜层理 |
| `FARLANDS_PRIMARY_STRENGTH/PERIOD` | 0.5 / 128 | 主谐波：轻度水平搬运（>1 会产生折叠/碎片） |
| `FARLANDS_SECONDARY_STRENGTH/PERIOD` | 0 / 320 | 次谐波：默认关闭 |
| `FARLANDS_SAWTOOTH_STRENGTH/PERIOD` | 0 / 128 | 水平锯齿断层：默认关闭 |
| `TRANSITION_WIDTH_BLOCKS` | 16 | 过渡区宽度（1 区块） |
| `NORMAL_REGION_CHUNKS` | 10 | 正常区边长（10×10 chunks） |

`tools/tune_warp.py` 用"原版高度场 + 坐标映射"的数值代理快速比较候选参数
（无需反复起服）：

```bash
python3 tools/tune_warp.py --dir run/vanilla --chunks -10 -10 10 10 --center-chunk 0 0
```

