# 阿里云函数计算部署

本目录是“东西不跑”的安全 API 代理。Android APK 中不再包含 DeepSeek、千问或高德密钥；真实密钥只保存在函数计算环境变量中。

## 1. 本地构建部署包

在 `server` 目录运行：

```powershell
pnpm install
pnpm test
pnpm run build
Compress-Archive -Path package.json,dist -DestinationPath youshu-api-proxy.zip -Force
```

生产代码没有第三方运行时依赖，因此 ZIP 只需要 `package.json` 和 `dist`。

## 2. 创建函数

在阿里云函数计算控制台选择“创建函数”：

- 函数名称：`youshu-api-proxy`
- 运行环境：自定义运行时 / Node.js / Node.js 20 / Debian 11
- 函数类型：Web 函数 / HTTP 函数
- 代码上传：上传 `youshu-api-proxy.zip`
- 启动命令：`node dist/src/app.js`
- 监听端口：`9000`；程序会优先读取平台注入的 `FC_CUSTOM_LISTEN_PORT`
- 内存：512 MB
- 超时时间：120 秒
- 公网访问：开启
- HTTP 触发器认证：无需认证（应用内部另有短期会话令牌）

## 3. 配置环境变量

在函数“配置 -> 环境变量”中添加：

```text
DEEPSEEK_API_KEY=<新建的 DeepSeek Key>
QWEN_API_KEY=<新建的百炼 Key>
AMAP_WEB_API_KEY=<新建的高德 Web 服务 Key>
SESSION_SIGNING_SECRET=<至少 32 位的随机字符串>
DEEPSEEK_MODEL=deepseek-v4-pro
QWEN_VISION_MODEL=qwen3.7-plus
QWEN_SPEECH_MODEL=qwen3-asr-flash
ALLOWED_APP_VERSIONS=1.2.0
```

不要把环境变量值发到聊天、截图、GitHub、Gradle 或 APK 中。已经公开过的旧 Key 必须在对应平台删除并重新创建。

## 4. 验证

部署完成后访问函数公网 URL 的 `/health`，应返回：

```json
{"status":"ok"}
```

将公网 URL（不含末尾 `/`）写入 Android 构建参数：

```powershell
.\gradlew.bat :app:assembleDebug -Pyoushu.backend.baseUrl=https://你的函数公网地址
```

公网 URL 不是秘密，可以提交到仓库；第三方 API Key 绝不能提交。

## 5. 上线保护

- 在 DeepSeek、百炼和高德控制台设置余额告警、调用配额和每日上限。
- 在函数计算中设置最大实例数和费用告警。
- 定期查看仅包含状态码、耗时和请求 ID 的日志，不记录聊天、图片、音频、位置或认证头。
- 发生异常调用时先轮换服务端环境变量中的 Key，再检查调用日志。
