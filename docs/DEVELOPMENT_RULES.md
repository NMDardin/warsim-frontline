# 开发规则

## Weapon与命中规则

- 禁止为普通子弹创建 Arrow、Snowball、Trident、Display 或 ArmorStand。
- 禁止客户端决定射速、弹药、命中或伤害。
- 禁止在异步线程读取玩家位置、BoundingBox 或调用 Bukkit 伤害 API。
- 禁止根据 Material、显示名、Lore 或 CustomModelData 单独识别 WarSim 武器。
- CraftEngine item ID 是测试武器身份；Lore 不是权威弹药存储。
- 每次伤害必须校验当前 matchId、lifecycleRevision 和双方玩家资格。
- 禁止旧 matchId 的射击、装填或伤害归属修改新局状态。
- 候选数量和伤害归属必须有界，禁止无界 Shot 历史。
- 禁止每发子弹或每名玩家创建独立 Bukkit 任务。
- 主插件不得依赖或强制加载 Weapons 插件。

## Objective 与 Ticket 规则

- 禁止领域核心持有 Bukkit `Player`、`Location`、`World` 或 BossBar。
- 禁止在非 PLAYING 状态推进据点。
- 禁止将断线保留、旧 matchId Roster 或无 ACTIVE Participant 的玩家计入 Presence。
- 禁止同一所有权变化重复发布捕获事件或重复发放票数。
- 票数不得小于0或超过配置 maximum；动态操作必须携带去重 operationId。
- 禁止为每名玩家或每个据点创建独立调度任务。
- Objective reset、recover 和新 matchId 必须清理旧进度、去重记录、监听器及显示。
- 高频据点进度不得写入 PostgreSQL 或 Redis。

## Roster 规则

- 禁止玩家自行切换 ATTACKERS/DEFENDERS。
- 禁止跨 matchId 恢复阵营、小队或队长。
- 禁止绕过每方 50 人与每小队 5 人硬容量。
- 禁止双阵营、双小队或同小队双队长状态。
- Roster 层禁止持有 Bukkit `Player`、执行 I/O 或异步修改内部集合。
- Participant 与 Roster 接纳必须通过统一原子协调入口。
- 管理员阵营或小队调整必须先验证完整目标状态，再原子提交。
- reset、recover 和 close 必须清空旧 Roster。
- 所有 Roster 写操作必须在单一串行上下文执行。
- 后续伤害代码必须使用 `CombatAffiliationService`，不得读取内部 Map。

## 分支

- `main`：可发布代码
- `develop`：集成分支
- `feature/*`：功能开发
- `release/*`：发布准备
- `hotfix/*`：生产紧急修复

禁止在正式服直接测试。功能完成必须包含代码、配置、权限、错误处理、测试和回滚方式。

## 强制约束

- 禁止主线程阻塞数据库、文件或 HTTP I/O。
- 禁止普通子弹实体泛滥。
- 禁止第二套载具模型框架与 ArmorStand 堆叠载具。
- 禁止租赁者上传插件。
- 禁止信任客户端 Plugin Message；Velocity 必须按真实 `ServerConnection` 验证来源。
- 禁止 Java 原生序列化和 Bukkit 对象序列化。
- 禁止在跨服消息中传递服务器 IP。
- 禁止根据用户输入动态注册后端服务器。
- 所有消息必须带协议版本，并执行字符串与总消息大小限制。
- Paper 世界、实体与玩家 API 只能在主线程访问。
- 不使用全局可变静态状态保存玩家或请求。
- 禁止在 Paper 主线程执行 JDBC 或等待数据库 Future。
- Repository 必须返回异步结果，并通过专用有界数据库执行器运行。
- 禁止 `SELECT *`，所有查询必须列出所需字段。
- 禁止字符串拼接 SQL 动态值；UUID、玩家名和时间必须使用 PreparedStatement。
- 经过严格校验的内部 Schema 标识符是唯一允许拼接的 SQL 结构。
- 禁止修改已经发布或执行的 Flyway 迁移；结构变化必须新增 V2、V3 等迁移。
- 禁止与 Euro WarSim 共用业务表、数据库用户或玩家资产。
- 禁止记录数据库密码、代理密钥或环境变量内容。
- 禁止无界数据库线程池和 `ForkJoinPool.commonPool()` 数据库任务。
- 禁止使用Redis Pub/Sub承担可靠控制命令；可靠消息必须使用Streams。
- 禁止生产逻辑使用Redis `KEYS`或无限SCAN。
- 禁止阻塞Redis命令、Future `get()`或`join()`进入Paper/Velocity事件线程。
- 禁止将Redis作为长期玩家资产数据库。
- 禁止将Bukkit或Velocity Player对象传入Redis线程。
- 禁止设计远程控制台、脚本、文件传输或任意命令消息。
- 所有Stream消息必须版本化、限长、带过期时间、目标校验和Redis去重。
- Match状态只能由核心协调器修改，禁止监听器、命令或展示服务直接set状态。
- 禁止为每位玩家创建独立倒计时任务；每个战场只允许一个中心生命周期任务。
- 所有延迟任务和异步重置回调必须绑定matchId与lifecycleRevision。
- 禁止旧局任务修改新局；不匹配任务必须拒绝并记录指标。
- Match状态转换不得执行文件、网络、数据库或其他阻塞I/O。
- 重置服务只能清理WarSim局级资源，不得操作其他插件实体或任意世界资源。
- 所有Match调度、BossBar和事件订阅必须支持取消和关闭。

## T-009 performance rules

- The main Paper plugin is the only owner of mutable performance aggregation.
- External plugins may only contribute read-only metrics through the registered
  `PerformanceService` facade.
- SyntheticLoad implementations must not mutate production Match, Roster,
  Objective, Ticket, Weapons, Redis, database, or Velocity state.
- Do not output pass/fail, capacity, TPS, MSPT, or production-readiness
  conclusions from local sampler reports.
- Reports must filter secrets, IPs, complete environment variables, player UUID
  lists, passwords, keys, and tokens.
- Do not create unbounded metric maps, unbounded sample lists, unbounded report
  histories, or one task per sample.

## T-010 load-map rules

- LoadMap `prepare` may only create an in-memory scenario context.
- Do not create worlds, copy worlds, place or delete blocks, call WorldEdit,
  teleport players, spawn fake players, spawn NPCs, spawn ArmorStands, create
  robots, start matches, or run synthetic load from LoadMap commands.
- Default load-map coordinates are templates and must be confirmed or adjusted
  before use on a real server map.
- Missing `warsim_load_test` must produce `UNLOADED` or validation failure; it
  must not auto-create a world.
- LoadMap failures must not shut down Match, Roster, Objective, Ticket,
  Weapons, PostgreSQL, Redis, Velocity, or the main plugin.
## Classes and Deployment Rules

- Do not create automatic deployment or operations tooling in gameplay tasks.
- Do not register top-level `/class`, `/deploy`, or `/respawn`; use `/warsim`.
- `CombatClassId` must remain an extensible value object, not a closed enum.
- `ADMIN_FORCE` is a deployment trigger only, never a billing reason.
- Do not mark a player `ALIVE` before deployment reaches `COMMITTED`.
- Old `lifeRevision` events must not mutate the next life.
- Do not pass Bukkit `Player`, `ItemStack`, CraftEngine objects, or Weapons plugin classes through `warsim-api`.
- Do not charge respawn tickets on death; T-011 charges only in the deployment transaction.
- If respawn cost is greater than zero and TicketService is unavailable, reject deployment instead of silently deploying for free.
- Safe-spawn search must not load chunks or scan unbounded candidates.
# T-012 combat outcome rules

- Do not record configured weapon damage as contribution before Paper damage is
  known to have produced effective survivability loss.
- Do not settle a player death from multiple listeners. CombatOutcome is the
  authority; downgrade death handling may only call the T-011 death transition.
- Do not deduct tickets from death settlement. T-011 deployment is the only
  respawn-ticket charge point.
- Do not bypass lifeRevision or matchId checks for damage, feedback, HUD or
  KillFeed.
- Do not let modules send competing ActionBar messages directly when
  PlayerFeedbackService is available.
- Do not overwrite foreign scoreboards with the WarSim HUD by default.
- Do not persist T-012 match statistics, contributions or KillFeed entries.
