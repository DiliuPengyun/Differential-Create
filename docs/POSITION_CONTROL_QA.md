# 时钟与升降机的有限力矩控制验证

本记录对应 2026-10-29 第二阶段修复，接续[动态结构与思索实机复查](STRUCTURE_VISUAL_QA.md)。测试环境为 Minecraft 1.21.1、NeoForge 21.1.238、Create 6.0.10-281；所有临时世界、采样和视频保存在忽略的 `build/` 下。

## 行为变化

- 时钟轴承的两只指针各自保存惯量、速度和累计角度。时间变化只改变目标，指针通过有限力矩加速、制动、换向；一只指针到点不会清零另一只的速度。六朝向、原生三种模式、嵌套稳定子结构仍使用同一物理坐标。
- 升降机使用固定向下为正的坐标。切换楼层不会翻转既有速度，到站依靠有限制动，不再执行原生到站瞬移或直接规定速度曲线。输入转向通过内部换向离合器处理。
- 位置控制只调节被动离合器与制动器。目标本身不能供能；无输入时制动，负载超过制动力时允许滑动。真实碰撞和行程端点仍是接触约束，玩家拆装仍保留原生取整规则。
- 当前格式读档保留每只指针及轿厢的独立动量；子结构未加载完整时暂停相关网络。旧时钟缺少独立指针速度，首次升级保留角度并从静止接管；旧升降机坐标只迁移一次，转换不增加动能。单精度位置的最后几个有效位用容差处理，防止到站后永远微动。
- 客户端确认姿态覆盖时钟顶盘、双指针、直线货物及绳长，停用重复的原生速度外推。最终协议为 25，并同步独立指针的速度和惯量。每 tick 样本之间仍是插值，不能据此宣称显示轨迹在数学上处处速度连续。
- 负载表按各指针实际速度计算重力功率、阻力和制动损耗；不把静止指针的负载算在旋转输入上。电梯接触损耗使用带传动方向的相对速度，同步反向传动不会显示假滑移损耗。
- 实机护目镜复查发现局部惯量漏计两只指针，局部动能也未计独立输出。现在分别按服务器同步的实际速度计算；静止指针不产生假动能，等待快照期间显示未知值。
- Ponder 中已装配的时钟和升降机接入相同控制方法。多绳升降机教程增加行程时间，确认到站后才反向。未装配货物的空绳演示继续遵守故事板行程上限。

没有装配输出的时钟和升降机不启用高频位置控制；已装配控制器使用共同事件时钟，控制间隔上限为 1/64 tick，接触等事件可以进一步细分。

## 数值与真实服务端

[PositionServoRegression](../src/physicsTest/java/com/diliupengyun/differential_create/PositionServoRegression.java) 使用生产求解器，625207 项检查通过，覆盖多种惯量、正反输入、逐步有限加速度、移动中改目标、到点保持、无动力目标变化、制动超载滑动，以及切换传动时的被动能量约束。直线精度检查覆盖 4、128、512 米位置。

[PositionControlTests](../src/gameTest/java/com/diliupengyun/differential_create/tests/PositionControlTests.java) 在真实服务器 tick 中装配与移动实体，不通过测试脚本写入运动轨迹：

- 双指针在六朝向下多次改目标，检查角位移和速度增量、单针保持、待消费角度与实体角度一致；向下安装包含嵌套机械轴承及子结构。
- 保存并重建控制器和实体，子结构故意晚两 tick 加载，检查加载期间不丢动量、完整后继续运动。
- 电梯覆盖三层目标、运动中反向、撤动力、旧坐标迁移、当前格式重载与最终停稳。
- 空控制器、装配后和拆解后三个阶段检查控制时钟是否应启用。
- 运动、停稳与拆解后核对护目镜惯量和动能，检查转子包携带每只指针的独立状态；编解码覆盖双指针一动一停、反向速度和累计多圈角度。

完整常规回归的 276 项必选 GameTest、运动覆盖套件的 1394 项必选 GameTest 通过。随后补充的两项负载读数断言先复现了静止时钟的无限假负载、电梯同步换向的假滑移损耗；修正统计后再次运行 `build` 及 9 项控制、重载、网络读数专项，全部通过。补齐护目镜独立指针读数后，再次构建并通过上述 9 项专项。读数探针在同一个测试回调内恢复临时字段，不参与实体运动轨迹。

该专项保留 3360 行双指针采样和 355 行电梯采样，已检查位置、速度曲线；电梯最后停在 3.000004768 米，输出为 0 RPM。采样副本在 `build/continuous-control/final-samples/`，曲线在 `build/continuous-control/position-control-traces.png`。曲线中的子结构延迟加载暂停和旧格式迁移耗散是单独标出的测试事件，不能与正常运行的目标切换混为一谈。

## 可见客户端与视觉复查

真实客户端重新搭建并运行全部 120 种结构：机械轴承 54 种、活塞 36 种、起重机 12 种、时钟 6 种、绳索滑轮 8 种、升降机 4 种。搭建矩阵及入口见[第一阶段记录](STRUCTURE_VISUAL_QA.md#真实世界搭建矩阵)。自动断言检查实际装配、运动、反向与停机，并在五个渲染子帧核对嵌套世界朝向、时钟顶盘与指针、双绳长度。

人工逐张检查 40 张全量截图拼图，每种搭建选取五个时刻。发现部分截图被物品栏遮挡或视点偏离后，重跑 12 种相关搭建并复查四张截图拼图；补测均通过。测试工具增加“前段、后段都有无遮挡截图”的要求，防止以后仅凭数值通过就接受不可见的画面。完整原始轮次与补测均保留，最终索引为 `build/continuous-control/verified-world-cases.json`。

手动存档轮次使用协议 24 的可见客户端重跑双指针时钟和双绳电梯，并操作原生红石接触器、冻结单步、保存退出和重新进入。电梯在下行途中保存前读取到输出约 49.456 RPM，重进后继续到达下层，输出为 0；再由上层红石呼叫返回，输出仍为 0，两个绳头保持贴合。这个人工检查确认实际存档和画面，精确的重载瞬间速度连续性由逐 tick 专项测试断言。协议 25 的新增字段只补齐读数，不改变该轮验证的运动和存档格式。

时钟在冻结状态下把时间从 6000 改到 6500，推进十 tick 后再改到 7000，分别检查目标变更当下、下一 tick 和后续十 tick 的画面。改目标当下指针保持原姿态，推进后分针反向、时针独立向新目标移动；恢复正常运行后两针各自停在目标角度。

协议 25 再跑六朝向时钟，检查真实客户端中的双针惯量、运动与停稳动能、待同步时的未知读数，全部通过。另复查两张截图拼图，并实际佩戴护目镜近距离确认：该搭建的局部惯量由漏计指针时的 0.01 改为正确显示 0.02。客户端逐次读数留在各案例的 `clock-readout.csv`；例如南向场景在第 40 tick 的指针动能约为 9.4094，停稳后为 0，输入转子的动能仍单独保留。

## 思索与边界画面

全量回放通过 52 个补充注册入口、18 个主题、168 个原生场景变体。时钟、升降机、双绳升降机和双绳普通滑轮另外以真实渲染器录制 60 fps 视频，运动审计均无问题：

| 场景 | 视频长度 | 帧数 |
| --- | ---: | ---: |
| 时钟轴承 | 91.20 秒 | 5472 |
| 升降机 | 106.15 秒 | 6369 |
| 双绳升降机 | 36.25 秒 | 2175 |
| 双绳普通滑轮 | 47.00 秒 | 2820 |

先按时间顺序抽查完整流程，再在装配、换向、图解平移、到站附近密集抽帧。已复查 79 个全程抽帧位置及 72 个边界抽帧位置；这不是人工检查全部 16836 帧的声明。自动子帧检查与人工画面复查相互补充。

## 复现与留档

```text
./gradlew build runGameTestServer -PregressionRunId=continuous-control-final-20261029
./gradlew runMotionCoverageServer
./gradlew runGravityServer '-PgravitySelect=clock.*,elevatorContinuousControl,gravityElevator.*,nestedContraptionReload,networkLoadFactor'
./gradlew runStructureClientAudit -PstructureClientReport=build/continuous-control/world-full
./gradlew runStructureClientAudit '-PstructureClientSelect=bearing_east_.*,bearing_down_0_[01],piston_south_0_0' -PstructureClientReport=build/continuous-control/visual-recheck
./gradlew runPonderClientSmoke -PponderAudit=true -PponderCapture=false
./gradlew runPonderClientSmoke -PponderAudit=true -PponderVideo=true '-PponderVideoScenes=native-clockwork_bearing,native-elevator_pulley,native-elevator_pulley_multi_rope,native-rope_pulley_multi_rope'
```

| 检查 | 结果 | 本地留档 |
| --- | --- | --- |
| 最终构建、数值与 9 项专项（含负载与护目镜读数修复） | 通过 | `build/continuous-control/goggle-readout-final.log` |
| 完整常规 GameTest | 276 项通过；随后读数统计修复另经上述专项 | `build/continuous-control/regression-final.log` |
| 1394 项运动覆盖 | 通过 | `build/continuous-control/motion-full.log` |
| 120 种可见客户端搭建 | 自动检查通过，已逐组复查代表帧 | `build/continuous-control/client-full.log`、`world-full/` |
| 12 种无遮挡补测 | 通过 | `build/continuous-control/visual-recheck.log`、`visual-recheck/` |
| 最终客户端及手动存档测试 | 通过 | `build/continuous-control/manual-final.log`、`manual-final/` |
| 协议 25 六朝向时钟及护目镜 | 通过，已复查代表帧及实际惯量显示 | `build/continuous-control/goggle-client.log`、`goggle-client/` |
| 全量 Ponder | 通过 | `build/continuous-control/ponder-second.log` |
| 四段连续视频 | 审计通过，已复查全程和边界抽帧 | `build/continuous-control/ponder-video.log`、`video/` |

较早的 `regression.log` 在修复空控制器的高频空算前启动，因耗时过长中止，不能算作完整通过。最终版本重新运行完整回归，使用独立测试世界与 `regression-final.log` 留档。

未穷举任意尺寸结构、所有附属模组或多人延迟环境。多人中途加入、真实跨区块卸载和维度切换仍应按具体复现继续扩展；不能将本次矩阵解释为所有组合均无 bug。

## 高频控制的 TPS 优化

2026-10-29 随后在实际存档中复现了装配时钟后约 15 TPS 的问题。原实现的共同事件时钟把全部网络带入 64 次控制细分，每次都重新扫描成员、查找动态结构、排列求解索引和建立连接。第一次 JFR 中，服务端执行样本约 92% 落在 `MachiningClock.advanceRotors` 内；这包含下级调用，不能与其他占比相加。

优化保留原来的控制间隔与求解方法。在一次服务端物理 tick 内复用结构成员、执行器归属、惯量、工作绑定及求解图索引；装配、拆回、世界工作回调、网络重建和形状改变会使对应缓存失效。速度、角度、重力、接触力、目标、制动和离合器摩擦仍读取当前状态，蒸汽源和其他未知发电源的实时特性也继续重新计算。缓存到 tick 结束即释放，不跨 tick 保存这些世界读取结果。

没有直接把 64 次降到 8 次：在相同模拟时长下重跑生产求解器，可编程齿轮箱的 8、16 次版本在部分负载下无法停稳，32 次版本未通过断动力后的被动能量账本检查；64 次通过 356 个案例、4855762 项检查。位置控制器的基础回归在四种频率下通过，不能据此推断可编程齿轮箱也能降频。临时实验只改隔离副本，生产代码保持 64 次。

本轮重新通过常规 276 项 GameTest，随后新增缓存失效回归，并在最终实现上通过 6 项专项和全部 1394 项运动覆盖。新增回归在启用生产缓存的同一个作用域内检查运动后重采样、旧快照不变，以及同 tick 拆回再装配后实体归属和移动工具绑定及时更新。

最终实现的真实客户端重新搭建全部 120 种结构，自动运动和五个渲染子帧检查通过；逐张复查 40 张截图拼图，覆盖每种搭建约第 25、70、120、141、178 tick 的画面。发现起重机拆回时存在短暂消隐：`gantry_down_0_0` 第 139 帧有实体，第 141 帧消失，第 143 帧只有已放置的齿轮箱，第 150 帧完整方块恢复。上一阶段原始截图也存在同样现象，属于实体显示交接给区块网格时的既有问题；本轮 TPS 优化没有修复它，自动运动断言通过不代表这个视觉问题消失。

本轮记录保存在 `build/tps-investigation/`：`game-tests.log` 为常规回归，`motion-client-tests.log` 为最终专项、运动覆盖和客户端运行，`world-regression/` 保存逐 tick 数据与原始截图。新增的 [PhysicsPerformanceAudit](../src/gameTest/java/com/diliupengyun/differential_create/tests/PhysicsPerformanceAudit.java) 仅在开发源集中，通过 JVM 属性 `differentialcreate.performanceReport` 启用；另可设置 `differentialcreate.performanceWarmup` 与 `differentialcreate.performanceSamples`。它记录完整服务端 tick 耗时和开始时间间隔，不改变世界规则、机器或目标 tick rate，且不包含在发布 JAR 中。

### 同存档对照结果

分别将原存档复制为旧版与优化版的独立测试世界，顺序运行两个真实客户端。旧版从提交 `3bef215` 导出，只增加同一性能观察器。两边均预热 600 tick，保留视距 12、模拟距离 12、垂直同步和 120 FPS 上限，仅在副本中关闭失焦暂停。没有移除机器或生物，两个报告均为 104 个网络、326 个动力成员，各类成员数量完全一致。原存档未被修改。

| 指标 | 旧版 | 优化版 |
| --- | ---: | ---: |
| 采样 tick 数 | 2400 | 6000 |
| 采样实际时长 | 153.93 秒 | 299.95 秒 |
| TPS | 15.58 | 20.00 |
| 平均 tick | 64.03 ms | 32.50 ms |
| 中位数 | 61.47 ms | 31.40 ms |
| P95 | 79.38 ms | 40.10 ms |
| P99 | 88.90 ms | 46.97 ms |
| 最长单 tick | 111.65 ms | 82.86 ms |
| 超过 50 ms 的 tick | 2400 / 2400 | 31 / 6000 |
| 最慢连续 200 tick 窗口的 TPS | 15.04 | 19.93 |
| 最慢连续 1200 tick 窗口的 TPS | 15.35 | 19.99 |

200、1200 tick 分别对应目标 20 TPS 下约 10、60 秒的窗口；旧版掉速时这些窗口的实际时长更长。优化版前 2400 tick 单独统计也为 20 TPS，平均 33.03 ms、P95 41.44 ms；完整五分钟平均 tick 耗时下降约 49.2%。仍有孤立尖峰，超过 50 ms 最多连续两个 tick，因此结论是这个存档在本机保持约 20 TPS，不能承诺每一个 tick 都小于 50 ms，或任意规模的其他存档也有相同性能。

原始结果在 `matched-baseline/measurements/`、`final-pass/measurements/`；对照与等长窗口统计在 `performance-comparison.json`，最终发布包构建记录为 `final-build.log`。这些路径均相对于 `build/tps-investigation/`。
