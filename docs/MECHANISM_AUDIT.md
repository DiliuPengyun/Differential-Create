# 微分动力：原生机制适配盘点

核对日期：2026-10-15。初次盘点基线为项目提交 `f7cd297`、Create 6.0.10-281，以及当时的文档修正，依据项目实现、本地依赖源码和对应的 Create 6.0.10 上游源码。之后补查了负载率零速分支，完成负载表显示改版、背罐连续充气、弹射置物台回卷和粉碎轮双轮作业适配；这些后续工作与初次盘点区分，具体验证以执行日志为准。

“已确认”指源码中存在相应实现边界；由此推测的异常仍需在游戏中复现。下列优先级是后续开发建议，不代表所有原生抽象都必须替换，也不把新的物理模型列为现有缺陷。

## 建议先补齐的机制

| 顺序 | 机制 | 已确认的现状 | 建议与验收重点 |
| --- | --- | --- | --- |
| 1 | 负载表及自动化读数 | 指针、护目镜、比较器、显示链接已接入物理负载；非线性指针行程以 100% 为中点，独立休止区表示非数值状态。可选 ComputerCraft 外设仍保留名义应力 | 后续实现零速时间右极限求值及新的外设接口；现有整体零速起动预算未改变。继续覆盖飞轮惰行、离合打滑、泵反拖与实际异常输入 |
| 2 | 铜背罐／下界合金背罐充气 | 已按实际绝对轴行程累计，每 128 RPM-tick 充入 1 点空气；小数进度随方块与物品保存。充满事件即时卸载，水淹暂停并卸载，均保留惯量 | 数值回归及两种材质共 16 项真实服务端测试已通过；覆盖极低速自然停转、正反转、高速、满罐边界、水淹与存档／掉落／穿戴／放回。自然区块卸载和多人体验仍可继续验收，无需先引入真实气体压力模型 |
| 3 | 弹射置物台 | 已按实际轴行程回卷并保存已付额度，充满即时卸载，发射耗尽一轮额度；改近目标丢弃超额。每批在途物品独立保存目标轨迹，沿用原版弹射与批量标定 | 覆盖耗功、改目标、取消、重复触发、红石、库存阻塞、碰撞、物品 NBT 与客户端显示。真实实体质量／动能、自然区块重载、多人延迟和动态结构上的原生支持范围仍需进一步设计或验收 |
| 4 | 粉碎轮两轮异速 | 已累计双轮相对行程并施加配对反力，支持一侧停转和同向异速；恢复对数批量优势，单件基准有限，整批出料 | 已覆盖运动、耗功、六种安装方向、批量和受控重载；独立驱动与离合滑移见动力覆盖。真实物料惯量、剪切滑移、自然区块重载和大型工厂性能仍可进一步设计或验收 |

负载表接入见 [LoadGaugeBehaviour](../src/main/java/com/diliupengyun/differential_create/kinetics/LoadGaugeBehaviour.java)、[LoadGaugeScale](../src/main/java/com/diliupengyun/differential_create/kinetics/LoadGaugeScale.java) 与 [显示链接适配](../src/main/java/com/diliupengyun/differential_create/mixin/KineticStressDisplaySourceMixin.java)。[原生应力表](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/kinetics/gauge/StressGaugeBlockEntity.java)和[原生显示链接](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/redstone/displayLink/source/KineticStressDisplaySource.java)的读数已经覆盖；[ComputerCraft 外设](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/compat/computercraft/implementation/peripherals/StressGaugePeripheral.java)的旧方法及事件仍保留名义应力，后续应新增明确的物理接口。

原生背罐的整数增量、计时与水淹停充见 [BacktankBlockEntity.tick](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/equipment/armor/BacktankBlockEntity.java)。项目现由 [BacktankBlockEntityMixin](../src/main/java/com/diliupengyun/differential_create/mixin/BacktankBlockEntityMixin.java) 替换服务端充气计时，并接入 [MachineActivity](../src/main/java/com/diliupengyun/differential_create/kinetics/MachineActivity.java) 的工作状态；水淹停充条件沿用原版。实现与验证边界见[动力覆盖](KINETIC_COVERAGE.md#背罐充气)。

弹射置物台的原生回卷与发射见 [EjectorBlockEntity](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/logistics/depot/EjectorBlockEntity.java)。项目 [EjectorBlockEntityMixin](../src/main/java/com/diliupengyun/differential_create/mixin/EjectorBlockEntityMixin.java) 现已接入公共物理时钟，[EjectorCharge](../src/main/java/com/diliupengyun/differential_create/kinetics/EjectorCharge.java) 保存绝对已付行程，[EjectorFlight](../src/main/java/com/diliupengyun/differential_create/kinetics/EjectorFlight.java) 保存各批轨迹。充好后断电发射的玩法保留；本轮额度对应原生弹射作业耗功，不宣称弹出实体的真实动能守恒。验证见[动力覆盖](KINETIC_COVERAGE.md#弹射置物台)。

粉碎轮几何与共同进给见 [CrushingKinetics](../src/main/java/com/diliupengyun/differential_create/kinetics/CrushingKinetics.java)，批量标定见 [CrushingWork](../src/main/java/com/diliupengyun/differential_create/kinetics/CrushingWork.java)，配对反力和相对行程事件接入 [TorsionSolver](../src/main/java/com/diliupengyun/differential_create/kinetics/TorsionSolver.java)。加工回调仍由 [CrushingWheelControllerBlockEntityMixin](../src/main/java/com/diliupengyun/differential_create/mixin/CrushingWheelControllerBlockEntityMixin.java) 执行，规则与验证见[动力覆盖](KINETIC_COVERAGE.md#粉碎轮双轮作业)。

### 负载率的零速边界补查

直接编译生产 [LoadFactor](../src/main/java/com/diliupengyun/differential_create/kinetics/LoadFactor.java) 及其既有回归，核对正反传动、不同参考轴和极低转速。固定速比、沿起动方向一起趋零、扭矩连续且零速供给非零，传动损耗除以共同转速后也趋零时，运动功率比与零速力矩比一致；一般异速路径上的运动功率比则没有统一的零速极限。代码在整个极低速区域采用起动预算，这可以避免除零，但不能保证跨过区域边界时连续。

一个纯数值反例：两个成员的名义速比均为 1，源扭矩 64、机器阻力 8、传动损耗为零。源为 `2e-9 RPM`、机器为零时，生产函数返回 0%；源降至 `1e-9 RPM`、机器仍为零时，函数切到起动预算并返回 12.5%。弹性传动允许这种本地异速状态。该检查证明读数分支存在跳变，没有把它当作已经复现的完整游戏停机过程。额外核对还确认：反向接近零速会由制动状态切到起动状态，含预紧滑移损耗的运动路径也可与零速读数不同。

现有零速值可用于名义传动关系下的整体起动预算，不能保证每条异速支路都能起动，也不能无条件称为运动负载率的连续延拓。本次表盘改版暂沿用该预算；后续已选定只取时间右极限、允许 +∞、无右极限时显示未定义的规则，但求值实现尚未接入。具体公式和连续条件见 [DESIGN](../DESIGN.md#8-显示与负载率)。这只影响读数，不改变求解器受力和转速积分。

## 随后检查的原生动作路径

| 机制 | 现状与风险边界 | 建议 |
| --- | --- | --- |
| 软管滑轮升降 | 流体端已按实际下放端读取水头与外界介质，也有抽取／灌注测试；升降动作仍沿用原生速度采样，零速通知会把下放位置取整，负载未区分升降和停放 | 单独适配卷扬行程、停止位置与待机负载；测试慢速启停、反转、碰撞、中途保存及连续变速下的取液状态 |
| 流体阀门执行机构 | 管网已识别开关状态，并有真实马达开闭测试；指针由原生缓动按采样转速推进，阀门通断仍是离散规则 | 先检查变速与换向时的开度行程、停止保持和重载；是否增加节流与受压操作力矩属于另一个玩法决定 |
| 普通传送带 | 传输仍走原生 tick；带体惯量为零，货物不附加质量，无额定应力 | 继续验证高速跨段转交、漏斗和加工接触；本次锁链传动轮的修复不覆盖普通传送带 |
| 锁链传动轮／链式传送带 | 已修复高速半圈／整圈／多圈漏出口事件，按方向依次处理蛙港、分支和终点，跨段保留余时，防止接收端同 tick 再次推进；保留固定额定负载与零惯量 | 正反转、接收阻塞、跨段变速、极高速闭环及受控 NBT 已有回归；乘链客户端共用未取模行程判定。仍是原生每 tick 转速采样，严格积分行程、货物惯量、抬升耗功和自然区块重载可继续验收或设计 |

软管升降入口见 [HosePulleyBlockEntity](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/fluids/hosePulley/HosePulleyBlockEntity.java)，已实现的水头边界见 [FluidKinetics.Endpoint](../src/main/java/com/diliupengyun/differential_create/kinetics/FluidKinetics.java)。阀门入口见 [FluidValveBlockEntity](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/fluids/pipes/valve/FluidValveBlockEntity.java)；相关现有验证见 [FluidMachineIntegrationTests](../src/main/java/com/diliupengyun/differential_create/tests/FluidMachineIntegrationTests.java)。这些设备的流体接入已经存在，不能笼统列为未适配。

输送入口分别为 [BeltInventory](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/kinetics/belt/transport/BeltInventory.java) 和 [ChainConveyorBlockEntity](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity.java)。项目的零惯量标定见 [InertiaRegistry](../src/main/java/com/diliupengyun/differential_create/kinetics/InertiaRegistry.java)，原生额定负载见 [AllBlocks](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/AllBlocks.java)。

2026-10-16 已补充 Create 6 物流、列车包裹取送、工厂仪表配料／合成和桌布商店验收，并发现原生商店在打包队列满或库存缓存过期前变化时的付款问题。常规通过用例与这两项可选失败探针分别记录在[动力覆盖](KINETIC_COVERAGE.md)，不把尚未修复的商店问题计为通过。

## 需要先决定是否扩展的物理范围

- **结构重力与势能**：轴承、活塞、绳索滑轮、升降机和龙门架已经计入结构惯量、空气阻力和工具耗功，但没有升降重力项或偏心结构的重力矩。若加入，需要统一向上做功、向下反拖、断电保持、制动和配重的规则，并把势能纳入能量账目。列车坡度力和液体重力已经实现，不属于这个缺口。实现边界见 [ContraptionPhysics](../src/main/java/com/diliupengyun/differential_create/kinetics/ContraptionPhysics.java)。
- **货物、液体与乘客质量**：目前结构按方块统计质量，装卸不改变逐物品或逐流体质量。若让满罐、满载列车明显更重，需一起处理装卸中的动量、重心、惯量和存档；这会改变既有质量标定，适合独立设计。见[动态结构质量](CONTRAPTIONS.md#质量与惯量)和 [TrainPhysics](../src/main/java/com/diliupengyun/differential_create/trains/TrainPhysics.java)。
- **风扇吹动实体与转盘带人**：鼓风加工和风扇平方阻力已适配；实体受风和转盘带动仍保留原生动作规则。是否建立实体加速、传递功率及反作用的统一模型，应与上述实体质量规则一起决定，不必为补齐加工时钟同时重做。
- **轮轨黏着、车钩弹性、储热储汽、可压缩气体、水阻力及疲劳**：均为更大的模型扩展。当前文档已明确这些边界，优先级低于读数一致性、既有设备的付功规则和运行验收。

## 已实现机制的剩余验收

移动机械手已有独立作业取力或有限储能，移动式接口已有有限制动与会话，列车也已接入；不再将这些板块列为整项待实现。剩余重点是高速弯轨上的偏置工具与接口轨迹、复杂车队和多夹点、自然卸载重载、跨维度、多人加入及大型工厂长期性能。载具机械手的驻车外部补能仍是独立扩展，不能把已有的有限储能说成没有作业动力。

服务端 GameTest、受控 NBT 重载和客户端数学回归各自有覆盖边界；历史通过数量不能代替当前版本的实际运行。入口与未验证范围见[动力覆盖](KINETIC_COVERAGE.md)、[矿车覆盖](MINECART_TEST_MATRIX.md)、[列车说明](TRAINS.md)和[接口指南](PORTABLE_STORAGE.md)。

Ponder 教学也需要后续同步修改，尤其是速比控制、列车轮组安装与软管连接、R/N/1–5 档位、装卸停靠，以及动力不足时逐渐减速的行为；本次只修正文档，没有修改教学场景。
