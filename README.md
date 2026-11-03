# NetliveCowork Channel

独立的 Java Channel 服务，将 Web、微信和测试 Adapter 接入 CoWork 宿主。服务负责请求去重、授权后的逻辑目标选择、后端派发、事件回传和可靠收件。

相关仓库：

- [Python 宿主接入分支](https://github.com/blacksheep182/NetliveCoworkPy/tree/feat/channel-v2)
- [两个独立测试前端](https://github.com/blacksheep182/NetliveCoworkChannelTestClients)：客户端式 Chat 和命令式 Console

## 模块和通信

```text
Chat :17928 / Console :17929
        ↓ /web/*（各自的同源代理）
L1 Web / Weixin / Fake → L2 请求、授权、会话导航、outbox / inbox
                              ↓ BackendAccessPort
                         L3 后端访问、设备连接、事件接收
                              ↓ HTTP 或设备主动 WSS
                         Python 宿主 :17926 → 原 Runtime
                              ↑ 持久完整事件
                         L3 → L2 inbox → Adapter / 浏览器
```

| 目录 | 职责 |
| --- | --- |
| `channel-contract` | 公共 DTO、协议和层间端口 |
| `channel-infra` | SQLite、加密与共享基础设施 |
| `channel-l1` | Web、微信和 Fake 协议适配 |
| `channel-l2` | 授权、导航、请求账本、关联、可靠投递和持久 inbox |
| `channel-l3` | 宿主登记、认证派发、事件回传、设备连接 |
| `channel-app` | Spring Boot 装配和 HTTP/WebSocket 入口 |

Maven Enforcer 和边界测试约束模块依赖。L2/L3 通过公共端口协作，各自访问自己的业务表；当前在一个 Java 进程和一个 SQLite 文件内运行。Python 侧另有 `channel-backend.db` 保存接入账本；原会话、核心任务和原始事件仍由宿主原有存储负责。

## 1. 构建和启动服务

要求 JDK 21+、Maven 3.9+。默认只监听 `127.0.0.1:17927`。

```powershell
mvn clean package
Copy-Item application-example.yml application-local.yml
```

生成不同用途的随机密钥（以下命令在 PowerShell 执行）：

```powershell
function New-ChannelSecret {
  [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
}
$env:CHANNEL_ADMIN_TOKEN = New-ChannelSecret
$env:CHANNEL_MASTER_KEY = New-ChannelSecret
$env:NLC_CHANNEL_SERVICE_TOKEN = New-ChannelSecret
$env:CHANNEL_L3_SINK_TOKEN = New-ChannelSecret
```

修改 `application-local.yml` 中的 W3 UID、proof 文件绝对路径、真实会话 ID 和 Web 页面令牌。示例入口默认停用，联调时显式启用 `web-local`。密钥需要跨重启保持一致；`CHANNEL_MASTER_KEY` 是本服务 AES-256-GCM 主密钥，不能每次重启重新生成。

```powershell
java -jar channel-app/target/channel-app-2.0.0-SNAPSHOT.jar `
  --spring.config.additional-location=file:./application-local.yml
Invoke-RestMethod http://127.0.0.1:17927/health
$headers = @{ Authorization = "Bearer $env:CHANNEL_ADMIN_TOKEN" }
Invoke-RestMethod -Headers $headers http://127.0.0.1:17927/api/v1/channel/entries
```

数据库默认在 `data/channel.db`，本机配置、凭据和数据目录已忽略提交。Windows 若 JDK 临时目录不可写，先创建可写目录，再给 Java 加 `-Djava.io.tmpdir=<绝对路径>`；Unix-domain 临时目录问题可另加 `-Djdk.net.unixdomain.tmpdir=<绝对路径>`。

## 2. 和 Python 宿主通信

宿主使用 `feat/channel-v2` 分支，并沿用它原有的 CoWork 启动方式。在启动宿主的终端设置环境变量；Java 和 Python 位于不同终端时，须分别设置相同的对应值。

```powershell
$env:NLC_CHANNEL_SERVICE_TOKEN = '<与 Java 环境相同的服务 token>'
$env:NLC_MYTHOS_SESSION_PROOF_SECRET = '<32 字节以上，宿主原 W3 proof 签名密钥>'
$env:NLC_CHANNEL_BACKEND_ID = 'netlivecowork-desktop'
$env:NLC_CHANNEL_CLIENT_ID = 'channel-local'
$env:NLC_CHANNEL_TENANT_ID = 'tenant-local'
$env:NLC_CHANNEL_WORKSPACE_ID = 'workspace-local'
$env:NLC_CHANNEL_RECEIVER_ID = 'channel-l3'
$env:NLC_CHANNEL_RECEIVER_URL = 'http://127.0.0.1:17927/api/v1/l3/events'
$env:NLC_CHANNEL_RECEIVER_TOKEN = '<与 Java CHANNEL_L3_SINK_TOKEN 相同>'
$env:NLC_CHANNEL_RECEIVER_AUTH_REF = 'channel-callback'
$env:NLC_CHANNEL_CONTROL_URL = 'http://127.0.0.1:17927'
$env:NLC_CHANNEL_CONTROL_SERVICE_REF = 'svc-local'
```

在 Python 仓库生成用户级 proof：

```powershell
.venv/Scripts/python dev/channel_provision.py --uid '<W3 UID>' --out data/w3-session-proof.txt
```

将输出绝对路径填入 Java `services[].principals[].session-proof-ref`，并使 `subject`、`principal-ref` 与真实用户对应。该工具只签发联调 proof；仍须在 CoWork 完成真实 W3 登录、激活可信身份，并创建归属于该身份的会话。宿主未登记可信所有者的旧会话不会自动开放。

在 Java 配置中：

| 配置 | 与宿主对应 |
| --- | --- |
| `services[].base-url` | Python 回环 origin，默认 `http://127.0.0.1:17926` |
| `access-type: LOCAL` / `credential-ref: env:NLC_CHANNEL_SERVICE_TOKEN` | 同机受认证 HTTP |
| `backend-id` / `client-id` / `backend-tenant-id` / `backend-workspace-id` | 对应四个 `NLC_CHANNEL_*` 身份字段 |
| `adapter-id: w3-desktop-session` | 宿主支持的 W3 委托方式 |
| `targets[].session-id` / `resource-epoch` | 真实会话和资源代次，默认代次 1 |
| `sinks[].receiver-id` / `callback-url` / `callback-auth-binding-ref` | 对应 `NLC_CHANNEL_RECEIVER_*` |
| `sinks[].token` 与 `l3.sink-token` | 对应宿主 `NLC_CHANNEL_RECEIVER_TOKEN` |

Java L3 访问宿主 `/api/v1/channel/v1/*`：能力、会话事实、输入、原键结果查询、历史、人工交互和事件接收端登记。写操作带服务认证、用户委托、请求体摘要和资源代次。宿主调用既有 Runtime；完整事件先在宿主持久化，再回传到 Java `/api/v1/l3/events`，Java 持久接管后确认。超时或确认丢失核对原键，不重新执行。

密钥用途必须区分：管理员 token 管理 Java；服务 token 认证 Java→Python；sink token 认证 Python→Java；用户 proof 证明 W3 委托；Java master key 保护凭据。不要把宿主 proof 签名密钥填成用户 proof 文件内容。

## 3. 绑定 Web 用户和开放会话

示例 `web-local.settings.users.alice` 配置独立、足够长的随机页面令牌。启用入口后，把外部用户名绑定到对应 W3 主体：

```powershell
$body = @{ subjectKind='USER'; subjectKey='alice'; callerRef='user-<W3 UID>' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Headers $headers -ContentType application/json `
  -Uri http://127.0.0.1:17927/api/v1/channel/entries/web-local/bindings -Body $body
```

然后在 CoWork 设置 → Channel 接入中勾选并保存本人允许开放的会话。目标配置、身份绑定、设备配对本身都不等于用户授权。管理员 token 不能代替用户创建会话授权。

若通过管理 API 启用入口，先 GET `/api/v1/channel/entries/web-local` 读取当前 `revision`，再 POST `/api/v1/channel/entries/web-local/enabled?expectedRevision=<revision>`，JSON 为 `{"enabled":true}`。

## 4. 和两个测试前端交互

在独立前端仓库执行 `npm ci`，分别在两个终端运行：

```powershell
npm run chat
```

```powershell
npm run console
```

浏览器打开 `http://127.0.0.1:17928` 或 `http://127.0.0.1:17929`，填写入口 `web-local` 和 `alice` 的页面令牌。

- Chat：选择工作空间和已授权会话，加载历史、发送普通对话、处理人工交互。`/list` 在这里是普通聊天正文。
- Console：先 `/list`，再 `/use 1`，然后普通消息或 `/status`。列表序号只来自本次展示的授权列表。
  审批直接发送 `/approve` 或 `/reject`，拒绝无需理由；问题直接发送 `/answer 答案`（例如 `/answer 2` 选择第 2 个选项），系统关联当前选中会话的待处理项，无需填写内部编号。
  有多条待处理项时先显示内容列表，再使用 `/approve #1`、`/reject #1` 或 `/answer #1 答案` 选择；`#1` 必须来自该次显示的列表，问题版本变化或已处理后旧编号失效。

两个前端都是独立 Node HTTP 服务，分别代理 `/web/*` 到 Java。令牌保留在页面内存。Java 暂留 `/web.html` 内嵌兼容页面；独立前端仓库是后续维护入口。

主要 Web API：

| API | 用途 |
| --- | --- |
| `POST /web/{entry}/chat/messages` | Chat 普通文本，Bearer 认证 |
| `POST /web/{entry}/messages` | 命令感知输入，现有协议包含 body `token` |
| `POST /web/{entry}/workspaces/query`、`sessions/query`、`sessions/select` | 授权导航和明确选目标 |
| `POST /web/{entry}/sessions/get`、`sessions/history` | 实际状态和历史 |
| `POST /web/{entry}/interactions/respond` | 明确的人工交互回复 |
| `GET /web/{entry}/requests/{requestId}` | 核对原请求结果 |
| `GET /web/{entry}/inbox`、`POST /web/{entry}/inbox/ack` | 持久收件、read/received 确认和缺口恢复 |

浏览器客户端不持久缓存正文，只提交 read 确认。请求已受理、Runtime 已执行和答案已回传是不同状态；未知写结果只查询原 requestId。

## 5. Fake Adapter 联调

Fake 是进程内测试 Adapter，无需外部聊天平台。通过管理员 API 创建并启用入口：

```powershell
$body = @{ entryRef='fake-local'; adapterType='fake'; enabled=$true; settings=@{} } | ConvertTo-Json
Invoke-RestMethod -Method Post -Headers $headers -ContentType application/json `
  -Uri http://127.0.0.1:17927/api/v1/channel/entries -Body $body
```

先给 Fake 入口绑定测试外部用户名 `fake-user` 到同一个 W3 主体：POST `/api/v1/channel/entries/fake-local/bindings`，JSON `{"subjectKind":"USER","subjectKey":"fake-user","callerRef":"user-<W3 UID>"}`。该主体须已在 CoWork 开放会话。入口就绪后调用：

```powershell
$body = @{ requestId='fake-list-1'; callerRef='fake-user'; text='/list' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Headers $headers -ContentType application/json `
  -Uri http://127.0.0.1:17927/api/v1/channel/entries/fake-local/test/inbound -Body $body
```

依次以新 requestId 提交 `/use 1` 和普通文本，查询管理端投递观测确认状态。该入口复用 L1→L2→L3 请求流程，不直接调用 Runtime。真实微信 Adapter 则使用示例 `weixin-main`，通过 `/entries/{entry}/auth` 扫码及 `/auth/{authRef}` 查询；未做真实微信账号验收。

## 6. 无公网入口的桌面宿主

使用 [application-connector-example.yml](application-connector-example.yml)，`access-type: CONNECTOR` 配置 `device-ref`，由设备主动连接 WSS，无需向云端暴露本机 HTTP。

1. 宿主设置 `NLC_CHANNEL_CONNECTOR_ENABLED=1`、`NLC_CHANNEL_CONTROL_URL`、`NLC_CHANNEL_CONTROL_SERVICE_REF`、`NLC_CHANNEL_LOCAL_URL`，及前述身份和服务配置。
2. 管理员 POST `/api/v1/channel/devices/enrollments`，JSON `{requestId,serviceRef,deviceRef}`，把短期票据交给指定设备用户。
3. 用户真实 W3 登录后，在 CoWork 设置 → Channel 接入 → 本机设备连接中配对；Windows 使用 DPAPI 保存设备凭据。
4. 单独开放会话。断开只停止设备连接；管理员 POST `/api/v1/channel/devices/{deviceRef}/revoke` 才吊销设备。

L3 的查询、写入和事件确认都走同一连接。公网部署使用 HTTPS/WSS。当前连接器面向 Windows 地端和单 Channel 实例。

## 7. 验证和排障

```powershell
mvn test package
```

当前 Java 回归包含协议/模块边界、授权、去重、交互、收件恢复、设备连接等。独立前端执行 `npm run check`、`npm run build`、`npm test` 和 `npm run test:browser`。宿主分支 `tests/channel_http/README.md` 提供真实 Java/Python/Node 多进程回归，模型和身份使用隔离夹具。

| 现象 | 检查 |
| --- | --- |
| 管理 API 401 | `CHANNEL_ADMIN_TOKEN` 与 Bearer |
| capabilities 401/503 | 服务 token、宿主接入是否启用及身份配置 |
| facts/输入拒绝 | W3 登录、proof、资源 owner/epoch 和委托字段一致性 |
| `/list` 为空 | 外部身份绑定及 CoWork 用户会话授权 |
| `/use 1` 拒绝 | 先运行 `/list`；序号必须来自当前页面 |
| 已受理却无答案 | 原请求状态、宿主事件 outbox、sink token/URL、Java 投递观测 |
| 未知写结果 | 查询原 requestId，不生成新键重试 |

服务端会话事实和用户授权始终为准。文件、会话监听等能力按协商开关开放；示例默认关闭。原会话归属与 Channel owner 重复存储的设计问题已单独记录，当前保留隔离边界，后续另行讨论。

更细的演进及评审记录见 [docs/implementation-notes.md](docs/implementation-notes.md)；其中早期状态不作为当前部署步骤。


## 新建会话与工作空间创建授权

Channel 支持 L1 `session.create` 到 Python 宿主的完整链路。创建接口是 `POST /web/{entryRef}/sessions/create`，通过 Bearer 页面令牌认证，正文为 `requestId`、`viewId`、`sessionWorkspaceRef`、`expectedBindingRevision`、`userPrompt`（首条消息）。Console 可以使用 `/workspaces`、`/workspace N`、`/new 首条消息`；Chat 的普通斜杠文本仍按消息处理。

创建权由实际 Python 宿主配置：`NLC_CHANNEL_WORKSPACE_GRANTS_FILE` 指向私有 JSON，格式为 `{"version":1,"workspaces":[{"path":"D:/Projects/my-app","label":"我的项目","users":["actual-w3-uid"]}]}`。只允许精确 W3 用户在配置目录中创建；未配置时默认关闭。文件每次调用重读，空工作空间同样可开放。服务凭据、页面令牌、设备配对或工作空间引用本身都不能赋予创建权。

公开工作空间列表合并已授权会话的空间与可创建的空空间，并通过 `canCreate` 区分。L3 独占逻辑引用到服务/宿主工作空间 ID 的映射，页面不接受真实目录或自报身份。宿主复用已有创建服务及当前 Electron 登录令牌中的人员锚点。创建成功登记真实归属并开放新会话，首个任务通过已有事件/收件设施回传回复与完成状态。

L2 在网络调用前持久登记原请求；L3 固定原服务、工作空间和后端幂等键；宿主在启动前持久领取固定会话 ID。未知结果只查询原键，不能换键重发。后端拒绝开始执行前的模板/模型错误可以明确结束为拒绝。创建后选择采用 CAS，迟到结果不覆盖用户的新选择。撤销创建授权阻止未来创建；已有会话访问权仍独立管理。

完整配置与隔离 HTTP/连接器联调命令见 Python 宿主仓 `docs/channel-integration.md` 和 `tests/channel_http/channel_creation_e2e.py`。
