# 微分动力移动式接口使用与扩展

移动式存储接口和移动式流体接口共用有限制动、停稳接合和放行规则。两端保持原来的相对朝向，中间留一格或两格空隙；固定端开放移动结构的合并库存。接口只提供通路，物品输送和流体泵送仍由外部设备完成。

联机时客户端和服务端一起换用本版；当前网络兼容版本为 10。

## 先搭一座低速旋转卸料站

1. 在机械轴承上搭半径约两格的旋转臂，粘合储物箱和朝向站点的移动接口。固定端按旋转臂经过时的姿态相对放置。
2. 结构支路按“动力源 → 减速传动 → 离合器 → 轴承”连接。首轮从轴承实际约 8 RPM 或更低开始；当前创造马达设定 4 RPM 是小型样机的起始档位，实际转速以护目镜为准。
3. 手持扳手看固定接口，在侧面设置框选择“比较器输出：机械占用”，放行保持默认“自动空闲放行”。
4. 用比较器读取固定接口，经过中继器接到离合器。反馈到达离合器时应为 15 级，使其完全分离。布线不要给固定接口送入红石信号。
5. 用漏斗、传送带或机械臂从固定端取货；流体版在固定端接管道和泵。卸料设备从离合器之前的母线独立取力。配置好后空手右键机械轴承组装机器，观察装卸循环。

接头先伸出准备，此时比较器为 0，机器继续进站。接头实际搭接后输出 15，离合器分离，接头消耗剩余动能。停稳后开始装卸；空闲超时后先关闭库存，再收回接头，完全释放才降到 0，让机器开始下一圈。

手持扳手瞄准固定端时，绿色框表示移动接口本体中心可以接触的区域，黄色框表示伸出准备区域。双方仍须朝向相对；准备区不施加制动力。护目镜显示阶段、驱动转速、接头反力上限和最近一次失败原因。放大结构、提高转速或增加红石传播延迟后，应降低进站速度或改用定位控制。

## 设置与信号

两个设置均在固定端侧面的扳手设置框中，支持存档和剪贴板复制。

| 比较器输出 | 空闲／准备 | 制动／接合 | 可传输 | 正常收回 |
| --- | ---: | ---: | ---: | ---: |
| 连接许可（默认） | 0 | 0 | 15 | 0 |
| 机械占用 | 0 | 15 | 15 | 15 |
| 停靠阶段 | 0／8 | 8 | 15 | 8 |

机械占用从实际搭接持续到完全释放，用于离合器反馈。阶段输出用于顺序控制；其中准备阶段的 8 不适合直接触发结构支路的离合器。

- **自动空闲放行（默认）**：从开放库存起等待 Create 的 `logistics.psiTimeout`，默认 60 游戏 tick。实际成功搬运非空物品或非零流体才刷新计时；查询、模拟和失败调用不刷新。超时表示没有搬运，不能据此确认库存已经卸空。
- **红石放行**：持续保持，直到外部控制器给固定端红石高电平。适合慢速灌装、延迟启动泵或有明确完成条件的物流。两种设置中，高电平均立即关闭访问，开始收回，并禁止新捕获。

拆端、关闭移动端 actor、实体消失、对准失效或保持滑移都会立即关闭会话并撤掉失效的机械接触。正常释放保留真实停车后的运动状态；出站由当前实际动力重新加速。

## 精确旋转、往返平台与矿车站

程序变速箱可以先完成定位，再开放接口。旋转样机使用 `转动 360° → 等待红石 → 结束`，变速箱与轴承保持 1:1 传动；移动期间禁用接口，等待步骤解除禁用。回路须记住本轮确实连接过，再由“完全释放”产生继续脉冲；结束后另发一次独立启动脉冲，不能用持续高电平重复启动。

活塞平台先组装并停在有效行程内部，在相隔四格的两个位置布置接口。程序使用 `前进 4 格 → 等待 → 后退 4 格 → 等待 → 结束`，每个等待步骤只启用对应站点。避免以完全缩回、会拆解结构的位置作为装卸站。

矿车站使用低速控制铁轨区、独立通电的过渡动力轨、0 级定位控制铁轨和出站轨。不同档位的控制铁轨区用其他轨道分隔，避免原生相邻铁轨信号插值。装卸完成且接头完全释放后，将定位轨锁存为 15 级；矿车离开出口检测轨后，再复位为 0。控制铁轨的推力有限，重车需要更长的减速区。

详细接线与行为设计见 [PORTABLE_STORAGE_DESIGN](PORTABLE_STORAGE_DESIGN.md)。程序与矿车测试中的流程反馈由测试控制器发送真实红石信号；低速农场测试使用实际比较器、导线、中继器和离合器回路。

## 流体与能力生命周期

流体接口同样先制动、停稳、开放库存，关闭时先失效能力。已经交付或抽出的流体、小数余量和管内缓冲仍归外部管网保存，断开不会删除或复制液体。多个流体接口访问同一个移动库存时共享真实库存预算。

每次接合创建新会话。旧物品与流体 handler 的查询、模拟和执行全部失效，重新接合也不恢复旧对象。存档恢复重新核对结构身份与几何，创建新会话，并保留自动放行的剩余等待时间。

## 附属模组扩展

公共入口位于 [kinetics/portable](../src/main/java/com/diliupengyun/differential_create/kinetics/portable)。机械状态不依赖物品或流体类型；库存包装独立放在 `DockingHandlers`。

移动行为继承 `PortableStorageInterfaceMovement`、固定端继承 `PortableStorageInterfaceBlockEntity` 的接口默认进入公共机制，按同一种方块和 `FACING` 配对。专用朝向或跨方块配对可以在模组初始化时注册适配器，客户端和服务器均注册：

```java
PortableDocking.register(MY_INTERFACE.get(), new PortableDocking.Adapter() {
    @Override public Direction facing(BlockState state) {
        return state.getValue(MY_FACING);
    }
    @Override public boolean compatible(BlockState moving, BlockState stationary) {
        return stationary.is(MY_COMPATIBLE_INTERFACE.get());
    }
});
```

额外资源类型仍由附属模组提供库存能力。重写 `startTransferringTo` 时，先检查公共状态的 `accepts(contraption)`，拒绝与现有预约冲突的请求；调用父类完成连接后，再取 `PortableEndpoint.differentialcreate$docking().session()` 保存本次会话。能力的每个读写和模拟入口都检查 `session.valid()`，实际成功传输后调用 `session.transferred()`。`stopTransferring` 保留父类关闭流程；关闭、换车和重新接合时重建包装对象并发送能力失效通知。不要缓存未保护的移动库存后继续开放访问。

需要共享流体库存预算的包装可实现 `DockingSession.Bound`，返回自己的会话及真实库存身份。身份只用于预算去重，不授予访问权限。`DockingHandlers.Items` 和 `DockingHandlers.Fluids` 是现成参考。

首版覆盖普通矿车、机械轴承和直线结构的公共驱动路径。列车沿用原生调度；第三方额外资源的库存协议仍需相应适配与实测。稳定子结构、双底车、多接头、特殊控制器和完整客户端多人交互仍需扩大验收范围。

## 验证入口

```powershell
.\gradlew.bat build
.\gradlew.bat runMotionCoverageServer '-PmotionSelect=dock_.+'
```

接口专项测试使用隔离的 `build/` 世界，包含物品和流体、旧能力失效、模拟不续期、存档恢复、循环机器及实际泵管网。早期原生行为研究的 74 项测试保留在 [tools/portable-storage](../tools/portable-storage)，用于复现改造前的行为，不能作为本版验收。

2026-10-10 首版验证记录：

| 检查 | 结果 |
| --- | --- |
| `build` 与全部数值回归 | 通过 |
| 最终完整运动套件 | 1332 项全部通过，其中 34 项为接口专项 |
| 常规机器与流体套件 | 247 项全部通过 |
| 低速离合器农场、程序旋转、活塞往返、矿车站 | 物品／流体版本各完成 100 轮；活塞每轮两个站点 |
| 泵 → 接口 → 移动储罐及反向抽取 | 双向自然管网通过，总库存误差小于 1e-6 mB；关闭后移动库存不再变化 |
| 存档与卸载回调恢复 | 新会话重新确认身份与几何，剩余等待不重置，旧 handler 失效 |
| 完整空气／流体套件 | 首轮 427 项中 426 项通过；`structure_shared_world_room_water` 未在 400 tick 观察窗内达到接收量要求。随后水／岩浆两项独立复验均通过，守恒误差为零；首轮时限失败原因仍需确认 |
| 客户端设置、预览与伸缩显示 | 编译通过；完整实机交互与多人观察待验收 |

新接口回归代码为 [PortableDockingTests](../src/main/java/com/diliupengyun/differential_create/tests/PortableDockingTests.java)、[PortableMachineTests](../src/main/java/com/diliupengyun/differential_create/tests/PortableMachineTests.java)、[PortableTransportTests](../src/main/java/com/diliupengyun/differential_create/tests/PortableTransportTests.java)、[PortableDepotTests](../src/main/java/com/diliupengyun/differential_create/tests/PortableDepotTests.java)、[PortableReloadTests](../src/main/java/com/diliupengyun/differential_create/tests/PortableReloadTests.java) 和 [PortableFluidNetworkTests](../src/main/java/com/diliupengyun/differential_create/tests/PortableFluidNetworkTests.java)。
