# Weapons and Hitscan

## 模块与物品身份

T-008 由纯 Java `warsim-weapons` 和独立 Paper 插件
`warsim-weapons-paper` 组成。公共 DTO 不包含 Bukkit 类型。Weapons 通过
`WarSimBattleRuntime` 只读查询 Match、Participant、Roster 和 CombatRelation，
通过动态注册表挂载 `/warsim weapon`。

固定适配 CraftEngine 26.6.3。`craftengine-item-id` 映射 WeaponId；识别和
创建只调用 CraftEngine API。显示名、Material、Lore、模型与 CustomModelData
均不是权威身份或弹药来源。测试资源位于
`dev-environment/craftengine/resources/warsim`。

## 输入、弹药和射速

主手右键一次最多生成一枪。合法武器右键方块始终拒绝原版方块与物品交互；
副手不射击。Q键启动装填并取消掉落；切换离开对应WeaponId取消装填且不消耗
备弹。射速使用单调纳秒时间和向上取整的`60_000_000_000 / RPM`，服务器卡顿
不会补发多枪。

## 散布、采样与Ray/AABB

散布在视线圆锥内使用可注入随机源计算并重新归一化。Paper每枪只采样同世界、
当前matchId、ACTIVE Participant、connected assignment且非旁观者的在线玩家。
位置与BoundingBox每候选读取一次，按距离平方和UUID稳定排序，最多100名。

核心使用slab intersection，支持近零方向轴、边界、盒内起点和`t >= 0`。
同一目标比较HEAD/BODY，全体目标选择最近交点，同距离epsilon内按UUID排序。
不创建任何Minecraft projectile实体。

## 方块遮挡、伤害与敌我

Paper使用`World.rayTraceBlocks`获取最近阻挡距离。实体位于方块之后时返回
BLOCKED；ray trace异常时拒绝本枪且不消耗弹药。伤害点按距离分段线性插值，
HEAD在插值后乘倍率。

ENEMY允许伤害；SELF默认阻止；SQUADMATE和TEAMMATE在friendly-fire关闭时
阻止；UNKNOWN安全阻止。友军命中仍消耗弹药。Paper在主线程伤害前再次验证
matchId、revision和双方资格，再使用原版damage API，因此保留原版护甲与正常
伤害事件。测试武器原版近战伤害会被取消。

## 击杀、生命周期与性能

击杀归属最多1024条、TTL五秒、消费一次，不写数据库或Redis。WAITING/WARMUP
不能射击；PLAYING允许；ENDING立即停止；RESETTING、换局、退出和关闭清理
弹药、冷却、装填、归属与ActionBar。

每枪最多100候选，不扫描普通实体、不使用并行流、不执行I/O、不创建每枪或
每玩家任务。指标记录请求、拒绝、装填、ray test、遮挡、命中、伤害、击杀与
处理耗时。T-009可在这些指标上增加分位数与压力采样。

## 管理命令

命令仅位于`/warsim weapon`：status、list、give、ammo、refill、clear和
inspect。give通过CraftEngine API创建物品；背包满时不向地面丢弃。clear只
删除映射到本插件的测试武器和对应状态。
