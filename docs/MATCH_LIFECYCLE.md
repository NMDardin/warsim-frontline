# Match生命周期

## 状态图

```text
BOOTSTRAPPING -> WAITING -> WARMUP -> PLAYING -> ENDING -> RESETTING -> WAITING
       |            |          |          |          |           |
       +----------> FAILED <---+----------+----------+-----------+

任意运行状态 -> STOPPING -> STOPPED
```

`WAITING`不能直接进入`PLAYING`，`PLAYING`不能直接回到`WAITING`。每次合法转换递增`lifecycleRevision`。延迟任务和异步重置结果必须同时匹配`matchId`、revision以及预期状态，旧局任务只增加stale指标，不得修改新局。

## 自动开局与热身

节点进入`WAITING`后接受参与者。启用自动开局且人数达到最低值时进入`WARMUP`。普通热身期间人数不足会取消并返回`WAITING`；管理员`start force`只跳过人数要求，仍必须完整经过热身。

`auto-cycle=false`只影响重置后的新局：新matchId进入手动`WAITING`，人数满足也不自动开局，管理员仍可执行start。

## 回合结束与重置

`PLAYING`只实现回合计时，时间到以`TIME_LIMIT`进入`ENDING`。管理员在WARMUP执行end会取消热身，在PLAYING执行end会进入ENDING。

ENDING结束后进入RESETTING。当前安全重置实现只清除WarSim参与者、局级事件订阅、计时和展示，不复制或删除世界。重置成功归档旧局并创建新matchId；异常或超时进入FAILED。

FAILED中的reset和recover复用同一恢复路径：清理失败局、创建新matchId并进入WAITING。系统不会自动无限重试。

## 玩家会话

`LocalSessionRegistry`表示玩家与当前Paper节点的连接；`MatchParticipantRegistry`表示玩家参与当前matchId。连接建立顺序为LocalSession后MatchParticipant，离开顺序相反。Match重置只关闭参与者，不伪造玩家离开节点。

本阶段不分配阵营、小队、出生点、枪械、载具或战绩。

## Redis映射

- BOOTSTRAPPING：STARTING
- WAITING/WARMUP：AVAILABLE
- PLAYING：根据是否允许中途加入映射AVAILABLE或DRAINING
- ENDING/RESETTING：DRAINING
- STOPPING：STOPPING
- STOPPED/FAILED：UNAVAILABLE

Redis心跳仍使用Paper实际在线人数，`reservedPlayers=0`。Redis或PostgreSQL不可用不会推动Match进入FAILED。

## 管理命令

- `/warsim match status`
- `/warsim match start [force]`
- `/warsim match end [原因]`
- `/warsim match reset`
- `/warsim match recover`

原因文本移除控制字符并限制128字符。

## 扩展点与限制

T-006可在参与者模型上增加阵营与小队装配；T-007可替换`MatchResetService`并接入受控地图目标和重置。本阶段不实现队列、匹配、据点、票数、正式地图回滚或资产持久化。
