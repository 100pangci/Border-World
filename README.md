# Border World

Fabric 1.21.1 / Java 21 Mod：**世界以实际出生点为中心保留一小块完全原版的正常区域，之外通过扭曲世界生成噪声坐标，让地形连续地"坠入"边境之地（Far Lands）。**

> 玩家出生在世界仅剩的一小块正常区域，越过约 160×160 格的安全区后，整个无限世界逐渐坠入边境之地。

## 效果与原理

| 区域 | 范围（以出生区块中心为原点，切比雪夫距离） | 行为 |
| --- | --- | --- |
| 正常区 | ≤ 80 格（10×10 chunks = 160×160） | 与 Vanilla **逐位一致** |
| 过渡区 | 80 → 112 格（2 chunks 宽） | 畸变系数 smoothstep 连续 0 → 1 |
| 边境之地 | ≥ 112 格 | 完整畸变，无限延伸 |

畸变方式不是预制墙体或结构拼接，而是把**进入噪声的世界坐标**做一次有界相位调制：

```text
v(u) = u + Σ strengthᵢ · (periodᵢ / 2π) · sin(2π·u/periodᵢ + phaseᵢ) + ramp·u
v'(u) = 1 + Σ strengthᵢ · cos(...) + ramp
```

- 导数为 0 的位置即"停滞带"：地形沿该轴被挤出成平台/巨墙/长隧道；
- 两个水平轴各自独立调制，叠加形成方格状地形（与经典 Far Lands 形态一致）；
- 多谐波叠加模拟经典机制里"多个八度在不同距离逐级溢出"的不规则性；
- 数学全部有界：不会像旧版那样外推到 1e11 量级，因此原版 spline / rangeChoice /
  squeeze / 含水层 / 洞穴管线全部照常工作，biome、洞穴、矿物、结构继续沿原版体系生成。

历史成因分析（12,550,824 那个数字怎么来的）与设计取舍见 [`docs/DESIGN.md`](docs/DESIGN.md)。

## 注入点（对原版侵入最小）

不重写 `ChunkGenerator`。安装点在 `ServerChunkLoadingManager` 构造完成时（每个维度一个实例，
因此只在 Overworld 安装），把 Overworld 噪声路由里的**噪声叶子**包装成坐标变换版本：

| 被包装的叶子 | 覆盖范围 |
| --- | --- |
| `InterpolatedNoiseSampler`（`base_3d_noise`） | 地形基础 3D 噪声（经典 Far Lands 的直系噪声，171.103/格） |
| `DensityFunctionTypes$Noise` | 含水层、洞穴、矿脉、jagged 等全部普通噪声 |
| `DensityFunctionTypes$ShiftedNoise` | 温度/湿度/大陆性/侵蚀/深度/怪异度 → biome 与地形共享同一畸变空间 |

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

系统属性开关：

- `-Dborderworld.warpEnabled=false`：完全关闭畸变（A/B 对照用）
- `-Dborderworld.log=false`：关闭锚定日志

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

见 [`docs/VERIFICATION.md`](docs/VERIFICATION.md)：各阶段实测结论与剩余事项。
