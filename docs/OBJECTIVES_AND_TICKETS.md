# 据点与兵力票数

## 据点模型

据点由稳定 `ObjectiveId`、中文显示名、圆柱区域、初始所有权、锁定状态、占领规则和
票数奖励组成。ID 只允许小写字母、数字、下划线和连字符，长度1至32。

所有权为 `NEUTRAL`、`ATTACKERS` 或 `DEFENDERS`；状态为 `LOCKED`、`IDLE`、
`CAPTURING`、`NEUTRALIZING`、`CONTESTED` 或 `CONTROLLED`。进度始终夹取在
0至1，不使用浮点精确相等判断。

## 区域与 Presence

圆柱区域要求世界名相同、水平距离平方不超过 `radius²`，且Y差绝对值不超过
`vertical-range`。Paper 每次扫描只读取每名在线玩家的位置一次。

计入 Presence 的玩家必须同时具有：

1. 当前节点 LocalSession；
2. 当前 matchId 的 ACTIVE MatchParticipant；
3. 当前 matchId 且 connected 的 TeamAssignment；
4. 非旁观模式；
5. PLAYING 状态和匹配的世界/区域。

断线保留、WAITING参与者、上一局Roster、NPC和其他实体不会进入Presence。

## 中和、占领与争夺

双方人数相同且大于0时进入 `CONTESTED` 并冻结。人数不同时使用净人数差：

```text
effectivePlayers = min(maximumEffectivePlayers, abs(attackers - defenders))
multiplier = 1 + (effectivePlayers - 1) * additionalPlayerRate
```

敌方先把当前 owner 的控制进度从1降到0；到0时发布一次中和事件并转为中立。
剩余推进量可继续从0捕获。达到1时变更所有权、发布一次捕获事件并发放配置奖励。
同方返回自身据点只恢复控制，不重复捕获。

空区域默认 `RETURN_TO_OWNER`：有正式 owner 时恢复至完全控制；中立的部分捕获
回退至0。单次时间差最多计入1秒，避免卡顿后瞬间占满。

## 兵力票数

默认只有进攻方启用有限票数，初始300、上限500。set/add/take 使用原子操作，
通过 UUID operationId 去重，并使用有界1024条记录。结果永远处于0至maximum。

据点奖励使用由 matchId、objectiveId、revision 和占领方生成的确定性 operationId。
管理员 `setowner` 不触发奖励。进攻方票数从正数首次变为0时发布一次耗尽事件；
配置启用时以 `TICKETS_DEPLETED` 结束当前 PLAYING Match。

本阶段不因退出、断线或死亡扣票，`RESPAWN_COST` 仅为后续系统预留。

## 生命周期与性能

- WAITING/WARMUP：显示初始状态，不推进。
- PLAYING：扫描Presence、推进据点并接受合法票数修改。
- ENDING：冻结据点并拒绝普通修改。
- RESETTING：清除显示、监听器、去重记录、旧Objective与Ticket实例。
- 新matchId：重建初始owner和票池，不继承旧局状态。

一个战场仅使用一个中心Bukkit任务。据点初始目标为100名玩家、1至5个据点、每5
tick扫描；使用距离平方、世界快速过滤、变化驱动BossBar，扫描中不执行I/O。

## 管理命令

```text
/warsim objective status [id]
/warsim objective list
/warsim objective lock|unlock <id>
/warsim objective reset [id]
/warsim objective setowner <id> <neutral|attackers|defenders>
/warsim tickets status
/warsim tickets set|add|take <attackers|defenders> <数量>
```

## T-008扩展点

后续可增加防区解锁顺序、正式进攻路线、出生部署和重生扣票。扩展必须继续使用
ObjectiveService、TicketService、MatchService 与 RosterService 边界，不得把
世界对象放入领域核心，也不得在本原型上直接加入武器、伤害或经济持久化。
