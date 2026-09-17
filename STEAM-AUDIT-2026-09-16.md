# 蒸汽机检查记录（2026-09-16）

本文件保留修复前的检查结论。后续实现与验证见 [蒸汽机修复记录](STEAM-FIXES-2026-09-16.md)。

检查对象是当前工作区，包括已有的未提交改动；依赖为 Create 6.0.10-281、NeoForge 21.1.238。本轮仅检查和复现，没有修改业务代码。

结论：确认两类动力逻辑缺陷，另发现一处锅炉显示口径遗漏。五项专项 GameTest 中四项失败，对应两类缺陷；正常锅炉参数对照通过。

## 1. [P1] 同向并联的蒸汽机会自动变成相反方向，无法正常启动

- 项目位置：`src/main/java/com/diliupengyun/differential_create/kinetics/NetworkTopology.java:264`，以及同文件设置成员 `source` 的逻辑（第 277–290 行）。
- 依赖调用：Create 的 `SteamEngineBlockEntity.tick()` 第 121–128 行用 `shaft.getTheoreticalSpeed()` 判断方向；当轴 `hasSource()` 时，会直接修改发动机的旋转方向设置。
- 当前网络安装时把名义速度设置为 `16 * ratio`，其符号不代表实际运动方向。并联机组的从属动力轴因此把参考值 +16 当成了真实正转。

复现：在 2×2×2 的实际储罐上安装两台朝北、朝向设置相同的蒸汽机，使用相邻的 X 轴动力轴直接连接。固定供汽等级为 2。经过 40 tick，无须调整速度或手动反转，就得到：

```text
ordinary startup rpmA=0.0 rpmB=0.0 directionA=-1 directionB=1 boilerLevel=2 engines=2
```

另一项定向测试把同一网络实际轴速设为 −64 RPM，从属轴名义速度仍为 +16 RPM；调用发动机 tick 后，其方向立即从 −1 改成 +1。

影响：与世界坐标和安装朝向有关的启动失败、并机互相制动，以及反转设置被自动改写。后续应适配蒸汽机的自动选向逻辑，明确静止、惰行、并联及主动反转时的规则；不能继续使用固定的名义速度符号。

## 2. [P1] 供汽来源不可用时，动力轴仍会用存档中的旧效率输出扭矩

- 项目位置：`src/main/java/com/diliupengyun/differential_create/kinetics/SourcePhysics.java:29` 至第 47 行。
- 方法先把 `shaft.engineEfficiency` 当作锅炉等级、把发动机数量设为 1。发动机未加载、不存在，或 `engine.getTank()` 返回 null 时，不会清零，而是直接返回这组回退参数计算出的动力曲线。
- `PoweredShaftBlockEntityMixin` 又直接把该曲线接入 `getGeneratedSpeed()`，因此服务端仍把这根轴视为动力源。

两个边界测试分别恢复一份真实动力轴 NBT 到发动机不可用的位置，以及让实际储罐指向不可用的控制器；结果都是：

```text
noLoad=-128.0 stall=-512.0
```

这表示轴仍提供绝对值为 512 su/RPM 的堵转扭矩，并非只保留惰行速度。旧 `engineEfficiency` 还是被发动机数量分摊并截断至 1 的效率，不能还原真实锅炉等级，因此回退也会改变动力特性。

影响：跨区块加载顺序或供汽源暂时不可用时可能凭旧状态继续驱动。源码确认发动机因区块卸载而 `setRemoved()` 时，不执行拆除发动机的 `remove()` 回调，不能依赖该回调清空轴的效率。应在服务端缺失有效供汽链时返回零驱动力；客户端若需要显示缓存，应与服务端动力参数分开处理。

验证边界：本轮复现的是“来源不可用”分支和 NBT 恢复行为，没有完成自然跨区块卸载、重载的整套游戏场景。控制器缺失测试检查的是当前分支，不声称其输出一定持续；发动机后续 tick 可能清除该状态。

## 3. [P2] 锅炉护目镜仍使用原版容量公式，与新动力模型不一致

- 新模型：`src/main/java/com/diliupengyun/differential_create/kinetics/SourcePhysics.java:54`。
- 未适配入口：Create `BoilerData.addToGoggleTooltip()` 第 209–211 行计算原版 `totalSU`，第 231–241 行将其显示为提供的应力容量。项目当前没有锅炉提示适配。
- 等级 2、单发动机、默认 capacity=1024 时，新曲线为 `|ω₀|=256`、`k=4`，最大输出功率为 `k×ω₀²/4=65536 su`；原版锅炉提示公式仍得到 `32768 su`。

旧应力容量与新动力模型在同一套护目镜中混用，用户无法据此判断可用动力。建议保留锅炉的尺寸、供水、热量状态，移除旧容量字段，或明确显示按新曲线计算的额定/实际功率。

验证边界：已对照依赖源码并在服务端验证两套公式的结果，没有进行客户端护目镜画面检查。

## 验证与复现材料

既有数值回归通过：物理 2597 项、渲染数学 97 项、扭转 34 项。这些通过不能覆盖以上蒸汽机生命周期和方向问题；已有蒸汽机测试只循环调用纯参数版 `steamCurve(1024, 3, count, 1)`。

本轮专项结果：

| 测试 | 结果 | 含义 |
| --- | --- | --- |
| `ordinaryTwoEngineStartup` | 失败 | 普通并联装配出现相反驱动方向 |
| `parallelReverseEngines` | 失败 | 实际负转速被名义正转速覆盖方向 |
| `unavailableEngineSnapshot` | 失败 | 无可用发动机的恢复状态仍提供扭矩 |
| `unavailableBoilerController` | 失败 | 无可用锅炉控制器时仍返回非零动力曲线 |
| `liveBoilerParameters` | 通过 | 实际方块关联下 1–36 台发动机参数、断水、熄火、被动供热符合当前设计 |

夹具使用真实储罐、发动机、动力轴、Mixin、网络拓扑和服务器 tick；直接固定热量与供水输入，未验证完整水泵及燃烧器供给链。

材料保存在工作区的 `build/steam-audit/`（受 `.gitignore` 排除，执行 clean 会删除）：

- `src/com/diliupengyun/differential_create/tests/SteamAuditTests.java`：专项测试。
- `steam-audit.init.gradle`：临时注入测试源码，使用独立编译输出、资源和测试世界。
- `verified.log`：修正测试夹具后的最终结果。
- `run.log`：首次试跑；其中护目镜调用因仅允许客户端运行而失败，已从服务器测试中移除，不列为产品缺陷。

在 Java 21 环境下复现：

```powershell
.\gradlew.bat --no-configuration-cache -I build/steam-audit/steam-audit.init.gradle physicsTest runGameTestServer
```

当前预期是 GameTest 报四项断言失败，Gradle 因此返回失败；源码编译和数值回归均通过。本机另使用了 README 已记录的临时 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/codex-unavailable-unix-sockets` 处理 Java 内部通信问题。
