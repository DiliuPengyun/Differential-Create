# 微分动力移动式存储接口原生行为研究

本文记录改造前的原生行为，适用环境为 Minecraft 1.21.1、NeoForge 21.1.238、Create 6.0.10-281，以及微分动力矿车适配提交 `8394417`。当时接口没有独立制动力，74 项研究测试包含原生驻留、速度恢复和旧流体 handler 缺陷的复现。当前首版已替换这些停靠规则，使用与扩展请看[接口使用指南](PORTABLE_STORAGE.md)；以下记录与研究测试不代表当前版本的验收结果。

## 对接与库存

同一种接口需要相对放置，中间留一格或两格空隙。移动端通过世界中的静态方块实体寻找另一端，因此两端都装在移动结构上时无法按此路径对接。物品接口与流体接口不能互接。方向允许一定倾斜：实际朝向向量与最近的方位向量距离必须不超过 0.5，单平面转角约为 29°。[Create 对接源码](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/contraptions/actors/psi/PortableStorageInterfaceMovement.java)

静态物品接口把本结构的合并物品库存暴露给外部自动化，静态流体接口暴露合并流体库存。接口本身没有用于中转的独立库存；实际输送能力来自漏斗、传送带、机械臂、泵或其他调用者。专项测试通过真实 NeoForge capability 插入和取出物品、注入和抽出水，确认车载库存准确增减。[物品能力实现](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/contraptions/actors/psi/PortableItemInterfaceBlockEntity.java)，[流体能力实现](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/contraptions/actors/psi/PortableFluidInterfaceBlockEntity.java)

微分动力按结构方块计算惯量，接口方块也计入；搬入物品或流体不会增加逐物品、逐流体质量。这沿用当前[矿车质量规则](MINECARTS.md#装配质量与阻力)和[动态结构质量规则](CONTRAPTIONS.md#质量与惯量)。

## 改造前的驻留和恢复运动

在 `8394417` 研究版本中，接口由原生 `PortableStorageInterfaceMovement` 设置 `context.stall`，再由结构实体汇总暂停状态，没有连续接触路径。下表只描述该历史版本；当前已使用独立停靠状态、有限反力和会话保护，列车也已接入，不再使用这些暂停与恢复规则。

| 载具或驱动 | 驻留时 | 释放时 |
| --- | --- | --- |
| 矿车 | 原生控制器保存进站速度，然后将当前运动向量置零；同车连续工具没有位移或免费进度 | 原生控制器恢复保存的速度；专项测试逐分量确认恢复值一致 |
| 动力轴承 | 结构角度停住，动力轴继续积分旋转；接口不增加工具作业力矩 | 按新的动力轴行程恢复转动；停靠期间的行程不会补播 |
| 动力活塞 | 在伸缩范围内部，结构位移停住，动力轴继续积分旋转 | 按新的动力轴行程恢复位移；停靠期间的行程不会补走 |
| 滑轮、龙门架、钟表轴承等 | 源码中的共用暂停判断仍作用于输出；这些控制器的接口组合尚需专项验证 | 取决于各控制器原生流程及共用输出控制 |
| 列车 | 使用 Create 自己的流程；接口只在速度不超过 5 m/s（0.25 m/tick）时处理对接，移动端不会按普通结构分支设置暂停 | 调度与车体动力学仍属于尚未全面适配的范围 |

矿车的停止和恢复由 [Create MinecartController](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/contraptions/minecart/capability/MinecartController.java) 的 `StallData` 完成。驻留期间速度保存在 NBT 可序列化的暂停数据中，释放时直接写回，因此停靠过程没有有限刹车距离或相应的制动耗散计算。恢复速度也没有经过一轮重新起步加速。

轴承和活塞的专项测试确认，结构暂停时其惯量仍留在轴系模型中，结构空气阻力变为零，接口自身的工具作业力矩为零。动力轴可以继续转动，暂停的结构输出为零，解除暂停后只消耗新的行程。共用代码见 [ContraptionPhysics](../src/main/java/com/diliupengyun/differential_create/kinetics/ContraptionPhysics.java) 与 [ContraptionMotion](../src/main/java/com/diliupengyun/differential_create/kinetics/ContraptionMotion.java)。这些行为构成原生驻留与连续动力学之间的适配边界，尚未描述一个包含对接反力的完整机械约束。

## 改造前的传输计时

计时使用游戏 tick，与动力轴转速无关。Create 的 `logistics.psiTimeout` 默认是 60 tick，20 TPS 时为 3 秒；实际服务器可以修改配置。首次开始连接时计时器固定设为 14；发生成功传输后才设为 `psiTimeout + 4`。在默认配置下，从首次连接到计时器降为 4 只有 10 次静态接口 tick，真正开始停车时可能已经消耗其中一部分。[计时实现](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/contraptions/actors/psi/PortableStorageInterfaceBlockEntity.java)，[超时默认配置](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/infrastructure/config/CLogistics.java)

成功插入或取出非空物品、成功执行非零流体传输都会重新计时。查询、模拟、空抽取和未接受的传输不会续期。专项测试验证模拟不改变库存或计时，并验证每 20 tick 成功取出少量内容时，矿车可以持续驻留 100 tick，超过默认的 60 tick 等待时间。接口无需等待库存全部搬空；最后一次成功交换后的超时决定何时放行。

计时器降至 4 时，移动端解除暂停；原生能力的连接区间仍包含 4。因此放行与能力完全断开可以发生在不同 tick。移动端每 tick 给静态端续上两 tick 的存活租约，停止续约后静态端关闭连接。零速度下也不能把“暂停已解除”直接等同于“接口 capability 已消失”。

静态端收到红石信号时关闭连接并拒绝新的对接；关闭移动端 actor 或拆掉静态端也会解除驻留。比较器的原生 0/15 输出按连接计时区间判断，表示对接状态，不表示车载库存满度；它不单独检查 `connectedEntity`，所以也不能直接代替存活与传输许可检查。[接口方块实现](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/contraptions/actors/psi/PortableStorageInterfaceBlock.java)

## 当时的流体管网交互和旧能力缺陷

当时微分动力已把静态流体接口作为原生 `IFluidHandler` 边界访问，实际交付使用整数 mB，小数保存在外部管网缓冲中；接口使用泛用机器的近似水头。目前已统一为同参考压力的库存边界，不再使用机器近似水头；完整泵、接口、移动储罐的双向传输和重接后来已有专项，见[当前接口验证记录](PORTABLE_STORAGE.md#验证入口)，不能继续视为完全未测试。

由交付代码与原生计时条件可推得：接口作为受液端时，尚未累计到 1 mB 的小数缓冲不会执行 `fill`，也不会续期；极低流量可能在首次有效注入前就解除驻留。供液方向会先以整数抽取并保留内部余量，不能直接套用同一个时间估算。此处的实际停车效果需要低流量接管测试确认。

**已复现的上游边界：外部长期保存旧流体 handler 时，红石断开后仍可能注入。** Create 的流体 `fill` 只判断计时区间 `isConnected()`，而两个 `drain` 和物品插取都经过 `canTransfer()`，后者同时检查连接实体。专项测试在断开后重新取得 capability，注入被拒绝；但对断开前保存的旧对象调用 `fill`，仍接受 1 mB，旧车载库存从 4000 增至 4001 mB。同一个旧对象的抽取被拒绝，旧物品 handler 的插入也被拒绝。[对应流体实现](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/contraptions/actors/psi/PortableFluidInterfaceBlockEntity.java)

这个历史缺陷要求调用者保留旧对象，且计时器仍处于连接范围。当前微分动力已通过 [DockingSession](../src/main/java/com/diliupengyun/differential_create/kinetics/portable/DockingSession.java) 和 [DockingHandlers](../src/main/java/com/diliupengyun/differential_create/kinetics/portable/DockingHandlers.java) 为物品及流体访问绑定会话，断开和换车后旧对象失效；对应拒绝判据位于 [PortableDockingTests](../src/gameTest/java/com/diliupengyun/differential_create/tests/PortableDockingTests.java)。研究工具刻意保留上游缺陷复现，不应改作当前版本的通过标准。

## 专项验证和维护

| 分组 | 场景数 | 判据 |
| --- | ---: | --- |
| 矿车物品与流体接口 | 64 | 四个水平方向，空闲、实际搬运、持续交换、红石、关闭 actor、拆端、类型与方向不匹配 |
| 两格和三格空隙 | 4 | 两格空隙可以发现对端，三格空隙不能发现 |
| 断开前保存的 handler | 2 | 复现物品与流体旧对象在红石断开后的不同检查行为 |
| 动力轴承和活塞 | 4 | 两种接口的实际驻留、轴转动、结构惯量、空气阻力、工具力矩、放行及不补播行程 |
| **合计** | **74** | 真实结构实体、原生回调及 NeoForge capability，全部通过 |

复验工具位于 [tools/portable-storage](../tools/portable-storage/PortableStorageInterfaceTests.java)，按需通过 [Gradle 初始化脚本](../tools/portable-storage/audit.init.gradle) 加载。正常构建不注册这组研究测试。以下命令只适用于上述 `8394417` 研究版本的检出；当前版本应运行接口使用指南中的 `dock_.+` 测试。研究判据刻意包含旧缺陷，通过表示历史行为得到复现。

```powershell
.\gradlew.bat runMotionCoverageServer '-PmotionSelect=psi_.+' --init-script tools/portable-storage/audit.init.gradle
```

测试世界和日志使用忽略的 `build/` 目录。测试中的接触回调用于确定对接起点，之后由原生实体 tick 管理驻留；这些有限场景不覆盖任意路线上的自动捕获。接口没有接入连续工具的体素和圆弧扫掠，高角速度长臂、自然卸载重载、完整循环管网及多人客户端伸缩动画仍需单独检查。

上述研究推动了后来的[接口行为设计](PORTABLE_STORAGE_DESIGN.md)。公共停靠状态、有限制动、真实停车后的重新起步、轨迹捕获与旧能力失效均已实现；当前范围、验证入口及尚未验收的组合见[接口使用指南](PORTABLE_STORAGE.md)。
