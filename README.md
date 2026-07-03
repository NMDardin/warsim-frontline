# WarSim: Frontline

## T-013 Round Reset

T-013 adds the official battle round reset transaction. When a Match enters
`RESETTING`, lifecycle listeners run first, then the Paper reset service
evacuates local online battle players to a configured holding spawn and removes
only whitelisted transient entities from configured battle worlds.

This task does not restore blocks and does not copy, delete, unload, or rebuild
Minecraft world folders. Configure `match.reset.holding-spawn` and
`match.reset.transient-worlds` before enabling a production map. See
[`docs/ROUND_RESET.md`](docs/ROUND_RESET.md).

## T-011 Classes and Deployment

T-011 adds combat class selection plus deployment/respawn foundations. The bundled generic config keeps `deployment.enabled=false` and does not guess production map coordinates. Configure waiting spawn and fixed team spawns before enabling deployment on a real battle node.

See [`docs/CLASSES_AND_DEPLOYMENT.md`](docs/CLASSES_AND_DEPLOYMENT.md) for class IDs, life revisions, loadout provider, ticket charging, rollback, and command rules.

## T-008 独立无实体测试枪械

T-008 将测试枪械实现为独立 Paper 插件 `WarSimFrontlineWeapons`。主
`WarSimFrontline` 插件只提供只读战场运行时与 `/warsim` 子命令扩展服务；
Weapons 插件依赖主插件和 CraftEngine 26.6.3，主插件不反向依赖 Weapons。

构建产物：

- `warsim-match/build/libs/warsim-frontline-paper-0.2.0-SNAPSHOT.jar`
- `warsim-velocity/build/libs/warsim-frontline-velocity-0.2.0-SNAPSHOT.jar`
- `warsim-weapons-paper/build/libs/warsim-frontline-weapons-0.2.0-SNAPSHOT.jar`

Official Battle 部署时同时安装主插件、Weapons 插件和 CraftEngine 26.6.3，
将 `dev-environment/craftengine/resources/warsim` 复制到 CraftEngine 的
`resources/warsim`。Weapons 配置使用
`dev-environment/official-war-01/weapons-config.yml.example`；Lobby 使用禁用模板。

控制方式：

- 主手右键空气或方块：单发射击；持合法测试武器时始终阻止原版右键交互。
- Q/丢弃键：开始装填并取消物品掉落。
- 切换离开正在装填的武器：取消装填且不消耗备弹。

管理员命令为 `/warsim weapon status|list|give|ammo|refill|clear|inspect`。
测试定义为 `test_rifle`、`test_pistol` 和 `test_smg`，均只支持点击触发
`SEMI_AUTO`。客户端人工验收仍包括模型显示、真实玩家发放、射击、装填、
AABB命中、方块遮挡、友军阻止、ActionBar、实际伤害与击杀提示。

## T-007 据点与兵力票数

Official Battle 现已包含平台无关的圆柱形据点、先中和后占领流程、争夺冻结、
人数差加速、进攻方兵力票数和票数耗尽结束 Match 的原型。Lobby 默认完全禁用
Objective 与 Ticket，不创建扫描或 BossBar。

默认 `alpha` 坐标仅用于测试，地图制作时必须调整：

```yaml
objectives:
  enabled: true
  scan-interval-ticks: 5
  points:
    alpha:
      display-name: "A点"
      world: "world"
      center: { x: 0.5, y: 64.0, z: 0.5 }
      radius: 8.0
      vertical-range: 6.0
      initial-owner: "DEFENDERS"
```

兵力票数默认仅对进攻方启用：

```yaml
tickets:
  enabled: true
  attackers: { enabled: true, initial: 300, maximum: 500 }
  defenders: { enabled: false, initial: 0, maximum: 0 }
  behavior: { end-match-on-attackers-depleted: true }
```

管理员命令：

- `/warsim objective status [id]`
- `/warsim objective list|lock|unlock|reset|setowner`
- `/warsim tickets status`
- `/warsim tickets set|add|take <attackers|defenders> <数量>`

当前不包含出生、死亡扣票、多防区推进、战绩或经济奖励。没有客户端时仍需人工验收：
真实玩家 Presence、双方争夺、自然占领进度、BossBar 视觉和捕获聊天。
完整设计见 [docs/OBJECTIVES_AND_TICKETS.md](docs/OBJECTIVES_AND_TICKETS.md)。

WarSim: Frontline 是面向 Minecraft Java 26.1.2 / Paper 26.1.2 的一战 50v50 商业战场服工程。当前完成 T-001 至 T-006，包括跨服转服、PostgreSQL数据层、Redis控制平面、Match生命周期以及阵营和小队基础。

## T-006 阵营与小队

Official Battle 节点具备 50v50 双阵营、每方十个固定五人小队、自动平衡、队长转移、
同局断线恢复以及敌我关系查询。Lobby 配置 `teams.enabled: false` 与
`squads.enabled: false`。

玩家使用 `/warsim squad status|list|join|leave|leader`；管理员使用
`/warsim team status|list|player|move|rebalance` 和
`/warsim squad inspect|leader|move|remove`。阵营不允许玩家自行切换。

无客户端时，可在 Official-War-01 控制台执行 `warsim team status`、
`warsim team list`、`warsim squad list`，再通过 Match start/end/reset 验证新局 Roster。
真实玩家分配、切换、队长提示和断线恢复需客户端人工验收。详细规则见
`docs/TEAMS_AND_SQUADS.md`。

## 技术基线

- Java 25
- Gradle 9.6.0 Kotlin DSL
- Paper API `26.1.2.build.69-stable`
- Velocity API `3.5.0-SNAPSHOT`
- PostgreSQL使用JDBC/HikariCP/Flyway保存长期数据；Redis使用Lettuce保存短期节点状态与可靠控制消息
- 载具模型、骨骼、动画和挂载仅允许 ModelEngine；基础实体和技能仅允许 MythicMobs

## 构建

确保 `JAVA_HOME` 指向 Java 25。

Windows：

```powershell
.\gradlew.bat clean build
```

Linux/macOS：

```bash
./gradlew clean build
```

主要产物：

- Paper：`warsim-match/build/libs/warsim-frontline-paper-0.2.0-SNAPSHOT.jar`
- Velocity：`warsim-velocity/build/libs/warsim-frontline-velocity-0.2.0-SNAPSHOT.jar`

Paper、Velocity、ModelEngine 和 MythicMobs API 均不会被打入上述 JAR。

普通测试：

```powershell
.\gradlew.bat test
```

PostgreSQL Testcontainers 集成测试：

```powershell
.\gradlew.bat integrationTest
```

集成测试需要可用的 Docker Engine。Docker 不可用时测试会明确标记为跳过，不会使用 H2 模拟 PostgreSQL。

Redis集成测试：

```powershell
.\gradlew.bat :warsim-network:redisIntegrationTest
```

该任务使用真实Redis Testcontainers镜像，不使用嵌入式或Mock Redis。

## PostgreSQL 配置

生产环境必须为 Frontline 创建独立数据库和独立用户，不得与 Euro WarSim 共用数据库、用户或业务表。示例：

```sql
CREATE USER warsim_frontline WITH PASSWORD 'replace-with-a-generated-secret';
CREATE DATABASE warsim_frontline OWNER warsim_frontline;
\connect warsim_frontline
CREATE SCHEMA warsim_frontline AUTHORIZATION warsim_frontline;
```

在 Paper 插件 `config.yml` 中设置 `database.enabled: true`。默认 Schema 为 `warsim_frontline`，Flyway 历史表为 `frontline_schema_history`。

生产密码推荐通过环境变量提供：

- `WARSIM_DB_URL`
- `WARSIM_DB_USERNAME`
- `WARSIM_DB_PASSWORD`

非空环境变量优先于 YAML；空环境变量不会覆盖有效配置。日志与 `/warsim status` 均不会显示密码或环境变量内容。完整字段及权限建议见 [docs/DATABASE.md](docs/DATABASE.md)。

## T-002 测试部署

1. 准备一个 Velocity、一个 Lobby Paper 和一个 Official-War-01 Paper 进程。
2. 将 Velocity JAR 放入代理 `plugins/`，将 Paper JAR 分别放入两个后端的 `plugins/`。
3. 参考 `dev-environment/velocity/velocity.toml.example` 注册 `lobby-01` 和 `official-war-01`。
4. 为 Velocity 现代转发自行生成真实密钥，并在两个 Paper 后端配置同一密钥。不要提交该密钥。
5. 分别使用 `dev-environment/lobby-01/config.yml.example` 和 `dev-environment/official-war-01/config.yml.example` 覆盖插件配置。
6. 依次启动两个 Paper 后端与 Velocity，然后从客户端连接 Velocity。
7. 在 Lobby 执行 `/warsim join official-war-01`。

成功时玩家会进入 `official-war-01`，目标节点日志会显示本地会话已激活。常见失败包括：目标未在 Velocity 注册、后端未启动、现代转发密钥不一致、节点名称不一致、权限不足、请求超时或目标不在允许目录。

Plugin Messaging 依赖在线玩家作为消息载体，只用于当前最小转服闭环；它不是 Redis 的替代品。

## Redis控制平面

Redis发布Paper节点心跳、在线人数、容量和可加入状态，并使用Streams在没有玩家载体时发送版本化控制消息。PostgreSQL仍是长期资产数据库，Redis不得保存玩家资产。

开发和生产环境需要 Redis 7+ 或兼容 Redis Streams、Consumer Group 与 `XAUTOCLAIM` 的服务。Paper 在 `config.yml` 中配置 `redis.*`；Velocity 首次启动后在 `plugins/warsim-frontline/network.properties` 中配置对应的 `redis.*`。两端必须使用同一 URI、数据库和 namespace，且每个进程使用不同节点 ID。最小开发配置：

```yaml
redis:
  enabled: true
  uri: "redis://127.0.0.1:6379"
  database: 0
  namespace: "warsim:frontline:v1"
```

Paper与Velocity均支持以下非空环境变量覆盖：

- `WARSIM_REDIS_URI`
- `WARSIM_REDIS_USERNAME`
- `WARSIM_REDIS_PASSWORD`

Redis不可用时Paper和Velocity继续启动，T-002转服回退到“Velocity服务器已注册且目标在允许列表”的原始验证。该模式无法证明后端当前健康或未满，因此日志和状态命令会明确显示降级。

运维验证命令：

```text
/warsim redis ping official-war-01
```

权限为`warsim.admin.redis.ping`。完整键空间、TTL、Streams与生产建议见[docs/REDIS.md](docs/REDIS.md)。

## 命令

- `/warsim status` — 权限 `warsim.admin.status`，包含数据库生命周期、健康、连接池与执行器指标
- `/warsim join <节点ID>` — 权限 `warsim.player.join`，仅 Lobby 玩家可用
- `/warsim redis ping <节点ID>` — 权限 `warsim.admin.redis.ping`，验证无玩家载体的 Redis Streams 控制消息往返
- `/warsim match status` — 查看当前matchId、状态、revision、人数和计时
- `/warsim match start [force]` — 从WAITING进入WARMUP
- `/warsim match end [原因]` — 取消热身或结束PLAYING
- `/warsim match reset` — 在允许状态执行安全重置
- `/warsim match recover` — 从FAILED创建新Match

## Match生命周期

Official Battle默认启用`frontline_offensive`模式，流程为`BOOTSTRAPPING → WAITING → WARMUP → PLAYING → ENDING → RESETTING → WAITING`。Lobby模板明确禁用Match，不创建中心tick、参与者注册表或BossBar。

配置位于Paper插件`config.yml`的`match`段，可设置最低/最大人数、热身、回合、结束、重置超时和自动循环。当前只实现生命周期，不实现阵营、小队、据点、票数、武器、载具、匹配或正式世界回滚。完整设计见[docs/MATCH_LIFECYCLE.md](docs/MATCH_LIFECYCLE.md)。

本地控制台验收可将最低人数设为1、热身设为5秒，然后依次执行：

```text
warsim match status
warsim match start force
warsim match end
warsim match status
```

## 模块

公共基础：`warsim-api`、`warsim-common`、`warsim-network`、`warsim-database`、`warsim-resourcepack`。

领域边界：`warsim-match`、`warsim-squad`、`warsim-classes`、`warsim-weapons`、`warsim-vehicles`、`warsim-destruction`、`warsim-progression`、`warsim-cosmetics`、`warsim-rental`、`warsim-anticheat`、`warsim-admin`。

代理适配：`warsim-velocity`。

## 手工启动验证

T-002 的纯协议、生命周期、配置、请求注册表和会话注册表由单元测试覆盖。实际转服仍需测试者提供 Minecraft 客户端、服务端运行包、EULA 同意和现代转发密钥。

数据库常见故障包括 PostgreSQL 未启动、用户名或密码错误、Schema 权限不足、迁移失败、连接超时及执行器队列满。数据库不可用不会阻止 Paper 插件和 T-002 转服功能启动；档案同步会安全跳过并输出限频日志。

## T-009 Performance sampler

T-009 implements the main Paper-owned `PerformanceService`, bounded metric
windows, nearest-rank percentile snapshots, slow-operation alerts,
JSON/Markdown report export, and disabled-by-default pure-Java SyntheticLoad
execution capability.

Commands are only registered under `/warsim perf`; there is no `/perf` or
`/performance` top-level command. This workstation implementation did not run
tests, Paper, CraftEngine, Redis, PostgreSQL, synthetic scenarios, or pressure
tests, and it does not claim any TPS/MSPT or 100-player capacity result.

See `docs/PERFORMANCE_SAMPLER.md`.

## T-010 Load-test map definitions

T-010 implements stable definitions for the `warsim_load_test` template world,
ten fixed zones, ten fixed scenarios, 100 deterministic slots, 50v50 squad
distribution, read-only validation, in-memory `prepare`, idempotent `clean`,
and `/warsim loadmap ...` diagnostics.

The subsystem does not create worlds, copy worlds, place blocks, teleport
players, spawn fake players/NPCs/ArmorStands, start matches, run synthetic
load, or output performance conclusions. All default coordinates are template
coordinates that must be confirmed or adjusted by an administrator.

See `docs/LOAD_TEST_MAP.md`.

## T-012 Combat outcomes and HUD

T-012 adds current-match combat statistics, effective-damage contribution,
authoritative death settlement, spawn protection, ActionBar arbitration, KillFeed
and WarSim Sidebar HUD. Statistics are match-local and are not persisted. Respawn
tickets are still charged only by the T-011 deployment transaction.

The workstation implementation does not run tests, Paper, Velocity, CraftEngine,
Redis, PostgreSQL, synthetic scenarios or client validation. See
`docs/COMBAT_OUTCOMES_AND_HUD.md`.
