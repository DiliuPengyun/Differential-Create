# 微分动力：动力行为覆盖矩阵

可编程齿轮箱的模型见 [DESIGN](../DESIGN.md#61-可编程齿轮箱)。本版使用有限驱动、有限壳体制动和有惯量的独立输出转子；目标规划按实际负载反馈重算。测试验证真实位置、出口速度、输入反力、耗散和程序状态，不以指令计数增加作为定位成功的依据。

## 齿轮箱数值划分

入口：[SequencerRegression](../src/physicsTest/java/com/diliupengyun/differential_create/SequencerRegression.java)。同一个生产求解器推进输入转子、独立输出转子、零惯量轴段和真实负载，期望由位置目标、机械功上界与被动性独立约束。

| 等价类 | 代表取值或断言 |
| --- | --- |
| 方向与档位 | 输入正负转；±1、±2 档；不能瞬间改变速度或制造驱动功 |
| 输出惯量 | 0.01、0.5、8；输入另有独立惯量；负载越重规划制动越早 |
| 被动阻力 | 0、64、512；零速静摩擦、移动阻力、阻力辅助制动 |
| 轴段 | 0、1、3 个零惯量中间节点；输出转子仍有真实惯量 |
| 连续程序 | 16 种两步档位组合；后续 1°、5°、90°；保留中间事件，同向不要求停车，反向先停车 |
| 停车障碍 | DELAY、AWAIT、END；位置或速度单独满足均不能完成 |
| 距离与速度 | 一格距离；1、16、256 RPM；断供后继续同一目标 |
| 外部驱动 | ±64、±512；制动能力不足时不伪造到位 |
| 原崩溃状态 | 原九节点速度、零惯量轴段、不平衡弹簧预紧和 89.87379073472506° 小数进度；新输出转子之外不重置状态，验证到位、无断轴与被动能量账目 |
| 零速启动 | 暂无输入时保留程序；首次实际输入决定正反转基准 |
| 能量 | 每个接受的时间片核对储能增量不超过供给功扣除制动功；耦合力矩有上限；累计制动功非负 |

2026-10-09 的验证记录中本组有 356 个机械场景；当前数量以本次回归输出为准。大量时间片断言不等于同样数量的独立行为。反复采样用于抓住中途超限、短指令过冲和停车附近振荡。

## 真实游戏划分

入口：[SequencerIntegrationTests](../src/gameTest/java/com/diliupengyun/differential_create/tests/SequencerIntegrationTests.java)，独立命名空间 `differentialcreate_motion`。每例在普通服务端 tick 中操作真实创造马达、齿轮箱、轴、飞轮或轴承；初始化和变更动力通过原生设置，停车后输入继续旋转。用例结束清理自建动力方块，避免相邻批次形成传动旁路。

| 分组 | 数量 | 内容 |
| --- | ---: | --- |
| 指令和朝向 | 360 | 三轴 × 输入正负 × 四档 × 15 程序：单步、连续、降档、升档、反向、短尾段、延时、红石等待、距离、角度/距离混合、零目标、两个完整旋转、128 格距离、600 tick 延时、90° 运动快照 |
| 生命周期 | 66 | 三轴 × 正负 × 11 场景：当前格式重载、断供恢复、输入反转、加负载、拆装输出轴、重启程序、红石启动、只读试算/冻结、持续高电平等待、零速红石启动、反接零速启动 |
| 反接端口 | 48 | 三轴 × 马达设置正负 × 四档 × 单步/连续；朝向负侧供给，验证参考侧互换与原生马达方向约定 |
| 实际运动结构 | 24 | 三轴 × 正负 × 单步/连续/反向/短尾段；两方块轴承结构实际角度到位，原生序列几何限位未参与停车 |
| 惯量维护 | 54 | 三轴 × 正负/零速 × 新惯量 0/0.01/0.25/0.5/1/8；质量增加守恒动量，质量移除带走储能，重复刷新不重复降速 |
| 虚拟演示 | 1 | Ponder 虚拟方块沿用原生延时与红石等待，不建立真实转子目标和伺服存档 |
| 输出装配相位 | 24 | 三轴 × 两侧接线 × 对齐/扩展/重载/双输出；以报告存档的两个不同角度零点复现接缝，保留实际弹簧扭转，校正后控制坐标、目标、速度与储能不变 |

上表列出的齿轮箱分组合计 577 例，不是当前完整运动套件的总数；其他机器、接口和矿车场景另计。比较器保留原生步骤更新和有信号时的结束锁存；读档比较双精度位置、速度、目标和进度；冻结验证程序与输出都不前进；丢弃试算验证结果不受采样顺序影响，也不修改实际输出。运动过程中逐 tick 核对输出端与相邻轴的实际扭转关系；90° 快照组覆盖中段真实旋转和停车前后，核对输出相位与普通转子在同一份网络消息中无损往返。

## 其他板块补测与既有覆盖

新增 [KineticPartitionRegression](../src/physicsTest/java/com/diliupengyun/differential_create/KineticPartitionRegression.java) 的 2526 个代表等价类，补充动力曲线、调速器、工作计数、公共时钟、相位图和负载读数的组合边界。

| 板块 | 新增判据 | 既有入口 |
| --- | --- | --- |
| 蒸汽与普通驱动 | 零功率、无限功率、恒扭矩/恒功率临界两侧；正反转对称；力的导数与非线性迭代一致 | SteamDriveRegression、SteamEngineTests |
| 调速器 | 禁用、零范围、调速带内/边界/带外；反向运动；收汽单调且不会增加驱动 | SteamDriveRegression |
| 工作与接触债务 | 非有限量、负量、零量、整数两侧、超整数上限；128 个固定种子各 512 次推进/借支/重载；二进制精确账本 | PhysicsRegression、ContinuousMachineTests、MachiningTests |
| 工作溢出 | 两个有限大值相加溢出时拒绝新推进，保持原余量，不能生成无限进度 | WorkAccumulator |
| 相位恢复 | 512 个不同大小、方向、闭环及断连图；保留各分量锚点、参考侧互换、幂等与无输入修改 | RenderingRegression |
| 公共事件时钟 | 极短/普通/长区间，初端/中间/末端事件，已达/未达边界 | BoundarySearchRegression、BoundarySnapshotTests |
| 负载与损耗读数 | 改转向或参考基准不能改变功率分类；弹性、阻尼、滑移、零咬合的损耗非负 | LoadFactorRegression、LoadFactorTests |
| 弹性与强度 | 已有能量、扭矩、断裂、稀疏/树求解、接合与轴替换测试继续全量执行 | TorsionRegression、TreeSolverRegression、ElasticKineticsTests、ShaftReplacementRegression |
| 动态工具与结构 | 保留钻、锯、机械手、碾压、收割、耕地、嵌套结构和实际接触回归 | ContinuousActorTests、ContraptionPhysicsTests、NestedContraptionTests |
| 流体与共享时钟 | 继续执行完整真实流体专项，检查机械改动没有改变液体库存、接口或动力耦合 | [流体覆盖矩阵](FLUID_COVERAGE.md) |
| 客户端数学 | 输出装配相位与相邻轴对齐，普通渲染和实例化 shader 使用同一相位；复现两路样本差一 tick 导致中段错位，验证同一物理快照的完整、缺失、合并交付与只读渲染查询；覆盖停靠、反转、冻结与两种模型静态偏移；既有零包/双包与轴承姿态回归继续执行 | RenderingRegression |

## 背罐充气

入口：[BacktankChargeRegression](../src/physicsTest/java/com/diliupengyun/differential_create/BacktankChargeRegression.java)、[BacktankIntegrationTests](../src/gameTest/java/com/diliupengyun/differential_create/tests/BacktankIntegrationTests.java) 和 [BacktankTests](../src/gameTest/java/com/diliupengyun/differential_create/tests/BacktankTests.java)。2026-10-15 数值回归 42 次检查、铜／下界合金两种材质共 16 项 GameTest 通过。

| 分组 | 验证内容 |
| --- | --- |
| 位移与产量 | 分成 1–1024 片的同一行程得到相同产量；极低速无最低免费增量，高速无原版增量封顶，正反转按绝对行程计 |
| 实际耗功 | 从 ±128、0.01、8192 RPM 的受控初态调用一次真实公共时钟，以孤立转子的恒扭矩运动解析解核对产量和末速；自然停转不丢最后一段行程 |
| 充满事件 | 从已付四分之三点空气、±128 RPM 开始，在 tick 内充满；末速对应最后四分之一点的实际加工功，余时和随后 tick 不再消耗工作扭矩 |
| 工作状态 | 静止待充保留起动阻力；原生 tick 不免费充气；水淹暂停并卸载，保留惯量与小数进度，解除后续充 |
| 生命周期 | 受控 NBT 保存／读取、物品 NBT、克隆取物、真实掉落表、右键穿戴和重新放置；保留容量附魔、整数空气、自定义名称、其他模组数据及显式组件移除；满罐清除旧小数额度 |

专项命令为 `.\gradlew.bat runBacktankServer`，独立命名空间 `differentialcreate_backtank`，每次在 `build/backtank/runs/` 创建世界。运动测试仅设置初速，随后不补能；它们在测试回调中额外推进一次公共物理 tick，以隔离初态和比较解析解。生命周期测试是受控序列化与原生物品转换，未替代自然区块卸载、多人交互或实际客户端体验。

## 弹射置物台

入口：[EjectorChargeRegression](../src/physicsTest/java/com/diliupengyun/differential_create/EjectorChargeRegression.java)、[EjectorIntegrationTests](../src/gameTest/java/com/diliupengyun/differential_create/tests/EjectorIntegrationTests.java)、[EjectorTests](../src/gameTest/java/com/diliupengyun/differential_create/tests/EjectorTests.java) 和 [EjectorClientSmokeTests](../src/gameTest/java/com/diliupengyun/differential_create/tests/EjectorClientSmokeTests.java)。2026-10-15 数值回归 30 次检查、服务端专项 19 项 GameTest 通过；其中一项复用既有机器负载回归，不计为新增独立场景。

| 分组 | 验证内容 |
| --- | --- |
| 实际回卷与耗功 | 0、0.01、±128、8192 RPM；孤立转子从受控初速推进一次公共物理 tick，与恒扭矩解析解比较实际回卷行程和末速；停转不增加额度 |
| 充满边界 | 正反转均只支付最后 32 RPM-tick；充满后余时与后续 tick 保留惯量但不消耗工作扭矩 |
| 原生发射 | 1 件／64 件物品、生物、断电发射、释放动画卸载；耗尽一轮额度，重复触发不重复发射；未充能触发也不向上推动实体 |
| 目标和触发 | 改远只补差额，改近丢弃超额，改回不能恢复；高度正负沿用原版相同行程标定；待发射时改远会取消未付足的排队发射并保留物品；保留红石抑制与库存阻塞规则 |
| 在途物品 | 两批不同目标／朝向同时在途，同批共享碰撞缓存；NBT 恢复各自轨迹、年龄和批次，拆除时按原轨迹掉落一次；实际障碍拦截与目标置物台入库 |
| 客户端 | 集成服务器的实际方块数据同步；真实原生 BER 在改目标前后产生相同的在途物品顶点；玩家待弹起期间收到 LAUNCHING 更新仍按原生速度弹起；虚拟 Ponder 保留原生时钟 |

命令为 `.\gradlew.bat runEjectorServer runEjectorClientSmoke`，各自在 `build/ejector/` 创建临时世界。服务端运动及充满测试以解析解核对轴功；其余用例由夹具明确预付回卷行程，用于隔离物品与触发行为，不作为从动力源到载荷真实动能的全周期验收。物品弹道测试清除共享模板的低顶棚，碰撞用例另放实际障碍；客户端使用独立集成世界和冻结夹具，完成后自动退出。

额度保留原版距离标定和批量规则，没有物品／生物质量模型，不保证实体动能守恒；虚拟物品仍使用原生修正弹道和周期性碰撞扫描。受控 NBT 重载未替代自然区块卸载／重载，多人延迟、长时间工厂运行和动态结构／列车上的原生支持范围仍未完成专项验收。

## 粉碎轮双轮作业

入口：[CrushingPairRegression](../src/physicsTest/java/com/diliupengyun/differential_create/CrushingPairRegression.java)、[CrushingIntegrationTests](../src/gameTest/java/com/diliupengyun/differential_create/tests/CrushingIntegrationTests.java) 和 [CrushingTests](../src/gameTest/java/com/diliupengyun/differential_create/tests/CrushingTests.java)。2026-10-15 数值回归 416 次检查、服务端专项 27 项 GameTest 通过；其中两项复用既有机器负载与多机器耗功回归。

2026-10-24 补查断源后的低速残余动能。两个等惯量粉碎轮通过无阻尼、反向弹性连接耦合，粉碎负载只消耗相对转动；共同摆动可在动能与弹性储能之间交换，不能据动能暂时为零判断网络已静止。初速 20／−12 RPM、各自惯量 0.1 的独立夹具中，初始总能量 27.2，粉碎消耗 25.6，剩余 1.6 在两种储能间往复；相对行程及新增加工功均为零。对称初速 16／−16 RPM 的对照则完全停下。该回归使用三种子步长各运行 1200 tick，逐步核对能量账本；不添加隐藏阻尼或强制清零。此夹具验证现有共同进给模型，未模拟材料剪切滑移等额外损耗，也不等于测量了任意玩家网络的全部能量。

静止进给的显示使用与求解器相同的相对速度容差，避免浮点误差触发粒子或翻转出料方向；实际低速进给仍正常显示。新增服务端检查覆盖共同运动、正负微小误差、无虚假加工进度和真实慢转，服务端专项现为 28 项，全部通过。

| 分组 | 验证内容 |
| --- | --- |
| 共同进给 | 等速／异速反向、同向同速／异速、一轮停转、全停、极低速自然相对停转和高速；按解析解核对行程、双轮末速、动量与耗功 |
| 相对静止 | 有限静反力、不同惯量、共享轮和冗余接触环；弹性轴振荡与反转时相对绝对行程仍与加工耗功一致 |
| 完工与批量 | 正反转均在最后 32 RPM-tick 完工并卸载两侧；单件、2／4／64 件的有限对数预算，按数量执行原生配方、整批输出且不重放；复用既有多机器全周期耗功测试 |
| 进出料 | 六种轮轴／间隔方向，一轮停转时仍创建有效进给；反转改变进出料方向，拆轮移除控制器；实体牵引和物品吸入使用共同进给，零进给不造成原生伤害 |
| 独立传动 | 两个实际创造马达分别驱动两轮，保留各自网络；其中一路经过实际离合器，检查滑移和扭矩上限；同向异速时慢侧负载率识别返回功率 |
| 保存与显示 | 受控 NBT 冷载恢复双精度加工余量；同速自转和全停均保留进度。护目镜显示有符号物料反力及吸收／返回功率，控制器同步静反力 |

命令为 `.\gradlew.bat runCrusherServer`，每次在 `build/crusher/runs/` 创建独立世界。孤立运动与完工场景使用受控初速和明确的已付行程；马达场景从实际动力源驱动。批量测试每个 tick 重设初速，用动能差检查每段耗功。受控 NBT 测试未替代自然区块卸载，多人同步、实际客户端观感及长期大型工厂性能仍待专项验收；物料惯量、滑移剪切损失和实体真实动能未纳入模型。

## Create 6 物流与锁链传动轮

2026-10-16 基于 Create 6.0.10-281 的专项包含 **60 项默认服务端 GameTest**；同次完整物理回归通过，其中 [ChainConveyorRegression](../src/physicsTest/java/com/diliupengyun/differential_create/ChainConveyorRegression.java) 有 1584 次分割路径与正反向首达检查。客户端烟测通过了 10 个正反向乘链出口用例、实际方块数据中的包裹／方向往返、虚拟 Ponder 连续 tick 与原生渲染器存在性检查。

入口为 [Create6LogisticsTests](../src/gameTest/java/com/diliupengyun/differential_create/tests/Create6LogisticsTests.java)、[列车邮件测试](../src/gameTest/java/com/diliupengyun/differential_create/tests/Create6TrainPackageTests.java)、[工厂配料测试](../src/gameTest/java/com/diliupengyun/differential_create/tests/Create6FactoryTests.java)、[商店测试](../src/gameTest/java/com/diliupengyun/differential_create/tests/Create6CommerceTests.java) 和 [客户端烟测](../src/gameTest/java/com/diliupengyun/differential_create/tests/Create6ClientSmokeTests.java)。

| 分组 | 默认数量 | 覆盖与边界 |
| --- | ---: | --- |
| 锁链与基本物流 | 46 | ±0.01／16／256／1024／2048／8192 RPM、整圈、多圈、停转；正反向转交、先到端口优先、跨段余时使用接收轮实际转速、同 tick 不重复推进、`1e30 RPM` 闭环不挂起；蛙港满库存保持包裹、受控 NBT 内容与方向、负转停机后的几何重载；打包拆包、碎片等待／重组、库存查询／请求、仪表、物品仓口与计时器 |
| 列车邮件 | 8 | 正反方向 × 取件／送件 × 实体邮筒／未加载坐标的离线缓冲；真实时刻表寻路、物理到站与原生库存转移；保留不匹配地址和已到本地的信件，重复停靠转移不复制 |
| 工厂配料与合成 | 2 | 两个实际仓储按 4 金锭／1 红石精确配料，保留 3×3 配方排列与发货地址，重打包守恒；输出承诺抑制重复配料。另从单输入仪表配方生成包裹，经原生拆包接口交给真实马达和齿轮驱动的机械合成器，实际产出 4 个木板 |
| 桌布商店 | 4 | 购物清单增加／库存上限与物品组件保存；正常交易精确扣款和打包；付款不足、收款库存满、使用新鲜库存摘要时缺货均不扣款 |

锁链修复入口为 [ChainConveyorTransport](../src/main/java/com/diliupengyun/differential_create/kinetics/ChainConveyorTransport.java)。原生最短角差比较会遗漏一次 tick 已经过的大半圈、整圈或多圈；现按未取模行程定位首个事件，再推进跨段余时。方向随包裹和传动轮保存，停机重载保留所在链侧。空载额定负载、零链体惯量、无货物附加质量及原生每 tick 转速采样保留，没有接入共同机械积分或真实链条伸缩模型。

```powershell
.\gradlew.bat runLogisticsServer
.\gradlew.bat runLogisticsClientSmoke
```

标准入口分别在 `build/logistics/runs/` 和 `build/logistics/client-runs/` 创建新世界，并要求日志明确通过。CI 执行服务端专项并保留日志。本机 Gradle 启动曾遇到 `Unable to establish loopback connection`，本次验证改用 JDK 21 编译全部当前源码和已有 ModDev 依赖／启动参数直接运行同一测试；不能据此声称执行了 Gradle 的完整 `build` 任务。

### 商店事务的已确认原生问题

两项额外正确性探针以 `-PlogisticsKnownIssues=true` 显式启用，默认不混入通过套件：

```powershell
.\gradlew.bat runLogisticsServer -PlogisticsKnownIssues=true
```

- `table_cloth_busy_warehouse`：打包机已有 50 项排队出口包裹，原生 `PLAYER` 请求拒绝；交易仍扣取 4 绿宝石并消耗清单，仓储的 17 铁锭未动，也没有创建本次商品包裹。
- `table_cloth_stale_stock`：清单订购 14 铁锭后，实际库存从 17 变为 1，但 20 tick 的近期摘要仍显示旧量；交易足额扣取 4 绿宝石并消耗清单，只打出 1 铁锭。

对应上游 [StockTickerInteractionHandler](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/logistics/stockTicker/StockTickerInteractionHandler.java) 在实际扣款后才广播请求且不检查返回值，库存检查又取近期摘要。这些路径没有使用微分动力的输送推进实现，本轮只确认并保留复现，没有改变商店交易规则；两项探针当前预期失败，不能当作已修复回归。

多人高延迟、自然区块卸载／重载、任意大型自动化图、长期性能、ComputerCraft 可选集成、纸板装备交互和全部新增配方仍未完成专项验收。乘链烟测验证实际客户端的出口选择，未穷尽长距离玩家随动和网络延迟。邮筒测试直接建立原生站点关联，没有验收玩家放置站台和连线的完整交互；离线缓冲测试使用真正未加载坐标，没有声称覆盖自然卸载的完整生命周期。

## 历史验证记录

本节保留当时的测试数量与网络协议号，不作为当前构建状态；现行协议见 [README](../README.md#构建与验证)，本次验证范围和结果以实际执行日志为准。

2026-10-09 初版构建与数值回归通过：356 个齿轮箱场景（4,855,762 次约束检查）和 2526 个其他动力划分（101,398 次检查）；535 项真实运动测试、247 项常规 GameTest 和 427 项真实流体专项全部通过。另在存档副本恢复原崩溃记录的九节点状态和双精度进度，100 个普通服务端 tick 内完成停车，输出轴和飞轮未断裂。临时重放探针只用于验证，没有进入试用 JAR。

同日修复输出半轴与相邻轴的装配零点错位：新增 24 项真实相位场景，修复前已复现失败；修复后全量运动专项 559 项通过，重新构建与渲染数学回归通过。

同日补上运动中段的快照同步：输出相位纳入同一转子消息，不再从延后的方块数据独立推进插值。新增 24 项 90° 运动快照场景，全量运动专项 583 项通过，渲染回归 17,859 次检查通过。网络协议版本为 9；物理驱动、指令目标与装配接口未因该显示修正改变。

## 复现和边界

```powershell
.\gradlew.bat build
.\gradlew.bat runMotionCoverageServer
.\gradlew.bat runMotionCoverageServer '-PmotionSelect=bearing_.+'
.\gradlew.bat runMotionCoverageServer '-PmotionSelect=phase_.+'
.\gradlew.bat runGameTestServer -PregressionRunId=servo-check
.\gradlew.bat runVirtualAirServer
```

专项在 `build/motion-coverage/runs/` 创建独立世界；`motionSelect` 为完整名称的正则。Gradle 任务要求日志明确出现全部必需测试通过，服务器自行退出不能算通过。常规与流体入口有复用场景，不把其数量相加宣称独立行为总数。执行日志均在对应世界的 `logs/latest.log`。

有限划分不能穷尽任意程序、任意外部模组和长期大型工厂。本版目标反馈在齿轮箱输出轴上；直接传动和无滑移的受控结构由真实轴位移跟随。下游滑动离合器、结构几何阻挡或工具驻留造成的独立位移，不等同于输出轴的位移；这些复杂作业尚需额外机构反馈。实际客户端交互、任意网络延迟和大型工厂性能未由服务端 GameTest 覆盖。
