# 列车调度测试矩阵

微分动力的本轮调度补充包含 **412 项默认运行的 GameTest**，另有四种状态各 `prepare`／`resume` 的 **8 项独立 JVM 磁盘重启检查**。它们补充原有 38 项时刻表与坡道测试；动力等级、动力车架数量和倍率关系仍由 [动力定量测试](TRAINS.md#验证) 验证。

## 输入分区与状态转换

| 分组 | 数量 | 覆盖与断言 |
| --- | ---: | --- |
| [状态与列车员](../src/gameTest/java/com/diliupengyun/differential_create/tests/TrainScheduleStateTests.java) | 46 | 前向／后向／双向／缺失列车员，途中失去并恢复；发车前、行驶中及等待时暂停／恢复，取消导航后重试、丢弃及替换时刻表、连续重复当前站、标题和进汽指令；PRE／IN／POST／完成／实体卸载后的保存读回。检查实际到站、当前条目、停靠登记、库存、动量及等待上下文。 |
| [等待条件](../src/gameTest/java/com/diliupengyun/differential_create/tests/TrainScheduleConditionTests.java) | 200 | 延时 0／1／20／40 刻及秒、分钟单位；AND／OR 完整二输入真值表、顺序等待和第二 OR 分支先满足；物品和流体的 LESS／EQUAL／GREATER，各自阈值下／等／上，以及错误过滤物；乘客精确／至少一人时的 0／1／2 人；时刻窗口的前一刻、两个端点和后一刻；交换货物重置空闲等待；站台区块状态、站台红石和无线红石正反向等待。 |
| [路径与目标变化](../src/gameTest/java/com/diliupengyun/differential_create/tests/TrainScheduleRouteTests.java) | 40 | 不存在、改名、错误朝向、通配符及多个同名候选、途中删除目标站、断开的路径接通、途中断轨后恢复；岔路较短／被红灯阻挡／重新汇合成环。核验原生选路、无效目标不推进条目、故障消除后的自动重试和实际到站。 |
| [信号与多列车](../src/gameTest/java/com/diliupengyun/differential_create/tests/TrainScheduleSignalTests.java) | 26 | 强制红灯、另一真实列车占用、真实列车的预约记录、链式信号、红灯等待时读档、删除等待信号；双车厢前轮过界仍不释放，最后车架通过才释放，后车随后到站。检查真实铁路管理器维护的区段占用，区分强制红灯与列车占用。 |
| [资源丢失与恢复](../src/gameTest/java/com/diliupengyun/differential_create/tests/TrainScheduleResourceTests.java) | 28 | 出发前缺水／关阀／无热／无引擎，途中水尽／关阀及重新补给，正常燃料燃尽后转为被动余热，再补煤恢复。检查无动力不离站、不扣不存在的资源；断供保留有限滑行动量；补给不重置条目，并继续原定行程。 |
| [精确数值边界](../src/gameTest/java/com/diliupengyun/differential_create/tests/TrainScheduleBoundaryTests.java) | 72 | 0.1 m/s 爬行速度、1e-6 m 剩余距离、2 m 收阀距离、−10 m 越过目标距离、0.15 m/s 站台与信号停车速度；逐项测试边界前／等／后，覆盖正反向和一／二车厢。检查进汽数值、制动优先级、越界后的导航中断，以及超速越信号时占用区段和保留动量。 |

除状态组里列车员和部分指令场景使用单车厢外，各组在一／二车厢、正反两个方向下展开。等待条件的定量检查包括实际刻数、2000 mB 流体门槛、货物数量和时刻窗口端点；路径与信号用例检查具体目标及区段，不能仅靠“最后停下来了”通过。

## 执行方式与覆盖边界

[场景夹具](../src/gameTest/java/com/diliupengyun/differential_create/tests/TrainScheduleScenario.java) 通过原生装配捕获方块、车厢、双向烈焰人列车员和挂载库存。每次轮询均观察实际世界刻，由 Create 铁路管理器推进行程和时刻表。站点、岔路、环路和信号分区由 [原生 TrackGraph 夹具](../src/gameTest/java/com/diliupengyun/differential_create/tests/TrainScheduleNetwork.java) 构造。

精确到站与信号速度边界直接调用已混入微分动力逻辑的原生 `frontSignalListener`，导航中断距离边界直接调用导航指令处理函数，从而隔离浮点阈值；爬行和开度边界观察一次正常铁路管理器推进。其余到站、等待、资源恢复和多列车测试均推进正常服务器刻，不在测试循环中额外调用 `train.tick()` 或 `runtime.tick()`。

部分环境变化使用受控原生输入：列车员丢失／恢复修改已捕获车厢的资格；无热／恢复修改挂载燃烧室状态；预约测试在另一真实列车上设置原生预约集合；站台未加载条件切换其元数据到未加载区块；无线条件使用注册在原生红石网络中的测试发射器。它们验证调度对输入的处理，不宣称覆盖了杀死列车员、实体站台自然卸载、实际信号方块的拓扑传播或无线方块的完整玩家操作链。

原生 `runtime.paused` 冻结时刻表推进，已发出的导航仍可继续；取消导航后再暂停则保留当前条目供重试。燃料烧尽会退回原生被动余热，不能把“无煤”误断言为完全没有动力。前车停靠时仍占用其终点区段，尾部释放场景为前后车终点设置独立分区。

仍需独立验收的范围包括大型路网中的任意交错与长期活性、跨维度调度、真实站台／信号方块的建造拆除与自然区块卸载传播、多人网络和其他模组轮组。当前测试没有穷尽全部状态组合。

2026-10-16 另在 `differentialcreate_logistics` 命名空间补充了 [8 项包裹取送测试](../src/gameTest/java/com/diliupengyun/differential_create/tests/Create6TrainPackageTests.java)：正反方向、取件／送件、实体邮筒／未加载坐标的离线缓冲。测试使用实际列车、时刻表和原生站点邮件转移，检查物理到站、地址过滤、重复停靠不复制及离线恢复标记。这 8 项通过用例不计入本页原有 412 项或默认完整列车套件总数；入口与交互／生命周期边界见[Create 6 物流覆盖](KINETIC_COVERAGE.md)。

## 磁盘重启

[独立进程测试](../src/gameTest/java/com/diliupengyun/differential_create/tests/TrainScheduleRestartTests.java) 分别保存发车前、已开始但断水的行程、部分完成的站台延时和实际红灯停车状态。每份存档包含两节真实车厢，位于测试重置区域外。准备阶段暂停时刻表以固定检查点，恢复阶段核验不同进程 ID，由原生铁路管理器从世界磁盘数据加载；测试恢复代码不调用 `Train.read`。

恢复时逐项核验条目、阶段、等待上下文、暂停标志、双向列车员、库存和等待信号，再解除暂停或补水／放行，完成剩余行程或精确等待余量。移动中保存和实体卸载保存另由默认状态组覆盖。

```powershell
foreach ($mode in @('pre', 'transit', 'waiting', 'signal')) {
    $trainRunId = 'schedule-' + $mode + '-' + [guid]::NewGuid().ToString('N')
    .\gradlew.bat runTrainCoverageServer "-PtrainRunId=$trainRunId" -PtrainSelect=none "-PtrainRestart=schedule_prepare_$mode"
    if ($LASTEXITCODE -ne 0) { throw "prepare failed: $mode" }
    .\gradlew.bat runTrainCoverageServer "-PtrainRunId=$trainRunId" -PtrainSelect=none "-PtrainRestart=schedule_resume_$mode"
    if ($LASTEXITCODE -ne 0) { throw "resume failed: $mode" }
}
```

## 常规验证入口

```powershell
.\gradlew.bat build runTrainCoverageServer
.\gradlew.bat runTrainCoverageServer '-PtrainSelect=train_schedule_(state|condition|route|signal|resource|boundary)_.*'
```

默认完整列车套件包含 **2308 项**。8 项重启检查单独运行，不计入默认总数；所有测试世界和诊断日志位于忽略的 `build/` 下，不使用用户游戏存档。

## 本轮发现的缺陷

途中删除目标站后，旧导航仍引用已经从轨道图移除的站点；列车停在旧目标附近，原生到站监听器永远无法触发，时刻表保持 `IN_TRANSIT`。现在在导航刻开始时检查目标是否仍为轨道图中的有效站点，失效则取消导航并请求有限制动，使时刻表回到 `PRE_TRANSIT` 自动重试。四项正反向、单／双车厢回归均检查不产生虚假到站，并在同名目标重建后实际抵达。
