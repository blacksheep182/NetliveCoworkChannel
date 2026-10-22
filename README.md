# NetliveCoworkChannel

独立 Java 21 / Spring Boot Channel 服务，提供平台适配、文本收发与显式人工应答，复用 NetliveCoworkPy 的会话 API。当前只接已有授权会话；配置文件管理连接、外部身份与会话白名单，不新增桌面页面。云端总体设计由 NetliveCoworkPy 的 `docs/云地协同方案/Channel接入.md` 维护，本仓库维护 Channel 实现、Java 测试及接入说明。

## 构建与启动

在 NetliveCoworkPy 仓库执行 `uv sync` 并按原方式启动 Python 后端，确认其已包含 `require_explicit_hitl`。在本仓库根目录独立构建和启动，不需要将 Java 源码放进外壳：

```powershell
mvn clean package
# 设置本机管理密钥，不写进配置或提交到仓库
$env:CHANNEL_ADMIN_TOKEN = '<至少32字符的随机值>'
java -jar target/netlivecowork-channel-0.1.0.jar --spring.config.additional-location=file:./application-local.yml
```

将 `application-example.yml` 复制为已忽略的 `application-local.yml`，填写实际后端地址和已有会话 ID。`workspace-ref` 是配置中的空间标识/来源标签，不是文件路径；授权由该绑定明确列出的完整目标决定，不会自动放开同目录的其他会话。未 `/use` 时不自动选择或执行。

后端/Channel 使用各自数据库。默认仅监听回环，暂不接入公网。对已有 Python Channel 同一微信账号，先停旧连接，再启动 Java，避免两套 worker 同时接管。本轮不自动迁移旧库；可沿用原受保护凭据文件（`bot_token`、可选 `baseurl` 格式），其余连接/授权需显式配置。

## 微信登录和配置

管理请求都需要 `Authorization: Bearer <CHANNEL_ADMIN_TOKEN>`，包括 `/health`。密钥不交给平台用户。

```powershell
$headers = @{ Authorization = "Bearer $env:CHANNEL_ADMIN_TOKEN" }
Invoke-RestMethod -Method Post -Headers $headers http://127.0.0.1:17927/api/v1/channels/connections/weixin-main/auth
# qrcode_url 是授权链接，由用户自行打开并确认登录
Invoke-RestMethod -Headers $headers http://127.0.0.1:17927/api/v1/channels/connections/weixin-main/auth
```

登录状态需主动查询，confirmed 后凭据保存到配置的 secret-file；配置文件只放路径。微信先发一条文字，用 `GET /api/v1/channels/inbox` 查看该消息的外部用户/聊天标识，填入绑定配置后重启服务。未配对消息只回复指引，不会在配对后自动执行。

平台内发送 `/list`、`/use <序号或后端/会话ID>`、普通文字、`/status` 或 `/resume`。提问使用 `answer <交互ID> <文字>`，审批使用 `approve <交互ID>` 或 `reject <交互ID> <理由>`；普通的 yes 不表示批准，过期或他人交互不能处理。切换当前会话之后，显式应答仍返回该交互的原会话。

## 诊断与边界

- `GET /api/v1/channels/connections`：连接状态，不返回 token。
- `GET /api/v1/channels/inbox`：收件状态、目标与错误。
- `GET /api/v1/channels/deliveries`：固定回复、目标与发送状态。
- 连接类型为 fake 时，`POST /api/v1/channels/connections/{id}/test/inbound` 接受 eventId/externalUserId/conversationId/text，用于联调；只允许管理凭据。

收件以平台稳定 ID 去重；outbox 与游标同事务。缺口会暂停并记录 error。后端尚无原请求核对时，可能已经生效的派发保留 unknown，不自动重复；平台发送结果未知也不自动重发。unknown 需核对原会话/平台，不要据消息缺少回执认定执行失败。本轮平台发送故障保守停下，不提供自动重投控制台。

服务配置或授权改变后重启；待发内容会按当前配置复验。Channel 停止不停止后端任务。云端委托、动态权限、共享库/租约、本地主动连接、手机新建和多模态仍为后续工作，静态管理 token 不代表已实现云端鉴权。

## 后端接口

只依赖 NetliveCoworkPy 的 HTTP 接口，不直接导入 ctx、访问会话数据库或共享库。后台地址及凭据由 `channel.backends` 配置：

| 现有接口 | 用途 |
| --- | --- |
| `GET /api/v1/sessions/{id}` | 当前状态、模型与接收 agent |
| `POST /api/v1/sessions/{id}/prepare` | 懒装填/执行准备 |
| `POST /api/v1/sessions/{id}/messages` | 文本提交，设置 `require_explicit_hitl=true` |
| `GET /api/v1/sessions/{id}/history?history_version=2` | 按稳定帧 ID 消费持久回复 |
| `GET /api/v1/hitl/pending?session_id=...` | 读取会话提问/审批 |
| `POST /api/v1/hitl/{id}/answer\|approve\|reject?session_id=...` | 显式应答及续跑 |
| `POST /api/v1/sessions/{id}/resume` | 恢复中断执行 |

后端在原会话锁内复验 `require_explicit_hitl`，待审批时不允许普通文字隐式批准；不能用 Java 的先查再提交替代这个保护。Python 兼容字段、ctx wheel 和跨进程测试归外壳仓库维护。当前没有后端请求结果核对接口，unknown 保留上述恢复边界。

## 新增文本平台

新增一个 `@Component` 实现 `ChannelAdapter`，放在 `com.netlivecowork.channel` 包或其子包；在 `channel.connections` 中填写与 `type()` 一致的平台类型，并配置身份/目标绑定即可。Spring 自动发现 adapter，公共配置不维护平台白名单，未知类型、重复类型和平台配置错误会在启动时拒绝。

必需实现 `type/state/send`；按平台需要覆写配置校验 `validate`、启停 `start/stop`、交互认证 `beginAuth/authStatus`。平台特有参数和凭据由 adapter 读取自己的配置/secret-file；微信的默认地址和凭据要求已移到微信实现中。一个 adapter bean 可管理多个连接，`start` 按连接调用，`stop` 在服务关闭时调用一次。入站复用 `ChannelService.receive(Inbound)`，其成功返回代表收件已落库，之后才能确认平台消息或推进平台游标。

长轮询在 adapter 的 `start` 中启动自己的 worker；回调平台覆写 `webhook`，入口为 `GET/POST /api/v1/channels/connections/{id}/webhook`。该入口交给 adapter 验证平台签名/挑战，验证成功后才能提交入站；未实现时默认拒绝。管理接口继续要求管理 token。新平台不需要修改公共 controller、发送分发或会话处理，也不需要改 Python/ctx。当前接口范围仍是文本，附件与卡片不在本次抽取范围。

## 验证

```powershell
mvn test
# 在 NetliveCoworkChannel 根目录先构建 jar，再切换到 Python 外壳执行联通测试
mvn package
$env:NLC_TEST_CHANNEL_JAR = (Resolve-Path target/netlivecowork-channel-0.1.0.jar).Path
Set-Location '<NetliveCoworkPy 工作目录>'
.\.venv\Scripts\python.exe -m pytest tests/test_channel_java_e2e.py
```

Java 测试覆盖去重、原目标应答、普通文字不批准、三种应答、分页/缺口、事务回滚、重启/unknown、撤权及微信扫码/收发协议。跨进程测试使用真实 Java jar、主线 Python API、源仓 ctx 和两个独立 SQLite；模型与平台为替身，不能代替真实微信账号验收。

2026-10-21 验证：Maven package 和 15 项 Java 测试通过，跨进程联通与 Python 显式应答保护共 4 项通过。选定主线回归 134 项通过、6 项失败；失败均为 `test_session_memory_lifecycle.py` 中替身缺少 `initialize`，干净 master（`0417c60`）配原版 ctx 同样复现。验证使用的 ctx 为源仓 `feat/multimodal@458a17f`，来源和 wheel 摘要记录在外壳仓库的 `vendor/ctx-weft-source.json`。

2026-10-22 Adapter 抽取：Maven package 和 19 项 Java 测试通过；增加仅在测试中注册的新平台，验证自动发现、状态/鉴权/启停、回调验证和统一收发，以及未知/重复类型与无效微信配置拒绝。原文本/人工应答跨进程及 Python 保护共 4 项继续通过。

部分 Windows JDK 在 8.3 TEMP 路径下无法建立内部 Unix socket。可为测试指定一个已存在的正常临时目录：`mvn "-DargLine=-Djdk.net.unixdomain.tmpdir=D:/temp -Djava.io.tmpdir=D:/temp" test`；跨进程测试使用 `NLC_TEST_JAVA_TMPDIR`，手动运行 jar 时使用相同 JVM 参数。无需改应用代码或选择器实现。
