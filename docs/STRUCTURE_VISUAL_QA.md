# 动态结构与思索的实机复查

本记录对应 2026-10-29 的修复与验证。测试采用 Minecraft 1.21.1、NeoForge 21.1.238、Create 6.0.10-281。真实客户端、集成服务器、Ponder 和专用 GameTest 分别运行，开发世界和运行输出保存在忽略的 `build/` 下，不修改玩家存档。

## 本轮修复

- 活塞和绳索滑轮碰到障碍时，将实际碰撞方向传回求解器。保持实体期间不再继续积累向障碍内部的速度；移除支撑、反转和读档后重新检查接触。
- 完全收回、保留实体的普通执行器释放原生等待转速变化标记；端点只支撑越界方向，重力仍能使结构向有效行程内运动。升降机的楼层保持沿用专用控制器。
- 起重机出生的前两个实体 tick 同时暂停物理输出，消除实体未移动却提前积累下落速度的启动尖峰。
- 集成服务器与客户端分别保存待消费位移和碰撞状态，避免客户端实体加载、移除影响服务器遍历并导致并发修改异常。
- `/tick freeze` 后的 `/tick step` 使用原生实际运行状态推进动力学。冻结仍停止积分，单步不再被冻结标记误挡。
- 时钟轴承的转速变化通知不再反复进入装配分支并丢弃已积分角度；时针和分针使用真实目标角作为限位，消除把“预计一 tick 的角位移”当成终点造成的间歇停转。
- 多绳滑轮思索的副绳跟随主绳；升降机思索通过内部传动换向，保持输入轴方向。画面整体平移与轿厢物理位移分别处理，切换图解和重新装配时绳头不脱离轿厢。
- 多绳教程按实际载荷运动预留时间，到达端点后才继续换向和字幕阶段。

重力仍为 9.81 m/s²；本轮不改变质量、阻力标定及原生拆装取整政策。修复前后的加速度记录见[动态结构说明](CONTRAPTIONS.md#游戏内加速度实测2026-10-28)。

## 真实世界搭建矩阵

[StructureClientAudit](../src/gameTest/java/com/diliupengyun/differential_create/tests/StructureClientAudit.java) 在可见客户端中放置原生方块和动力源，操作原生装配、楼层目标及红石信号。运行时不指定实体位置、速度，也不以手工调用求解器代替服务器 tick。

| 类别 | 搭建覆盖 | 操作与检查 |
| --- | --- | --- |
| 机械轴承 | 六朝向 × 偏心、平衡、嵌套三种负载 × 三种放置模式，共 54 种 | 装配、撤动力、重新供能、反转；嵌套子结构在五个渲染子帧检查世界朝向 |
| 机械活塞 | 六朝向 × 黏性/普通 × 三种放置模式，共 36 种 | 8 根杆、不同货台宽度、障碍、撤动力、反转、完全收回 |
| 起重机 | 六朝向 × 两种取物器安装面，共 12 种 | 长杆运动、撤动力、反转、真实红石块停机拆回 |
| 时钟轴承 | 六朝向实机；另有三种模式 × 六朝向双指针 GameTest | 时间改变、连续追踪、到点保持、再次改变时间、正反输入 |
| 绳索滑轮 | 两轴向 × 两负载 × 两种放置模式，共 8 种 | 下放、障碍支撑、撤动力、收回；另有单步移除支撑的手动复查 |
| 升降机 | 两轴向 × 单绳/双绳，共 4 种 | 楼层往返、撤动力、重新启动；双绳逐子帧核对真实渲染长度 |

每种搭建保留服务端逐 tick 数据、客户端实体状态和截图。重点截图位于装配、撤动力、反转、红石拆回前后以及末段；客户端先等待区块与渲染准备完成，避免把加载中的截图误判成实体消失。升降机副滑轮固定在非黏性端块上方，避免被货台的黏液块一起装入结构；测试检查原生镜像关系及客户端共享轿厢，不能用副滑轮不会更新的服务端 `offset` 判断绳长。

运行示例：

```text
./gradlew runStructureClientAudit
./gradlew runStructureClientAudit '-PstructureClientSelect=bearing_.*_2_[012]'
./gradlew runStructureClientAudit '-PstructureClientSelect=elevator_.*' -PstructureClientHold=true
```

`structureClientReport` 可指定报告目录，默认 `build/full-audit/world`。`structureClientHold=true` 在自动搭建完成后保留世界，供手动观察、红石操作、冻结单步及保存重进。通过标记只代表自动断言通过，截图仍须人工复查。

## 思索回放与视觉检查

全量回放覆盖 52 个补充注册入口、18 个主题及 168 个原生场景变体。运动关键场景另外执行“正常播放、跳过、再次正常播放”，检查重播不会残留旧绑定或角度。关键帧截图覆盖场景段落及末帧；结构相关的 20 段连续视频以 60 fps 记录，针对装配、换向、图解平移和拆回边界密集抽帧。

[PonderLiftAudit](../src/gameTest/java/com/diliupengyun/differential_create/tests/PonderLiftAudit.java) 在五个子帧检查轿厢与绳头接触，并检查确实发生上行、下行以及到达端点后再换向。连续视频与关键帧使用真实 Ponder 渲染器；截图不是重新绘制的示意图。

```text
./gradlew runPonderClientSmoke -PponderAudit=true
./gradlew runPonderClientSmoke -PponderAudit=true -PponderCapture=false
./gradlew runPonderClientSmoke -PponderAudit=true -PponderVideo=true '-PponderVideoScenes=native-rope_pulley_multi_rope,native-elevator_pulley,native-elevator_pulley_multi_rope'
```

默认截图和逐帧诊断位于 `build/ponder/previews/audit/`；本轮视频、实机矩阵和复查图保留在 `build/full-audit/`。

2026-10-29 最终回放生成 885 张关键帧，视觉复查其中 313 张，覆盖全部 186 个场景/变体及结构教程的所有段落。20 段连续视频先检查完整时间顺序的抽帧，再检查换向、图解平移和重新装配附近的密集帧；不是宣称人工看过全部视频的每一帧。多绳普通滑轮的最终完整时长为 47 秒，升降机主教程为 106.15 秒，升降机多绳教程为 27.5 秒，三段最终运动审计均无问题。

## 自动回归入口

```text
./gradlew build runGameTestServer
./gradlew runMotionCoverageServer
./gradlew runGravityServer '-PgravitySelect=clock.*,gravity.*,kineticTickStep,motionThreadIsolation'
./gradlew runGravityServer '-PgravitySelect=measure.*,gravity.*'
./gradlew runSelectedRegressionServer '-PregressionSelect=joiningDifferentWetLinesPreservesIdentityAtBlockedReceivers' -PregressionRepeat=10
```

流体回归中的异种液体接入测试改为等待两侧真实完成充液，再隔离原液体支路并制造不兼容队首；不再依靠固定等待时长和多个泵的竞争结果猜测测试边界。此项改动只调整测试夹具，未修改流体生产实现。

## 本轮结果与留档

| 检查 | 结果 | 本地留档（均未入库） |
| --- | --- | --- |
| 最终构建与完整回归 | 2635 项数值检查、274 项必选 GameTest 通过 | `build/full-audit/regression-complete.log` |
| 运动覆盖套件 | 1394 项必选 GameTest 通过；随后时钟修复另经专门测试和最终完整回归 | `build/full-audit/motion-final.log` |
| 时钟六朝向、双指针三模式 | 连续运动、两次目标变更和保持通过 | `build/full-audit/clock-hands-final.log` |
| 原生与补充思索 | 52 个补充入口、18 个主题、168 个原生场景变体通过 | `build/full-audit/ponder-complete.log` |
| 流体边界稳定性 | 修正夹具后独立重复 10 次通过 | `build/full-audit/fluid-repeat-primed.log` |
| 嵌套轴承客户端 | 18 种搭法均实际观察到子结构，五个子帧的世界朝向检查通过 | `build/full-audit/nested-confirmed-final.log` |
| 升降机客户端 | 四种搭法到站与停稳检查通过；双绳真实渲染长度同步 | `build/full-audit/elevator-persistence-final.log` |

真实客户端累计检查 120 种不同搭建，分轮保存并对发现的问题复测。原始数据保留每一轮，包括曾失败的测试夹具；不能将一个中途失败的客户端整轮日志写成全量通过。最终各搭建的数据索引见 `build/full-audit/verified-world-cases.json`。

手动进入专用测试世界后，还执行了滑轮支撑移除与冻结单步，以及双绳电梯的红石呼叫、保存退出、重新进入和返回上层。停层时读取到实体输出转速为 0；输入轴剩余惰转与实体输出分开。电梯测试程序的楼层请求补上原生调用也执行的 `markDirty()`，避免测试世界保存旧目标，并在上下层分别断言到站且输出停稳。

本矩阵覆盖常见搭法和已发现的边界，未穷举任意结构、所有附属模组或多人网络环境。多人中途加入、维度切换及其他附属的特殊行为仍应按具体复现继续扩展测试。
