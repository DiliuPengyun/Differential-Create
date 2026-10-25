#set page(paper: "a4", margin: (x: 2.2cm, y: 2.0cm))
#set text(lang: "zh", font: ("Microsoft YaHei", "SimHei"))
#set par(justify: true, leading: 0.7em)
#set heading(numbering: "1.")
#show heading.where(level: 1): set text(size: 16pt)
#show heading.where(level: 2): set text(size: 13pt)
#set math.equation(numbering: none)

#align(center)[
  #text(size: 24pt, weight: "bold")[小惯量下扭转弹性的影响]
  #v(0.2em)
  #text(size: 13pt, fill: rgb("#555555"))[为什么“任意小惯量”不是免费的刚性化]
]

#v(0.8em)

#block(
  fill: rgb("#f4f6f8"),
  inset: 14pt,
  radius: 6pt,
  stroke: 0.5pt + rgb("#d0d7de"),
)[
  *结论先行*：给系统任意小的惯量，可以把零惯量的代数奇点变成有限频率的弹性模态，
  但不会自动得到一套刚性齿轮组。没有阻尼时，它只是把“相对速度被冻结”换成了“高频弹性颤振”；
  初始角度不一致时，速度振幅甚至会随 $1\/sqrt(J)$ 放大。
]

= 两转子模型

先看最简单的两个转子：惯量 $J_1, J_2$，比率 1，连接刚度 $k$，无阻尼。

$ J_1 (dif omega_1)/(dif t) = -k (theta_1 - theta_2) $ <eq-two-rotor-a>
$ J_2 (dif omega_2)/(dif t) = +k (theta_1 - theta_2) $ <eq-two-rotor-b>

令 $phi = theta_1 - theta_2$，两式相减：

$ (dif^2 phi)/(dif t^2) + k (1/J_1 + 1/J_2) phi = 0 $

弹性振荡频率为

$ Omega = sqrt(k (1/J_1 + 1/J_2)) $

等惯量 $J$ 时，$Omega = sqrt(2k\/J)$。

公共转速 $omega_c = (omega_1 + omega_2)\/2$ 不受弹簧影响，仍然是一个零频模。
弹簧只决定相对运动，不给公共模提供回复力。

= 三种初始状态

同样的 $J > 0$，弹性造成的影响完全取决于初始状态是否已经落在约束流形上。

#table(
  columns: (1.05fr, 1.5fr, 1.25fr, 1.4fr),
  inset: 7pt,
  align: (left, left, left, left),
  stroke: 0.4pt + rgb("#cccccc"),
  table.header(
    [*初始状态*],
    [*扭转与力矩*],
    [*相对速度*],
    [*$J -> 0$ 的极限*],
  ),
  [
    $phi_0 = 0$\
    $Delta omega != 0$
  ],
  [
    $phi_max = Delta omega \/ Omega$\
    $tau_max = Delta omega sqrt(k J \/ 2)$\
    都正比于 $sqrt(J)$
  ],
  [
    振幅仍是 $Delta omega$
  ],
  [
    角度抖动、力矩趋于零；\
    速度仍以极高频率振荡
  ],
  [
    $phi_0 != 0$\
    $Delta omega = 0$
  ],
  [
    $tau_max = k phi_0$\
    与 $J$ 无关
  ],
  [
    $Omega phi_0 = phi_0 sqrt(2k\/J)$\
    正比于 $1\/sqrt(J)$
  ],
  [
    速度振幅发散；\
    小惯量反而更危险
  ],
  [
    $phi_0 = 0$\
    $Delta omega = 0$
  ],
  [
    弹簧力矩为零
  ],
  [
    相对速度为零
  ],
  [
    弹性模态不被激发；\
    小惯量不改变运动
  ],
)

== 情况一：初始扭转为零，但相对速度不为零

这是“速度已经不一致、但角度还没有错开”的情况。解为

$ phi(t) = (Delta omega \/ Omega) sin(Omega t) $

- 扭转振幅 $Delta omega \/ Omega$，随 $sqrt(J)$ 变小；
- 弹性力矩振幅 $k phi_max = Delta omega sqrt(k J \/ 2)$，也随 $sqrt(J)$ 变小；
- 但相对速度振幅仍然是 $Delta omega$。

所以 $J -> 0$ 时，角度上看起来几乎锁死，速度层面却一直在高频振荡。
没有阻尼，它不会自己停下来。求解器还必须分辨周期

$ T = 2pi\/Omega approx 2pi sqrt(J\/k) $

“任意小”会把步长要求压到任意小。

== 情况二：初始扭转不为零，相对速度为零

- 力矩振幅 $k phi_0$，与 $J$ 无关；
- 相对速度振幅 $Omega phi_0 = phi_0 sqrt(2k\/J)$，随 $1\/sqrt(J)$ 发散。

惯性越小，同样的初始扭转激发出越大的速度振荡。
如果存档里存在非零扭转，小惯量不是稳定化，而是放大。

== 情况三：初始状态已经满足约束

$phi_0 = 0$ 且 $Delta omega = 0$ 时，弹簧不产生任何力矩，弹性模态不被激发。
系统只是以公共 gauge 速度整体旋转；给不给小惯量，运动都一样。
小惯量只影响整体转速有多容易被外力改变。

= 公共模仍然没有被锚定

有惯量后，守恒量是广义角动量

$ P = sum_i J_i q_i omega_i $

弹簧是内力，不改变 $P$。公共转速由初始 $P$ 决定：

$ omega_c = P \/ sum_i J_i q_i^2 $

它不会因为弹性被拉回某个固定值。小惯量只是让公共模有了很小的惯性：

- 没有外力时，它保持不变；
- 有外力时，很小的力矩就能让它快速加速，因为 $(dif omega)/(dif t) approx tau \/ J$。

因此，小惯量解决的是“零惯量代数奇异”，不是“自由 gauge 没有物理边界”。
公共模依然是零频模。

= 数值后果

- $J > 0$ 把微分代数系统变成 ODE，数学上正则化了；
- 但弹性模态频率 $Omega approx 1\/sqrt(J)$，系统变成刚性系统；
- 显式步进需要 $h < 2pi\/Omega approx sqrt(J\/k)$，$J$ 越小步长越小；
- 当前模型阻尼为零，激发起来的相对模态不会衰减，会一直高频振荡；
- 在 60M RPM 这种量级下，功平衡/步长判据仍然可能失败，只是失败方式从“代数发散”变成“刚性振荡/步长崩溃”。

如果有阻尼，相对模态会衰减，系统最终收敛到满足齿轮比率的刚性运动，
稳态弹性力矩为零，或由外负载决定。*小惯量 + 阻尼*才是恢复刚性齿轮组的物理路径；
只有小惯量、没有阻尼，得到的是永久高频颤振。

= 对齿轮塔的含义

当前塔保存的是相对速度不一致，例如：

$ 43.69 "RPM" quad "对" quad 66.16 "RPM" $
$ 51.18 "RPM" quad "对" quad 66.16 "RPM" $

这属于情况一：

- 给很小的惯量后，弹簧会让这些速度差以极高频率振荡；
- 角度抖动和力矩幅度都随 $sqrt(J)$ 变小，视觉上可能“看起来几乎刚性”，但速度层面一直在颤；
- 因为没有阻尼，它不会自己收敛到正确的齿轮比率；
- 求解器会被 $1\/sqrt(J)$ 的刚性拖住，$J$ 小到一定程度就退化成零惯量行为，甚至再次失败；
- 如果存档里还有非零扭转，则会触发情况二，速度振幅按 $1\/sqrt(J)$ 放大，更危险。

#block(
  fill: rgb("#fff8e6"),
  inset: 14pt,
  radius: 6pt,
  stroke: 0.5pt + rgb("#e6c76a"),
)[
  *一句话总结*：小惯量不是当前解析路径的简单替代品。
  它把“冻结相对速度”换成了“高频弹性颤振”；除非同时引入阻尼，
  或者用约束投影/模态分解，否则不会得到一套干净、刚性、比率正确的齿轮塔。
]
