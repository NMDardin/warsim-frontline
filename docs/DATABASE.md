# Frontline 数据库

## 拓扑与隔离

每个 Paper 战场进程通过自己的 HikariCP 连接池访问同一个 Frontline PostgreSQL 数据库。数据库、用户、密码和 Schema 必须专用于 WarSim: Frontline。不得与 Euro WarSim 共用业务表，也不得跨库读取其经济、等级、战绩或资产。

默认值：

- 数据库：`warsim_frontline`
- 用户：`warsim_frontline`
- Schema：`warsim_frontline`
- Flyway历史表：`frontline_schema_history`

## 创建示例

以下命令中的密码只是占位符，必须替换为生成的秘密：

```sql
CREATE USER warsim_frontline WITH PASSWORD 'replace-with-a-generated-secret';
CREATE DATABASE warsim_frontline OWNER warsim_frontline;
\connect warsim_frontline
CREATE SCHEMA warsim_frontline AUTHORIZATION warsim_frontline;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA warsim_frontline TO warsim_frontline;
```

应用用户不应拥有超级用户、创建角色或创建其他数据库的权限。

## 配置字段

`database.enabled` 控制模块是否连接。禁用时空密码合法且状态为 `DISABLED`。启用时密码为空会令数据库模块进入 `FAILED`，但 Paper 插件继续运行。

连接字段包括 `jdbc-url`、`username`、`password`、`schema`、`migrations-enabled` 和 `health-check-interval-seconds`。连接池字段位于 `database.pool`，执行器字段位于 `database.executor`。

Schema 必须匹配 `[a-z][a-z0-9_]{0,47}`。JDBC URL 必须使用 `jdbc:postgresql://`。

非空环境变量按以下优先级覆盖 YAML：

1. `WARSIM_DB_URL`
2. `WARSIM_DB_USERNAME`
3. `WARSIM_DB_PASSWORD`

空环境变量不会覆盖配置。任何环境变量值和密码都不得写入日志。

## 迁移规则

迁移位于 `warsim-database/src/main/resources/db/migration`。V1 创建 `player_profiles`。迁移文件一旦发布或执行就不得修改；后续变化必须创建新的版本化迁移。

生产发布前应在与生产 PostgreSQL 主版本一致的临时数据库运行：

```powershell
.\gradlew.bat integrationTest
```

## 备份

迁移前应完成逻辑或物理备份并验证恢复流程。备份必须包含 Frontline 数据库和 `warsim_frontline` Schema，但不应混入 Euro WarSim 数据。数据库备份不替代配置与密钥的独立安全备份。

## 开发与生产差异

开发环境可使用本机 PostgreSQL 或 Testcontainers；禁止使用 H2 声称兼容生产 SQL。生产环境应启用 TLS、限制网络来源、配置操作系统 TCP keepalive、监控连接数与慢查询，并通过秘密管理系统提供密码。

## 故障排查

- `CONFIGURATION_ERROR`：检查URL、Schema、连接池范围及非空密码。
- `AUTHENTICATION_ERROR`：检查独立用户密码与认证规则。
- `CONNECTION_ERROR`：检查PostgreSQL进程、监听地址、防火墙和数据库名称。
- `MIGRATION_ERROR`：检查Schema权限、历史表和迁移校验；不要修改旧迁移。
- `TIMEOUT`：检查数据库负载、网络和连接池配置。
- `QUEUE_FULL`：检查数据库延迟与任务产生速率，不要通过无界队列掩盖问题。

使用 `/warsim status` 查看生命周期、健康、Schema、连接池和执行器指标。命令不会显示密码或完整 JDBC URL。
