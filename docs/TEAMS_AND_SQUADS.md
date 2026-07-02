# 阵营与小队

## 模型

每个 Match 只拥有一个内存 Roster。战场侧固定为 `ATTACKERS` 与 `DEFENDERS`，
不在本层绑定历史国家。每方最多 50 人，并固定提供 ALPHA 至 JULIET 十个小队，
每队最多 5 名活动成员。

Roster 只保存 UUID、玩家显示名、时间和不可变领域值，不保存 Bukkit `Player`，
也不访问 PostgreSQL、Redis、网络或文件系统。

## 自动平衡

自动分配选择当前硬负载较少且未满的阵营。硬负载包含活动成员和断线重连保留。
双方相同时使用每局交替游标，成功分配后确定性翻转，不依赖集合遍历顺序。
双方各 50 人时拒绝第 101 名玩家。

小队分配仅扫描玩家阵营的十个固定小队。优先选择已有成员、未满且人数最少的小队，
相同人数按 SquadId 顺序；没有已建立候选时选择第一个空小队。

## 队长与切换

第一名成员成为队长。队长离开、换队或断线时，选择加入时间最早的活动成员继任；
时间相同按 UUID 字典序。非空小队恰有一名队长，空小队没有队长。

普通玩家不能切换阵营，只能在配置允许的 Match 状态切换本阵营小队。成功切换后开始
冷却，失败与离开小队不触发冷却。管理员 `force` 只能突破平衡差值，不能突破 50 人硬容量。

## 重连与 Match 生命周期

断线保留仅限当前 matchId。宽限期内保留阵营硬容量，但释放小队活动容量。重连时优先恢复
原小队；原小队已满则在原阵营重新自动分配。超过宽限期后删除保留并释放阵营容量。
保留数据不写入 Redis 或 PostgreSQL，插件重启后允许丢失。

- WAITING/WARMUP：允许接纳和配置允许的小队修改。
- PLAYING：按中途加入和小队切换配置执行。
- ENDING：保持只读分配，不接受新接纳。
- RESETTING：停止写入并清空保留、小队和阵营。
- 新 matchId：创建全新 Roster，不继承上一局分配。
- FAILED：保留诊断快照并停止写入。
- STOPPING/STOPPED：清空全部 Roster 数据。

Participant 接纳使用 `RosterAdmissionPlan`：先规划阵营和小队，在 Match 同步临界区创建
Participant，再提交 Roster，最后才发布加入事件。提交失败会在事件发布前回滚 Participant。

## 关系、不变量与性能

`CombatAffiliationService` 提供 SELF、SQUADMATE、TEAMMATE、ENEMY、UNKNOWN，
且只查询当前 matchId。后续伤害系统只能依赖该接口。

诊断检查覆盖单阵营、单小队、容量、队长数量和 matchId 一致性。严重错误使 Roster
进入 FAILED，不会静默修复或关闭数据库、Redis、Velocity。

玩家主索引使用 UUID Map；自动小队选择最多扫描十个小队。断线保留由现有 Match 中心
tick 低频清理，不创建每玩家任务，不执行 I/O 或并行流。

T-007 可增加出生部署策略；T-008 枪械与伤害系统应通过 `CombatAffiliationService`
判断敌我关系。
