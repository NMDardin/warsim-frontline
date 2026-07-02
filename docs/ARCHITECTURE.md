# 系统架构

## T-008 Weapon扩展边界

`WarSimFrontlineWeapons` 是独立 Paper 插件，依赖 `WarSimFrontline` 与
CraftEngine，不允许主插件反向依赖。主插件通过 Bukkit ServicesManager 暴露
平台无关的 `WarSimBattleRuntime` 和动态命令注册表；扩展只能读取 matchId、
lifecycleRevision、MatchState、LocalSession/Participant/Roster 资格与
CombatRelation，不能获取可变集合或修改 Match/Roster。

`warsim-weapons` 是纯 Java 领域核心，负责弹药、装填、射速、散布、Ray/AABB、
距离衰减、友伤策略、事件和指标。`warsim-weapons-paper` 负责 CraftEngine
物品身份、Paper 输入、玩家候选采样、方块 ray trace、主线程伤害和 ActionBar。
CraftEngine 只提供 JSON 模型、资源包和自定义物品身份。

射击依次经过物品识别、Battle Runtime资格、同世界候选采样、方块遮挡、
纯Java最近交点、CombatRelation和Paper伤害前二次校验。Weapons不创建第二个
Bukkit中心任务；主插件既有中心tick通过Runtime事件驱动装填与显示。换局、
RESETTING、退出和关闭会清理旧局状态。

## Objective 与 Ticket

`warsim-api` 只暴露不可变据点、Presence、票数、事件和指标契约。
`warsim-match` 的 `DefaultObjectiveService` 与 `DefaultTicketService` 是纯 Java 核心；
`ObjectiveMatchCoordinator` 负责捕获奖励、票数耗尽和 Match 生命周期联动。

Paper 只在主线程读取在线玩家位置，并先验证 LocalSession、当前 MatchParticipant 和
当前 Roster assignment，再形成不可变 PresenceFrame。领域核心不接触 Player、
Location、World、BossBar 或调度器。数据库和 Redis 不参与高频占领进度。

每个战场仍只有一个中心 Bukkit 任务。Match 与 Roster 每5 tick运行，据点按自己的
1至20 tick配置节流。RESETTING 会先停止扫描、清理显示和局级服务，再允许新
matchId 建立全新据点与票池。后续防区、出生和重生系统只能通过现有服务接口扩展，
不得绕过 Match/Roster 资格判断。

## Match、Roster 与 LocalSession

`LocalSessionRegistry` 表示玩家已连接当前 Paper 节点；`MatchParticipant` 表示当前 Match
已接纳玩家；`DefaultRosterService` 表示该参与者在当前 matchId 的阵营与小队归属。

写入顺序为 LocalSession → RosterAdmissionPlan → MatchParticipant → Roster commit。
Roster 提交成功后才发布 ParticipantJoined 事件。退出顺序为 Roster 断线保留 →
MatchParticipant 关闭 → LocalSession 关闭。

Roster 核心位于 `warsim-squad`，是单一串行事务聚合。Paper 只能通过
`MatchRosterCoordinator` 和公开服务接口操作。断线保留占用阵营硬容量但不占用小队活动
容量；reset 清空旧 Roster，新 matchId 仅重新接纳仍有 LocalSession 的在线玩家。

`CombatAffiliationService` 是后续出生、枪械和伤害系统的敌我关系扩展点。

## 部署拓扑

玩家连接 Velocity 代理层，再进入 Lobby、`Official-War-01`、`Official-War-02` 或 Rental Pool。每个战场对应一个独立 Paper JVM。PostgreSQL 保存长期数据，Redis 在后续阶段承担跨服状态与无玩家载体的节点通信，资源包由 CDN 分发，战场控制服务负责节点生命周期。

T-002 仅配置 `lobby-01` 和 `official-war-01`。后端 Paper 必须仅在受信网络监听，不得直接暴露公网。Velocity 是转服权限的最终决策者；Paper 只能提交内部节点 ID，不能决定后端 IP 或动态注册地址。

## Plugin Messaging 边界

频道 `warsim:control` 只负责当前有在线玩家作为载体的转服请求与响应。Velocity 对频道消息先标记 handled，并仅接受已注册后端 `ServerConnection`。没有在线玩家时 Plugin Messaging 无法提供可靠服务间通信，因此不能替代 Redis。

## Redis控制平面

Paper节点定期将类型、实例ID、生命周期、在线人数和容量写入带TTL的Hash，并用Sorted Set维护最后心跳索引。Velocity仅在Redis健康时使用节点目录增强T-002目标验证；Redis降级时仍保留原有注册服务器验证。

无玩家载体的消息使用Redis Streams和每目标节点Consumer Group。消息固定版本、限长、过期校验和目标校验；成功后XACK，失败重新入流，超过次数进入目标死信流。XAUTOCLAIM接管消费者崩溃留下的Pending消息。成功处理后写入Redis去重键，处理中使用短TTL锁。

Redis只存短期控制面数据，PostgreSQL只存长期业务数据。Redis故障不得阻止Paper、Velocity、Plugin Messaging或PostgreSQL启动。

## Match生命周期

`DefaultMatchService`是平台无关的单局状态机，只有它可以修改Match状态。`PaperMatchCoordinator`负责一个每5 tick运行的中心调度任务、玩家事件DTO转换、展示和插件生命周期装配。

`LocalSessionRegistry`跟随Paper连接，Match参与者跟随matchId。重置会关闭参与者和局级事件订阅，但不会伪造玩家离开节点。异步重置回调必须验证matchId、revision和RESETTING状态。

Paper Redis心跳从Match本地快照映射节点可用状态；Redis和数据库故障与Match状态机隔离。当前`MatchResetService`只做WarSim安全清理，后续可以替换为正式受控地图重置。

## 模块依赖

`warsim-api` 不依赖平台。`warsim-common` 与领域模块建立在 API 上。`warsim-network` 保存平台无关协议。`warsim-match` 聚合 Paper 业务适配，`warsim-velocity` 聚合代理适配。依赖保持单向，不允许领域模块反向依赖平台入口。

## PostgreSQL 数据层

`warsim-api` 只暴露平台无关的数据库生命周期、健康、指标和玩家档案异步接口，不依赖 JDBC、HikariCP、Flyway 或 Paper。`warsim-database` 实现连接池、迁移、有界执行器和 JDBC Repository。`warsim-match` 仅负责从主线程提取 UUID、玩家名和时间，并提交不可变数据。

所有 JDBC、迁移和健康检查都运行在专用固定线程、有界队列的数据库执行器中。Paper 主线程不等待数据库 Future。HikariCP 管理连接恢复与池指标，Flyway 在首次进入健康状态前完成迁移，Repository 使用 PreparedStatement 和显式列。

Frontline 使用独立 PostgreSQL 数据库、用户、密码和默认 Schema `warsim_frontline`。禁止读取、连接或共享 Euro WarSim 的业务表与玩家资产。

## 载具职责

ModelEngine 负责模型、骨骼、动画、座位和模型挂载；MythicMobs 负责基础实体和技能；WarSim 仅负责后续的驾驶输入、弹道、装甲、模块伤害、配额和对局联动。禁止 VehicleCore、第二套 Display 载具渲染框架和 ArmorStand 堆叠模型。

## T-009 Performance sampler boundary

The main Paper plugin owns the only mutable `PerformanceService` and local
performance aggregator. Other plugins may contribute read-only metrics through
`PerformanceContributor`; the dependency direction remains:

```text
Weapons plugin -> WarSim main plugin service facade
```

The main plugin must not reference `warsim-weapons-paper`, CraftEngine adapter
implementations, or the Weapons plugin entrypoint.

SyntheticLoad execution is implemented as pure Java work that must not mutate
the live Match, Roster, Objective, Ticket, Weapons, Redis, or database state.
It does not create players, entities, NPCs, ArmorStands, projectile entities,
or per-sample Bukkit tasks. The office-laptop implementation did not run
synthetic workloads or produce performance conclusions.

## T-010 load-test map boundary

LoadMap and LoadScenario definitions provide stable scenario IDs and immutable
snapshots for future pressure reports. The default template world is
`warsim_load_test`; missing worlds produce `UNLOADED` or validation failure and
are never auto-created.

The Paper implementation reads `config/load-maps.yml` and
`config/load-scenarios.yml`, validates coordinates and scenario topology, and
can prepare only an in-memory scenario context. It must not create or copy
worlds, place or delete blocks, call WorldEdit, teleport players, create fake
players/NPCs/ArmorStands/robots, start matches, run synthetic load, or alter
the production Match.
## T-011 Class/Deployment Boundary

The main Paper plugin owns class and deployment state. `warsim-api` exposes only platform-neutral DTOs and service contracts; it must not depend on Bukkit, Paper, CraftEngine, or the independent Weapons plugin implementation. The Weapons plugin registers `CombatLoadoutProvisioningService` through Bukkit `ServicesManager`, so dependency direction remains `Weapons plugin -> WarSim main plugin services`.

Deployment is transactional and binds `matchId`, `lifecycleRevision`, `deploymentRevision`, and `lifeRevision`. A player becomes `ALIVE` only after the transaction commits. Waiting, dead, not-deployed, and deploying players are excluded from weapons and Objective Presence through the shared battle runtime eligibility view.
# T-012 combat feedback boundary

The main Paper plugin owns CombatOutcome, SpawnProtection, PlayerFeedback,
KillFeed and HUD. The independent Weapons plugin submits weapon damage
correlations and WEAPON feedback through shared `warsim-api` ServicesManager
services; the main plugin never depends on the Weapons plugin entrypoint or
CraftEngine adapter.

Combat statistics are current-match memory only. PostgreSQL and Redis are not
used for T-012 statistics or KillFeed. Respawn ticket charging remains part of
the T-011 deployment transaction, not death settlement.
