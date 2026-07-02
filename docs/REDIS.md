# Redis控制平面

## 拓扑

Velocity、Lobby和每个战场Paper进程分别建立Lettuce异步连接。Redis承载短期节点状态、心跳、容量和无玩家载体的控制消息；PostgreSQL承载长期玩家和商业数据。

## 键空间

默认namespace为`warsim:frontline:v1`：

- `{namespace}:nodes:{nodeId}`：节点Hash，带TTL。
- `{namespace}:nodes:last_seen`：按Epoch毫秒排序的节点索引。
- `{namespace}:streams:control:{targetNodeId}`：目标控制流。
- `{namespace}:streams:dead_letter:{targetNodeId}`：目标死信流。
- `{namespace}:dedup:{targetNodeId}:{messageId}`：成功去重记录。
- `{namespace}:processing:{targetNodeId}:{messageId}`：短期处理中锁。

生产逻辑不使用`KEYS`，节点目录只读取有效时间窗口，默认最多500个节点。

## 心跳与目录

默认每5秒发布心跳，Hash TTL为15秒。Lua脚本比较instanceId与startedAt，防止旧进程延迟心跳覆盖新实例。关闭时节点发布停止状态并尽力删除自身Hash和索引；崩溃节点依靠TTL与last_seen窗口过滤。

节点只有在心跳未过期、状态AVAILABLE、接受玩家且剩余容量大于零时才可加入。

## Streams

每个目标节点使用独立Stream和Consumer Group。消费者使用有限批量、阻塞式异步读取；Pending消息通过XAUTOCLAIM接管。成功消息写去重键后XACK；处理失败重新发布并增加attempt；超过上限写入死信流。Stream使用近似MAXLEN裁剪。

当前消息仅允许NODE_PING、NODE_PONG、NODE_REFRESH_REQUEST和NODE_REFRESH_RESPONSE，不支持远程命令、玩家踢出、文件或配置修改。

## 配置与环境变量

配置位于`redis`、`redis.tls`、`redis.connection`、`redis.heartbeat`和`redis.streams`。非空`WARSIM_REDIS_URI`、`WARSIM_REDIS_USERNAME`、`WARSIM_REDIS_PASSWORD`覆盖YAML。状态和日志只显示脱敏地址。

## 安全与故障

生产应使用独立Redis用户、最小ACL、受信网络和TLS。禁止存储数据库密码、玩家IP或聊天内容。Redis不可用时T-002回退到Velocity注册服务器验证，这能维持服务但不能证明目标实时健康或容量。

监控应包含连接状态、重连、成功/失败心跳、活动节点、已发布/消费/确认/重试/死信/重复/过期/非法/Pending/In-flight消息。

## 持久化与集群限制

控制消息需要Redis AOF或适当RDB策略，但Redis持久化不替代PostgreSQL备份。当前键设计按单实例/主从Redis验证；Streams目标键未使用Redis Cluster hash tag，跨键Lua心跳脚本在Cluster中可能跨槽失败。启用Redis Cluster前必须设计统一hash tag并完成专门集成测试。

## 故障排查

- 连接失败：检查URI、DNS、端口、ACL和TLS。
- 认证失败：检查独立Redis用户名与密码，勿在日志粘贴URI。
- 节点消失：检查心跳间隔、TTL、系统时钟和事件循环。
- Pending增长：检查处理器失败、消费者名称和XAUTOCLAIM。
- 死信增长：检查消息版本、目标、过期时间及处理器异常。
- 转服降级：使用`/warsim status`确认Redis状态，再用`/warsim redis ping <节点ID>`验证控制平面。
