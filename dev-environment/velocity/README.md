# Velocity 开发模板

复制 `velocity.toml.example` 为 Velocity 的 `velocity.toml`。自行生成 `forwarding.secret`，不要提交真实密钥。Velocity 注册服务器名称必须与节点 ID 完全一致。

现代转发需要代理和两个 Paper 后端使用同一个密钥。示例只绑定本机后端地址，不包含生产密钥。

首次启动 WarSim Velocity 插件后会生成 `plugins/warsim-frontline/network.properties`。Redis 默认关闭；测试控制平面时，在该文件中启用 `redis.enabled=true`，并确保 `proxy-node-id=velocity-01`。生产密码应通过非空 `WARSIM_REDIS_PASSWORD` 环境变量提供，不要提交配置中的真实密码。
