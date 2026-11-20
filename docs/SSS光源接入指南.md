# 屏幕空间聚光灯（SSS）· 光源接入指南

> 适用版本：JiezhiMod **5.1.0** 及以后。给自己未来加"探照灯 / 吊顶灯 / 地面灯"时看这一份就够。

---

## 一、整套系统的三个环节

```
方块实体 (TileEntity)          →  收集器 AlarmSpotlights      →  着色器 DepthDebugPass
  位置 / 颜色 / 朝向 / 转速          按距离挑最近的灯             屏幕空间加法光照
```

| 环节 | 文件 | 职责 | **唯一真源** |
|---|---|---|---|
| 安装方式 | `client/render/LightType.java` | 判 FLOOR / WALL / CEILING，给光锥与射程 | ✔ 类型 + 角度 + 射程 + 束数 |
| 方向 | `client/render/RenderAlarmLight.java` | `beamWorldDir()` 算世界方向 | ✔ 光束方向 |
| 收集 | `client/render/AlarmSpotlights.java` | 找灯、填 uniform 数组、射线做遮挡 | ✔ 遮挡策略 |
| 着色 | `client/render/DepthDebugPass.java` | 反投影 + 锥体/衰减/N·L | ✔ 光照公式 |

**铁律：加新灯时只允许改这四个文件（通常是前两个），绝不在别处再写一份"角度/射程/朝向"的判断。**

---

## 二、三种安装方式的完整参数（v5.1.0 定档）

| 类型 | 判据（`FACING` 法线） | 光锥 内/外 | 射程 | 光束 | 方向公式 |
|---|---|---|---|---|---|
| `FLOOR` 立杆 | `axis.y > 0.5` | 35° / 55° | 8 格 | 2 条对扫 | 径向扫描 + `BEAM_PITCH(-5°)` + `BEAM_DROP(0.20)` |
| `WALL` 挂壁 | 其余（`axis.y` 的绝对值 ≤ 0.5） | **15° / 45°** | **10 格** | **1 条**（墙面画圆） | **单束完整圆环 + 下移**：`normalize(T·(radius(0.5)·cos) + V·(yOffset(1.0) + radius·sin) − n·WALL_NORMAL_SEEP(0.2))`，`V=(0,-1,0)` |
| `CEILING` 吊顶 | `axis.y < -0.5` | 45° / 75° | 10 格 | 1 条 | 严格垂直向下（`axis` 本身，零横向扫描） |

调参只改 `LightType` 构造参数里的三个数（内角、外角、射程）；方向只改 `RenderAlarmLight` 里对应的常量。

★ 挂壁灯用**单束完整贴墙圆环 + 下移**（v5.4.9 定案）：
`dir = normalize(T·(radius·cos) + V·(yOffset + radius·sin) − n·WALL_NORMAL_SEEP)`。
`T = horizontal(u)` 沿墙左右、`n = horizontal(axis)` 墙面朝外、`V = WALL_DOWN = (0,-1,0)`；
`radius=0.5`（圆环半径）、`yOffset=1.0`（**光斑中心下移 1 格**，sin 仍在 ±0.5 摆动、圆环完整）、`WALL_NORMAL_SEEP=0.2`（上下都向墙内微倾），只发 1 条光。
★ `cos`/`sin` 公式结构别动，只调 `yOffset` 平移光斑中心。
★ 外角收到 45°（内角 15°）：30° 太窄会裁掉上方光斑；45° 让上下两半都完整铺开。
★ 挂壁光锥 15°/45°、射程 10 格。

---

## 三、加一盏新灯：五步

### 1. 方块 + 方块实体

照抄 `BlockAlarmLight` / `TileAlarmLight`：

- 方块 `getRenderType` 用 `EnumBlockRenderType.INVISIBLE`（外形全交给 TESR，避免 cube 罩住灯罩）
- `FACING` 用 `PropertyEnum.create("facing", EnumFacing.class)` 六向
- ★★ 方块实体**两个入口都要写**：`createTileEntity(World, IBlockState)` 和 `createNewTileEntity(World, int)`
  —— 1.12.2 实际调用前者，只写后者会**静默失效**（`docs/踩坑记录.md` #17）
- 运行期状态变更（开关/颜色）必须 `broadcastState()` 自己发包，`notifyBlockUpdate` 不带 TE 的 NBT

### 2. 决定它属于哪种 `LightType`

**不要新建枚举常量**，先看 `classify()` 能否覆盖：

```java
LightType t = LightType.classify(axis);   // axis = FACING 的方向向量
```

- 灯口朝上 → `FLOOR`
- 灯口朝下 → `CEILING`
- 灯口水平 → `WALL`

如果新灯的光学行为和这三种都不一样（比如"环形灯，四面八方"），才在 `LightType` 里加常量，
并同时给 `beamCount()`、`cosInner()`、`cosOuter()`、`range()` 四组值 —— 它们必须在一起，不能分裂。

### 3. 写 `beamWorldDir`

在 `RenderAlarmLight.beamWorldDir` 的 `switch` 里加一个 case，返回**归一化**的世界方向：

```java
case XXX: {
    Vec3d flat = horizontal(axis);
    return new Vec3d(flat.x, -XXX_DROP, flat.z).normalize();
}
```

★ 这个方法是整个 mod 里**唯一**算光束方向的地方。光斑、遮挡射线、TESR 全部读它，
再写第二份必然出现"光斑在左、光照在右"且极难定位（v4.x 踩过两次）。

### 4. 接进收集器

`AlarmSpotlights.update()` 里已经把 `TileAlarmLight` 的收集写死了。新灯如果**不是** `TileAlarmLight`：

1. 在 `rescan()` 的 `instanceof` 判断里加上新类型，缓存进 `lamps`
2. `emit()` 里按新类型取颜色 / 转速 / 轴向量
3. `MAX_LIGHTS = 8`（v5.2.0 起）是着色器槽位上限，按距离取最近的灯；超出会被静默截掉。
   改这个数必须同步：`AlarmSpotlights.MAX_LIGHTS`、`DepthDebugPass` 的 `uniform ... [N]` 五处声明、
   以及 `for (int i = 0; i < N; i++)` 循环上限。**对不上不会报错**，只会静默丢光源。

### 5. 遮挡：射线策略

`trace()` 只做一件事：沿锥轴打一条射线，用命中结果决定**这束光能走多远**。

| 命中距离 | 处理 |
|---|---|
| ≥ `MIN_CLEAR_DIST`(2.0) 且命中竖直满方块 | 生成半空间平面，挡住墙后 |
| ≥ 2.0 但命中水平面 / 非满方块 | 不生成平面（水平面会切瞎整片地面，见 v4.21） |
| < 2.0 且 `WALL` | **不生成面法线平面、也不改射程**：生成一张垂直于光束的**端盖平面**（零点在 `max(hitDist, WALL_CAP_MIN=1.5)` 处） |
| < 2.0 且 `FLOOR`/`CEILING` | 忽略这次命中，按原射程正常发光 |

★ **射程只许按 `LightType` 定档，近距遮挡一律交给平面。** `range` 在着色器里同时决定
渐隐曲线（`1 - smoothstep(rmax*0.45, rmax, dist)`），压缩它不只是"截短"，而是把整束光
钉在灯口——实机表现就是"光变成贴在墙上的亮盘"（5.0.1~5.1.1 的教训，日志 `range=1.87`）。
★ `WALL_CAP_MIN` 必须 **≥ 1.0**：灯塞进一格深的凹槽时命中距离可能只有 0.02 格，
端盖跟着贴脸就等于整盏灯不发光（v5.0.0 隐身 bug 的另一种写法）。
★ **任何情况下都不要 `return false` 把这束光整束关掉** —— v5.0 那么做过，结果是贴墙的灯直接隐身。

---

## 四、典型配方

### 探照灯（远处扫射）

```java
WALL(10.0F, 22.0F, 24.0F, "探照灯")   // 窄锥 + 长射程
```
方向仍走单束墙面画圆（`radius`/`yOffset`）；探照灯感靠窄锥（10°/22°）+ 长射程 + 高 `rpm`，别改方向。
锥角越小边缘越硬，10°/22° 已经像探照灯。

### 吊顶灯（走廊照明）

```java
CEILING(45.0F, 75.0F, 10.0F, "吊顶")   // 宽锥 + 中射程 + 垂直向下
```
`CEILING_RADIAL` 必须保持 `0.0D`。加上横向分量会让吊灯变成"旋转探照灯"，
光斜着打在墙面和旁边的机器上，玩家看到的是"灯在乱晃"。

### 地面立杆灯

```java
FLOOR(35.0F, 55.0F, 8.0F, "立杆")
```
大角度是为了让光斑"连着灯"：窄锥会让光从半空掉下来、和灯断开。

---

## 五、调参速查

| 症状 | 改哪里 |
|---|---|
| 光斑离灯太远、像从半空掉下来 | 加大 `cosInner` 对应的**内角** |
| 光斑边缘太硬 | 拉大内角与外角的差（渐隐带变宽） |
| 光斑太大罩住整面墙 | 减小**外角**，别减射程 |
| 光太暗 | 先看 `PEAK`（`AlarmSpotlights`，默认 1.8），再看 `LAMBERT`（shader，0.10） |
| 贴墙看不见光 | 先看 `LightType.WALL` 外角（15/30）与 `radius`/`yOffset`（光斑圆是否落到墙面上），再看射程 |
| 光斑离灯口太远 / 射向对面房间 | 目标点投影法下方向已固定指向墙面圆、不会射对面；若复现说明 WALL 分支又引入了 N（法线）分量 —— 删掉它 |
| 墙上光斑满屏乱飞 | `beamCount()` 又变回 2 条了 —— v5.4.0 起挂壁就是单束（`FLOOR ? 2 : 1`） |
| 光斑在墙上“飘走” | WALL 分支没按 `angleDeg` 旋转（固定方向）—— 改回 `T·(radius·cos) + (0, −yOffset + radius·sin, 0)` 的画圆 |
| 光在墙上成一个硬边亮盘 | 射程被近距命中压缩了 —— 改回端盖平面（`WALL_CAP_MIN`），别动 `range` |
| 光穿墙 | 检查 `trace()` 有没有生成平面；近距命中是否漏了端盖 |
| 灯在狭缝里完全没光 | 这是 v5.0 的旧行为，现在不会了；如果复现，查是否有人加回了 `return false` |

★ **调参数之前先确认场景**：白天白墙 + 加法混合的光本来就看不太出来。
测试用**灰色或深色方块**，或者到夜里/洞里试。

---

## 六、性能

- 每帧只遍历 64 格内的**候选灯缓存**（通常 0~10 盏），每 40 帧或移动 2 格才重扫区块
- 着色器固定 4 个光源槽位，`for (int i = 0; i < 4; i++)`，超出的用 `w = 0` 跳过
- 射线只在**收集阶段**打（每帧每束 1 条，最多 8 条 = `MAX_LIGHTS`），不在着色器里做

---

## 七、绝对不要做的事

1. **不要用 Java 几何体画光斑**（Billboard / 网格射线 / Stencil）—— 项目已全面废弃，
   和 SSS 叠加会出现"柔和光照 + 一圈生硬色块"。
2. **不要写自研 GLSL 光照管线** —— OptiFine/Iris 会整体接管，一装光影就失效或崩。
3. **不要在 `RenderWorldLast` 里改完 GL 状态不还原** —— 所有状态改动必须 `try/finally`。
4. **不要在方块里写第二份"朝向 / 光锥 / 射程"判断** —— 只会造成两边对不上。
5. **不要用 `FBO` 同时当渲染目标和纹理输入** —— 必须读写分离。
