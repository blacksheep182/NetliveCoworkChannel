> 实现演进记录，部分早期评审状态已由后续版本覆盖。启动和当前集成方式以仓库根 README 为准。

# NetliveCowork Channel

Java 21 / Spring Boot 实现的 Channel 服务：把聊天平台、小程序与外部 Agent 的协议转换为统一请求，
受理、关联并把 Agent 的完整回复与业务事件可靠投递回固定目的地。

本仓库按 [`channel design v2`](../channel%20design%20v2/README.md) 的三层架构**破坏性重写**：
旧的单模块实现（`ChannelService` / `ChannelAdapter` / 旧 DTO）已全部删除，不保留兼容层。
设计与实现的对应关系、以及尚未实现的能力见本文第 8、9 节。

## 1 模块与依赖方向

```
channel-contract   公共数据契约与层间端口（packet / content / port / adapter SPI / JSON 编解码）
channel-infra      层共享基础设施：SQLite 事务、标识、摘要（无业务逻辑）
channel-l1         协议适配：weixin、web、fake 三个 Adapter（只依赖 contract）
channel-l2         公共应用：admission、去重、逻辑目标、订阅、outbox、事件规范化、连接调度（只依赖 contract）
channel-l3         后端访问：服务/目标登记、派发记录、后端 HTTP、可信事件接入与回传泵（只依赖 contract）
channel-app        装配：Spring Boot、管理端 API、L3 事件接收、网页入口
```

`l1` 不依赖 `l2/l3`，`l2` 不依赖 `l3` 实现，`l3` 不依赖 `l2` 实现——三者只经 `channel-contract`
里的端口协作（INV-47）。`channel-app` 是唯一同时依赖三层的模块，只做装配与协议入口。

### 1.1 解耦规则与检验

| 模块 | 允许依赖 | 禁止依赖 |
| --- | --- | --- |
| `channel-contract` | 仅 JDK/Jackson | 任何层与 `channel-infra` |
| `channel-infra` | 仅 JDK/SQLite/Jackson | 任何层与 `channel-contract` |
| `channel-l1` | `contract`、`infra` | `l2`、`l3`、`app` |
| `channel-l2` | `contract`、`infra` | `l1`、`l3`、`app` |
| `channel-l3` | `contract`、`infra` | `l1`、`l2`、`app` |
| `channel-app` | 全部 | —（只做装配，不含业务规则） |

规则由构建强制：每个模块的 `maven-enforcer-plugin`（`ban-layer-dependencies`）在 `validate`
阶段检查传递依赖，违规直接构建失败，而不是靠约定或代码评审事后发现。`channel-app` 的
`LayerBoundaryTest` 另外扫描 `src/main/java` 的 import 方向，防止绕过模块依赖图直连相邻层之外的类型；
以后新增能力（例如授权、成员事实、凭据导入）同样只能先加公共契约端口，再由对应层实现。
运行时还有三条约束：

1. **跨层只走端口**：L2 调用后端用 `BackendAccessPort`，L3 转交事件用 `ApplicationIntakePort`，
   L1 只用实例限定的 `AdapterHost` 设施；没有模块跨层 new 对方实现。
2. **跨层调用不进事务**：L2 的本地事务只包含 L2 表；`registerInteraction`/`registerFile` 这类
   L2 → L3 的登记在事务提交之后执行（端口是异步契约，未来 L3 变远程实现时不会把阻塞塞进事务）。
3. **不互读业务表**：L2 与 L3 共用同一 SQLite 文件（设计允许），但各自只访问自己的表；
   L2 的表以 `l2_` 前缀隔离，L3 使用独立表名，查询不跨模块。

## 2 快速开始

```powershell
mvn clean package
$env:CHANNEL_ADMIN_TOKEN = '<至少 32 字符的随机值>'
$env:CHANNEL_L3_SINK_TOKEN = '<后端回传令牌>'
java -jar channel-app/target/channel-app-2.0.0-SNAPSHOT.jar `
  --spring.config.additional-location=file:./application-local.yml
```

`application-example.yml` 是可直接改用的部署样例（复制为 `application-local.yml`，该文件已被 git 忽略）。
默认只监听 `127.0.0.1`，不暴露公网。

```powershell
$headers = @{ Authorization = "Bearer $env:CHANNEL_ADMIN_TOKEN" }
Invoke-RestMethod -Headers $headers http://127.0.0.1:17927/api/v1/channel/adapters
Invoke-RestMethod -Headers $headers http://127.0.0.1:17927/api/v1/channel/entries
```

## 3 配置

所有配置在 `channel.*` 下（见 `ChannelProperties`）。关键项：

| 配置 | 作用 |
| --- | --- |
| `admin-token` | 管理端 Bearer 密钥；未配置时管理 API 一律 401，不会放行 |
| `data-file` | SQLite 文件；L2 与 L3 共用同一文件，但只经各自 Repository 访问业务表 |
| `tenant-ref` / `workspace-ref` | 本部署的可信资源域；不接受客户端自报归属（INV-03） |
| `auto-provision-identities` | 未绑定外部身份时是否签发最小内部主体；生产建议 `false` 并先显式绑定 |
| `master-key-id` + `CHANNEL_MASTER_KEY` | 凭据保护：AES-256-GCM，主密钥只从环境变量读，库内只存密文与 keyId（INV-57） |
| `l3.sink-ref` / `l3.sink-token` | 后端事件回传接收端与令牌；未配置时后端事件一律拒绝 |
| `l3.files-supported` / `session-watch-supported` | 能力开关：未开启时 L3 明确返回 `UNSUPPORTED_CAPABILITY`，不假装支持 |
| `services[]` | 后端服务登记：`service-ref`、`base-url`、`access-type`（CLOUD/LOCAL/CONNECTOR）、`credential-ref` |
| `targets[]` | 逻辑目标登记：`target-ref` → 服务 + `session-id` + `resource-epoch` + 能力与可用性 |
| `sinks[]` | 回传接收端登记：`sink-ref` → 所属服务 + 令牌 |
| `entries[]` | 平台接入：`adapter-type`（`weixin`/`web`/`fake`）+ Adapter 私有 `settings` |

`resource-epoch` 区分后端资源重建与编号复用：数据重建后必须递增，否则旧目标会继续派发到新资源（INV-51）。

## 4 管理端 API

除 `/health` 外都需要 `Authorization: Bearer <admin-token>`。错误统一返回脱敏的
`{code, message, retryable}`，不返回堆栈、凭证或内部地址（INV-52）。

| 方法与路径 | 说明 |
| --- | --- |
| `GET /health` | 存活检查 |
| `GET /api/v1/channel/adapters` | 已装配 Adapter 的类型、版本与能力声明 |
| `GET /api/v1/channel/entries`、`GET /entries/{entryRef}` | 接入配置与运行观测（期望启用 / 就绪 / 在线 / 代次 / 重试） |
| `POST /api/v1/channel/entries` | 创建接入（公共字段 + Adapter 离线校验） |
| `POST /entries/{entryRef}/enabled?expectedRevision=` | 启停意图（不伪造 online） |
| `POST /entries/{entryRef}/reconnect` | 主动重连（仅主动模式） |
| `POST /entries/{entryRef}/test` | 连接测试（绑定凭证范围与超时，不提交业务任务） |
| `POST /entries/{entryRef}/validate` | 只做离线配置校验 |
| `POST /entries/{entryRef}/auth`、`GET /auth/{authRef}`、`POST /auth/{authRef}/cancel` | 交互式授权（微信扫码等） |
| `POST /entries/{entryRef}/bindings` | 绑定外部身份 → 内部 `callerRef`（INV-03） |
| `POST /entries/{entryRef}/destinations` | 登记目的地与平台私有 `protocolData`（INV-13、34） |
| `POST /entries/{entryRef}/test/inbound` | 仅 `fake` 入口：直接注入一条入站文本，用于联调 |
| `GET /entries/{entryRef}/protocol-state/{key}` | 只读协议状态（游标），实例/代次/版本限定 |
| `POST /entries/{entryRef}/account-rebind` | 账号换绑：递增账号代次（旧 admission/排队工作/身份映射失效），需 `expectedRevision` |
| `GET /entries/{entryRef}/inbound-dispositions` | 入站确定性拒绝的权威处置（最近 N 条为展示投影，跨代次保留） |
| `POST /deliveries/{deliveryId}/reconcile` | 人工核对 UNKNOWN 投递（`status: accepted\|failed`），不触发重发 |
| `GET /api/v1/channel/targets`、`POST /targets/sync` | L2 可见逻辑目标 / 从 L3 可信登记同步 |
| `GET /api/v1/channel/deliveries`、`GET /deliveries/{deliveryId}` | 投递状态、尝试次数、脱敏错误 |

后端 → L3 的事件入口是 `POST /api/v1/l3/events`，用 `X-Channel-Sink` + `X-Channel-Sink-Token`
（或 `Authorization: Bearer`）认证；成功返回 `202 {"event_id": "...", "status": "accepted"}`。
此确认只表示 L3 已持久接收并登记可恢复转交，不代表任务完成或平台已投递（INV-22、31）。

## 5 平台入口

### 5.1 微信（`weixin`，SDK_MANAGED）

按 iLink bot 协议长轮询：`getupdates` / `sendmessage` / `get_bot_qrcode` / `get_qrcode_status`。
凭证写在 `settings.secretFile` 的 JSON 里（`bot_token`、`ilink_bot_id`、`baseurl`），配置只存路径。

- 扫码授权：`POST /entries/weixin-main/auth` 返回二维码，`GET .../auth/{authRef}` 轮询确认。
- 游标只在 `IngressPort.submit` 持久受理成功后才推进；平台重投保持相同 `requestId`（INV-14）。
- 投递只支持文本：来源标注以 `【sourceLabel】` 前缀保留；非文本内容或带交互动作的投递返回
  `UNSUPPORTED_CONTENT`，不会静默丢内容后报告成功（INV-38、40）。
- 网络失败/超时 → `unknown`；401/403 → `AUTH_REQUIRED`；账号与登记不符 → `ACCOUNT_MISMATCH` 并阻断业务（INV-12）。

### 5.2 网页（`web`，PASSIVE）

`GET /web.html` 打开聊天页面，填 `entryRef` 与页面令牌后收发消息。发送使用
`POST /web/{entryRef}/chat/messages`，接收使用 `GET /web/{entryRef}/inbox?consumerKey=...&cursor=...`，
确认使用 `POST /web/{entryRef}/inbox/ack`；页面请求携带 Bearer，令牌不放 URL。
页面令牌来自 `settings.users`（或多用户文件）。v2.29 出站通过 L2 公共持久收件设施，
内容、deliveryId 去重、持久序号及接管凭据同事务提交后才 accepted，重启不会归零或淘汰为最近 200 条。

Web 完成提醒在其他会话时显示来源与“查看原会话”，点击后重新授权并走既有选择 CAS；到达本身不切换。
完整答案按 messageRef 合并，终态先到显示结果同步中。网页未维护持久本地正文缓存，因此仅发送 read 确认；
刷新后从服务端补读。具备可靠本地缓存的小程序/原生客户端可复用 received 确认端口。

`settings.inbox` 可配置 `retentionSeconds`（默认 2592000，范围 60～31536000）、`maxItems`
（每个收件箱正文默认 100000，范围 1～1000000）、`cursorTtlSeconds`（默认 1800，范围 60～86400）。
接管凭据保留为墓碑用于原键核对；正文到期显式报告 gap。正式入口要求文件数据库，容量/存储失败不返回假成功。
微信按 provider 真实回执交接，Web 按 durableInbox 提交交接；公共端口供后续其他 adapter 复用。

### 5.3 联调（`fake`）

记录所有投递并按文本接受，提供 `POST /api/v1/channel/entries/{entryRef}/test/inbound` 注入口。
仅用于联调与集成测试，不接真实平台。

### 5.4 文本命令（`commandAware` 输入）

普通文字直接作为输入提交；以 `/` 开头且能确定性解析时才当作命令，未知命令明确报错而非猜测：| 命令 | 效果 |
| --- | --- |
| `/list` | 列出当前可见的逻辑目标（`session.list`，由 L2 的已同步目标视图回答） |
| `/use <序号\|targetRef>` | 修改当前范围的绑定（`session.select`，CAS 版本比较） |
| `/status` | 查询当前绑定目标的会话信息（`session.get`） |
| `/cancel <taskRef>` | 提交取消意图（`task.cancel`，仅后端声明真实取消时开放） |
| `/approve <interactionRef>` / `/reject <interactionRef> [原因]` | 审批响应 |
| `/answer <interactionRef> <文本>` | 单题文本/选项兼容映射；多题必须结构化提交，不猜测拆题 |

> `session.list` 由 L2 用从 L3 同步的逻辑目标视图回答：`/use <序号>` 必须与用户刚看到的列表来自同一
> 排序来源，否则会把请求绑到另一个目标（INV-15）。逻辑目标本身仍由 L3 的可信登记签发，L2 只保存引用、
> 名称与可用性观测（INV-11）。单租户部署下可见性过滤退化为入口/成员权限。

## 6 后端（Agent 服务）对接契约

L3 只使用版本化的后端专用接口，不复用前端 SSE，也不把 Channel 公共包直接当后端请求
（INV-63）。默认路径集中在 `L3Settings.Endpoints`：

| 用途 | 默认路径 | 关键字段 |
| --- | --- | --- |
| 提交消息 | `POST /api/v1/channel/v1/sessions/{sessionId}/messages` | `request_key`、`agent_id`(可选)、`content`、`event_sink_ref` |
| 取消任务 | `POST /api/v1/channel/v1/sessions/{sessionId}/tasks/{taskRef}/cancel` | `request_key`、`task_ref`、`reason` |
| 人工交互 | `POST /api/v1/hitl/{interactionId}/{action}` | `approve`: `modify`；`reject`: `message`；`answer`: `answer` |
| 会话查询 | `GET /api/v1/sessions/{sessionId}` | — |
| 会话观察登记 | `POST /api/v1/channel/v1/sessions/{sessionId}/watches` | `request_key`、`event_sink_ref`、`event_types`、`resume_cursor` |
| 出站文件 | `GET /api/v1/workspace/files/{fileId}` | 上限 32 MiB，超限拒绝而不是截断 |

提交回执：`202` + `{"request_key": "...", "status": "accepted"}` 表示后端**已接管**（不是已执行、也不是完成）。
`4xx` 映射为确定性拒绝；超时/IO/`5xx` 保留 `OUTCOME_UNKNOWN`，L3 不重发、不换 ID、不换目标（INV-23）。

回传事件（后端 → `POST /api/v1/l3/events`）使用 `AgentEvent`：

```json
{
  "version": "1",
  "eventId": "evt-001",
  "type": "message.ready",
  "occurredAt": "2026-10-27T08:00:00Z",
  "resource": { "sessionId": "ses-1", "taskId": "bt-1" },
  "correlation": { "requestKey": "rk-..." },
  "order": { "scopeRef": "stream-1", "sequence": 42 },
  "payload": {
    "content": [{ "partId": "p1", "type": "text", "text": "月度分析已完成", "format": "plain" }],
    "backendMessageId": "bm-1"
  }
}
```

- `requestKey` 必须回传 L3 派发时固定的 `request_key`，L3 据此解析回原 `operationRef` 与业务链；
  后台/定时执行可以没有它，此时按稳定后端执行关联建立 flow，而不是“最近一次任务”（INV-30）。
- `eventId` 是后端稳定事件身份：重发保持相同 ID 与内容；同 ID 不同内容会被隔离核对，不覆盖（INV-32）。
- 支持类型：`message.ready`、`task.started/progress/waiting/completed/failed/cancelled/artifact`、
  `interaction.required/resolved/expired/cancelled`。未知版本、类型或字段一律拒绝。
- L3 持久接收并登记可恢复转交后才返回 `accepted`；转交 L2 失败按 2s→10s→30s→1min→5min 退避重试，
  超过上限进入 `paused` 并保留事件，不静默删除（INV-64）。

## 7 已实现的核心语义

- **受理与去重**：六元组去重键（tenant + workspace + entryRef + accountBindingEpoch + callerRef + requestId）；
  同键同参数幂等返回既有结果，同键异内容 `REQUEST_CONFLICT`（INV-14）。
- **派发阶段与结论分离**：`PENDING_DISPATCH`（已受理待派发）→ `DISPATCHING`（某 worker 已领取，
  **不代表后端受理**）→ `DISPATCHED/REJECTED/UNKNOWN`（后端确证）；`channel accepted` 只在受理事务提交后返回（INV-16、22）。
- **不可变规范包**：`CommandPacket` / `EventPacket` / `ReceiptPacket` 固定后不改写，重试不换 ID（INV-17）。
- **首次固定**：L2 受理时固定目标、配置版本、发起人与原回复目的地；L3 首次后端写入前固定实际服务、
  会话、`resourceEpoch` 与后端幂等键（INV-15、19）。
- **事件三段式**：`AgentEvent` → `VerifiedBackendEvent` → `EventPacket`，阶段间只保存稳定映射（INV-28）。
- **两段确认**：L3 先持久接收再确认后端；L2 持久接管（或保存待关联记录）后确认 L3（INV-31）。
- **可靠投递**：outbox 按 `tenant + workspace + entryRef + destinationRef + destinationRevision + packetId`
  合并；发送前复验目的地版本与匹配依据；`accepted/retry/unknown/paused/cancelled` 分级恢复（INV-35～39）。
- **版本化状态**：任务状态按后端 `revision` 更新，晚到旧状态只登记不投递（INV-27）。
- **人工交互**：事项与动作版本绑定、本地 CAS 只协调 Channel 并发、`applied` 只在后端给出本次生效依据时才表达（INV-59～62）。
- **连接隔离**：每实例独立凭证与协议状态；generation 单调持久化，旧回调拒绝；重连调度唯一（INV-48、49）。
- **凭据**：AES-256-GCM 加密，主密钥库外供应，缺失时拒绝写入而不是退回明文（INV-57）。

## 8 明确门控（已设计、当前不开放）

这些能力返回 `UNSUPPORTED_CAPABILITY` / `UNSUPPORTED_CONTENT`，不会静默降级：

| 能力 | 现状 |
| --- | --- |
| 入站文件（L1 准备 → L2 → L3 上传） | 端口与表已留，缺传输与完成确认链路；`files-supported=false` 时读取明确失败 |
| 消息引用 / `replyToMessageRef` | 契约与存储已具备，平台映射与后端 `message_id` 链路未启用，提交时明确拒绝 |
| 多题结构化答案的动态表单 | 单题文本/选项兼容映射可用；多题要求结构化提交，不做猜测拆题 |
| `task.get` | L3 无后端任务状态接口，返回 `UNSUPPORTED_CAPABILITY`，不伪造快照 |
| 真实 `task.cancel` | 仅当目标声明 `task.cancel` 能力时开放；现有后端 `interrupt` 是软暂停，不作为已取消 |
| 会话级持续观察（`session-watch-supported`） | L3 已实现登记/撤销与冲突检测，默认关闭；L2 目前直接拒绝会话级订阅（`UNSUPPORTED_CAPABILITY`），等“pending 意图 → 后端登记 → 激活”的两段协调接线后才开放，避免宣称未登记的订阅生效（INV-65） |
| 旧消息编辑、分片投递 | 未实现；文本超限不伪造分片，Adapter 声明 `maxTextLength=null` |

## 9 设计不变量 → 实现位置

| 不变量 | 主要落点 |
| --- | --- |
| INV-01、02、08 | `AdmissionService`（身份/范围）、`SubmitService`（目标与活动者校验） |
| INV-03、12、13 | `AdmissionService.verifyAccount`、`EntryRepository.recordObservedAccount`、目的地版本递增 |
| INV-14～18 | `SubmitService`（去重/受理事务/不可变包/默认回复关系） |
| INV-19、21、23、67 | `channel-l3 DispatchStore`（预登记尝试、固定关联、unknown 语义） |
| INV-20 | 所有仓储事务内无网络调用；派发/投递 worker 在事务外执行 |
| INV-22、25、26、27 | `EventIntakeService`（回执与事件分离、版本更新、`message.ready` 与 `task.completed` 分工） |
| INV-28～31、64 | `EventIngestService`、`ForwardPump`、`EventIntakeService` |
| INV-34～40 | `EventIntakeService.scheduleDeliveries`、`OutboxService`、`PlainTextRenderer` |
| INV-41～46 | `DirectoryRepository`（fileRef 登记）、`HostFacilities.fileAccess`、L3 `registerFile/readFile` |
| INV-47 | Maven 模块依赖方向（见第 1 节） |
| INV-48、49、53 | `ConnectionManager`（代次、退避、SDK_MANAGED 不叠加重连） |
| INV-51、65、66 | `TargetResolver`/`BackendRegistry`（固定的资源身份）、`EventIngestService`（sink 与观察登记） |
| INV-52、57 | `ChannelError` 脱敏、`SecretBox`、管理端错误映射 |
| INV-54～56、59～62 | `SubmitService`（范围与共享判断）、`RequestRepository`（事项 CAS、草稿/响应隔离） |

## 10 测试与验证

```powershell
mvn test        # 113 项，全部通过（contract 7、l1 33、l2 43、l3 27、app 3）
mvn package     # 产出 channel-app/target/channel-app-2.0.0-SNAPSHOT.jar
```

覆盖要点：

- `channel-contract`：公共包编解码对称性、未定义字段/未知类型拒绝、partId 唯一、回执因果校验。
- `channel-l1`：Adapter 注册冲突、能力声明与拒绝降级、微信协议桩（账号不符、扫码、submit/resolve 两处确定性拒绝
  分类、处置落盘失败不确认游标、已处置重投不再次执行、异内容隔离、超保留边界转缺口）。
- `channel-l2`：受理幂等与冲突、CAS 绑定、事件接管/规范化/固定目的地投递、交互审批一次生效、旧版本事件不投递；
  outbox 领取排他、崩溃后 unknown 不重发（含崩溃+停用再启用）、入口暂不可用先重试后暂停、目的地版本变化保留
  可见暂停记录、UNKNOWN 可核对为 accepted、身份换绑精确绑定隔离（S65～S68）、入站处置表去重/冲突/保留边界（S69～S72）。
- `channel-l3`：派发单次 HTTP + 幂等重放、预登记后超时 unknown、4xx 映射、事件去重与冲突隔离、回传泵重试与暂停、
  租约过期事件可被重新领取且旧持有者确认被拒、文件能力门控。
- `channel-app`：Spring 上下文启动与管理端鉴权；跨层贯通（入站 → 受理 → L3 HTTP 派发 → 事件回传 → 规范化 → 投递）。

测试使用临时文件 SQLite（内存共享缓存库在多连接下会触发表级锁，见第 11 节），后端用真实
`com.sun.net.httpserver` 桩，不 mock 网络层。

## 11 已知限制与后续工作

1. **Python 侧需补齐**：专用提交路由、`event_sink_ref` 登记与回传、`applied` 生效依据、任务状态与
   `message_id` 稳定身份、结构化整组答案、真实取消。当前码只按设计文档的字段实现，未对接真实 Python 服务。
2. **`Db.memory()` 只适合单连接场景**：共享缓存内存库在最后连接关闭时销毁，多连接并发会触发表级锁；
   仓储与测试统一使用文件数据库。
3. **管理端还没有多管理员与权限分层**：只有一个部署级管理密钥；可委派范围、审计留待后续。
4. **消息引用、文件字节传输、旧消息编辑、分片投递**按第 8 节门控，实现顺序见设计文档 L3 §5.4。
5. **前端界面**只有联调网页（`/web.html`），没有独立管理后台。
6. **`paused`/`unknown` 处置**：`UNKNOWN` 可通过 `POST /deliveries/{id}/reconcile` 按平台依据核对为
   `accepted`（不重发），平台具备查询能力时自动核对；`paused` 仍只有查看入口，重新入队需显式操作（INV-36、39）。
7. **周期任务与网络调用同线程**：`tick()` 内的派发与投递会等待后端/平台调用完成（受各自超时约束），
   单实例下表现为吞吐上限；后续可拆成独立 worker 池（领取已按 `status + attempt_count` 排他）。
8. **成员/受众授权模型未实现**：没有成员表、群成员变更与逐人鉴权；共享投递只校验“范围是共享 + 目的地版本有效”，
   成员退出、受众变化后的暂停与复核（INV-04、INV-56）需要在接入成员事实后补齐。
9. **目标/任务缺少用户级授权**：`SubmitService.requireTarget` 只校验 tenant/workspace 归属，同一空间内的
   已绑定用户可以选择、提交、订阅他人的目标。修复需要引入目标所有者与显式授权关系（见第 12 节未完成项）。
10. **Web 持久交付已由 v2.29 补齐**：L2 公共 inbox 接管、原键去重与回执核对、客户端游标/确认和保留恢复见第 5.2 节及 `DurableInboxTest`；旧内存模式的历史 accepted 不回填为持久接管证据。
11. **Web 页面令牌仍在 settings 明文**：`settings.users` 会原样入库并由管理接口返回；微信凭据文件同理只受
    文件系统保护（Windows 上 POSIX 权限不生效）。统一走实例限定凭据设施是第 12 节的未完成项。

## 12 评审整改记录

外部评审两轮共 10 项（P1×7 / P2×3）。第一轮修复后评审复核找回 3 项（第 2、5、7 条），
第二轮已全部关闭并补齐复现测试。**未修项只有第 1、6、8 条**，不冒充已解决。

| # | 评审结论 | 状态 | 证据 / 说明 |
| --- | --- | --- | --- |
| 1 | 目标/任务缺用户级授权（P1） | **未修** | 见下方"授权模型落地口径"；当前 `requireTarget` 只校验空间归属 |
| 2 | 身份重绑后旧 scope/目的地/旧 admission/旧排队仍可用（P1） | **已修（五轮）** | 标识纳入 `callerRef + bindingRevision`；admission 与排队工作都记录**精确原绑定（subject kind/key）+ 原版本 + 账号代次**，按该绑定行比较，禁止 caller 聚合；账号代次变化使旧 admission 与旧排队工作失效；重绑在**单事务**内完成撤销与版本提升，受理事务内再复验绑定；并发重复绑定由条件 UPDATE 保证只产生一个版本；缺依据隔离为 `QUARANTINED` → `L2ReviewRegressionTest#concurrentIdenticalBindingKeepsOneRevision`、`#queuedWorkFromOldAccountEpochIsNotDispatched`、`#admissionFromOldAccountEpochIsRejected`、`#rebindingUpdatesBindingAndInvalidatesWorkAtomically`、`#siblingBindingRevisionDoesNotAffectThisAdmission`（S66）/`#rebindingOneBindingDoesNotRevokeTheOtherOnesDestinations`/`#rebindingOneBindingCannotBorrowTheOther`（S65）/`#staleQueuedBindingIsNotDispatched`（S67）/`#queuedWorkWithoutBindingEvidenceIsQuarantined`（S68） |
| 3 | 管理端登记目的地把版本重置为 1（P1） | **已修** | `registerDestination(..., expectedRevision)` 单调递增、版本不符 `BINDING_CONFLICT` → `L2DeliveryRecoveryTest#destinationVersionChangeKeepsAVisiblePausedDelivery` |
| 4 | L3 已确认事件在转交崩溃后永久卡住（P1） | **已修** | 领取范围包含租约过期的 `sending`，确认/失败带 owner 围栏 → `EventStoreLeaseTest` |
| 5 | outbox 崩溃 + 入口停用后可重复发送（P1） | **已修（三轮）** | "上次可能已发送"判定提到入口/重试判断之前；`UNKNOWN` 可经管理端或平台查询核对为 `ACCEPTED`，核对不重发 → `L2ReviewRegressionTest#crashThenEntryDisabledStillDoesNotResend`、`#unknownDeliveryCanBeReconciledAsAcceptedWithoutResending`（S55～S57） |
| 6 | Web 令牌明文入库并由管理接口返回（P1） | **未修** | 见下方凭据迁移口径 |
| 7 | 微信 resolve 阶段拒绝未分类、无持久记录、落盘失败仍确认（P1） | **已修（三轮）** | 新增 L1→L2 入站处置设施：按 entry+账号代次+源事件 ID 的权威处置表（默认保留 30 天，跨代次/版本）；落盘失败抛暂时失败、游标不前进；已处置重投直接复用原处置不执行；同键异内容隔离；超保留边界标 `GAP` 转人工 → `InboundDispositionStoreTest`、`WeixinAdapterTest#rejectionRecordFailureKeepsTheCursor`（S69）/`#replayOfDisposedMessageDoesNotReachL2`（S70）/`#conflictingReplayIsQuarantinedWithoutExecution`（S71）/`#expiredDispositionBecomesGapWithoutExecution`（S72） |
| 8 | Web 投递先 accepted 再存内存（P2） | **已修（v2.29）** | L2 公共收件事务、跨重启原键核对、分端确认、保留缺口；正式启用检查存储，见 DurableInboxTest / ChannelRuntimeE2ETest |
| 9 | 目的地版本变化时新事件被直接跳过（P2） | **已修** | 改为写入可见的 `PAUSED` 记录并带 `DESTINATION_STALE` 原因 → `L2DeliveryRecoveryTest#destinationVersionChangeKeepsAVisiblePausedDelivery` |
| 10 | 入口暂时不可用使投递永久 paused（P2） | **已修** | 可恢复不可用按 `deliveryBackoff` 重试，耗尽才 `PAUSED`；身份/版本类问题立即暂停 → `L2DeliveryRecoveryTest#disabledEntryRetriesBeforePausing` |

另：回归测试发现同一后端事件在"后台泵 + 周期 tick"并发规范化时可能生成两个规范包与两份投递（INV-31），
已改为单 worker 领取（`PENDING_RELATION → PACKETIZING` 条件 UPDATE，租约过期可恢复，且已规范化的事件
不会被二次规范化覆盖），回归见 `L2ReviewRegressionTest#replayedBackendEventProducesOnlyOneDelivery`、
`#packetizedEventIsNotOverwrittenByLateNormalization` 与端到端重放断言。

### 扩展审查（8 组）

| 场景 | 状态 | 修法 / 证据 |
| --- | --- | --- |
| 回执落盘后中断，重投不再投影 | **已修** | `l2_operation_receipts.applied` 标记；重投时若未投影则继续投影 → `#receiptPersistedButNotAppliedIsRecoveredOnRetry` |
| 晚到回执把已完成操作改回 DISPATCHED/UNKNOWN | **已修** | `updateInbox` 终态守卫（COMPLETED/REJECTED/QUARANTINED/LOCAL_DONE 不回退）→ `#lateReceiptDoesNotRegressCompletedOperation` |
| 任务终态被更高 revision 的中间态改写 | **已修** | `updateTaskStatus` 终态守卫 → `#taskTerminalStateDoesNotRegressOnHigherRevision` |
| 交互 required/terminal 事件忽略 revision 与终态 | **已修** | 版本分支 + 终态不复活 + 条件更新（详见下方新表） |
| L3 映射登记临时失败无持久重试 | **已修** | l2_backend_registrations 同事务意图 + 重试泵（详见下方新表） |
| 替换实例/旧宿主的代次栅栏 | **已修** | 每次激活 `bumpGeneration`；`HostFacilities` 捕获宿主代次，旧宿主的 ingress（admission）与协议状态（游标）调用返回 `AUTH_REQUIRED` |
| 待恢复的读查询走写端口 | **已修** | dispatchRow 按 isReadOnly() 分流到查询端口（详见下方新表） |
| 账号换绑后旧实例与旧投递仍可继续 | **已修** | 换绑后重启实例（新代次）+ 发送前校验目的地账号代次，旧代次积压 `PAUSED` → `#accountRebindPausesOldEpochDeliveries` |
| 自动重连/实例替换的尝试代次 | **已修（部分回归）** | 宿主代次与尝试代次分离，尝试代次持久单调（实例替换不重号）→ #replacementAttemptGenerationNeverCollides；旧回调级验收待补 |
| 已完成操作的响应记录被晚到回执改写 | **已修（部分回归）** | inbox 迁移成功才更新响应/事项；响应与事项级断言待补 |
| 任务终态之间互换（COMPLETED→FAILED） | **已修** | 终态一律不再迁移 → `#taskTerminalStateDoesNotRegressOnHigherRevision`（含 FAILED 尝试） |
| 冲突回执应用到另一操作 | **已修** | 同 `packetId` 但操作/命令/内容不一致视为冲突，整条拒绝 → `#conflictingReceiptIsRejectedForAnotherOperation` |
| L3 映射登记失败无持久待办 | **已修** | `l2_backend_registrations`：意图与规范化同事务落库，提交后泵调用 L3，退避重试、超限 FAILED → `RegistrationRepositoryTest`（泵级回归待补） |
| 待恢复读查询走写端口 | **已修** | dispatchRow 按 isReadOnly() 分流到查询端口，SESSION_GET/TASK_GET 均写端口 0 次、查询端口 1 次 → #pendingReadQueriesUseTheQueryPort |
| 交互 required/terminal 忽略版本与终态 | **已修** | 版本分支 + 终态不复活 + 条件更新 → #interactionGuardsRejectStaleAndTerminalUpdates |

### 已确认的授权与凭据落地口径（不再等待产品选择）

1. **目标所有者来源**：以后端可信资源事实、经 L3 验证登记为权威；配置只作为受控部署的初始登记输入，
   不得覆盖已确认的资源归属。缺少可信归属或授权依据时拒绝。`ownerPrincipalRef` 只是归属元数据，
   不单独构成授权；是否暴露到 `TargetSummary` 按接口需要决定。
2. **共享范围授权路径**：有效资源分享授权 + 可信当前成员身份 + 成员角色允许本次动作；
   成员不需要为每个目标重复建立个人 grant。取消、审批、管理分别授权，每次记录实际操作者并由后端复验。
3. **个人授权与群公开**：个人 grant 是另一条路径；"个人可读"不等于"可对全群披露"，群公开仍须独立授权。
4. **凭据迁移**（按保留规范）：显式、幂等导入。`usersFile` 只作为导入来源，不做长期明文存储；
   导入后配置只保留引用，管理端只返回用途名。微信凭据同样进入实例限定凭据设施，
   非 POSIX 平台的文件 ACL 只是过渡保护，不算完成迁移。
5. **Web 交付**：正式产品持久化消息与游标；内存模式只允许作为显式联调配置，生产默认关闭。

### 落地拆分（按依赖顺序）

1. `#1` 授权：L3 目标登记带可信所有者 → L2 内部视图保存归属 + 显式授权表 → `session.select` /
   `input.submit` / `session.get` / `task.*` / 订阅 / outbox 发送前统一校验 → 管理端授权与撤销接口；
   共享范围按上表路径，群公开单独授权；无依据时拒绝。
2. `#6` 凭据：`SecretAccess` 承接 Web 令牌与微信凭据；管理端响应脱敏；显式幂等导入工具/接口。
3. `#8` Web 交付已按 v2.29 公共持久收件实现；旧内存 accepted 不迁移为可靠接管，客户端使用带缺口语义的新 inbox API。



### v2.11 个人授权管理（审查修复）

管理接口保留在 contract；L2 实现规则与持久化；app 只通过 `GrantManagementPort` 调用。

| 范围 | 当前行为 |
| --- | --- |
| create / replace / revoke | 当前资源域与管理依据校验、不可复活过期/撤销规则；规则、历史版本和幂等快照同事务提交 |
| 幂等重放 | 返回原提交快照，重新检查当前读取资格；没有原快照的旧请求报告 unknown |
| 执行授权 | 缺少有效资源事实即拒绝；读取、选择、提交、任务读取/取消和交互响应分别校验；列表与 `/use` 使用同一可见目标集合 |
| 恢复与撤权 | 保存原始 Grant / revision / 资源代次 / 所有权代次 / 使用范围；派发和投递前复验并持久记录本次开始发送；新 Grant 不接管旧请求 |
| 权限及期限变化 | 历史收缩持续约束原请求；收回后重新授予不复活原请求；延期不延长原请求的期限 |
| 旧数据 | 未保存原授权依据的队列不能自动获取新授权；不从旧 owner 字符串推断可信管理权 |
| 管理 HTTP 入口 | 资源主体认证尚未接线，部署级 admin token 不能充当资源 owner；Grant 路由明确返回 UNSUPPORTED_CAPABILITY |
| L3 资源证据 | 静态 TargetRegistration / legacy TargetOwnership 没有可验证有效期，不再被提升为已验证资源事实；等待真实后端证据适配 |
| 委派与共享 | L2 必须有明确可委派动作及期限；未接可信证据/委派登记时默认拒绝。共享执行/投递、缺少持久观察授权的任务订阅保持门控 |

`GrantManagementTest` 与 `GrantReviewProbeTest` 覆盖事务故障、并发重放、资源代次、域隔离、旧结果读取、过期替换、撤权后重试、权限恢复和延期边界。测试夹具显式提供可信事实与委派范围；配置字符串不是生产证据。

这次修复关闭了已发现的绕过路径，不代表 S101～S116 全量能力已实现。真实主体认证、后端资源证据与本地委派策略的可信登记入口、完整观察/披露授权及 L3 关闭未发送操作的协调仍需后续接线。


### Web 页面会话选择与回归

连接后左侧显示当前用户已授权的会话；点击一条会话后输入文本，Enter 发送。会话列表、状态查询和点击选择通过页面控件操作；输入框里的 /list 等文字按普通消息发送。服务、身份及配置过程见 `../channel design v2/web-session-configuration.md`；授权入口在 CoWork 设置菜单内。

Java 检查：`mvn test`。页面回归：安装或使用现有 Playwright 后，设置 `PLAYWRIGHT_MODULE` 为其模块路径（默认解析 playwright），运行 `node --test channel-app/src/test/web/web.test.cjs`；测试使用本机 Edge。设计与状态规范在 DESIGN.md；premium-ui.json 记录静态审计范围。


## v2.20 正常 Web 聊天前端与状态同步

用户在 CoWork“设置 → Channel 接入”管理授权。Web 页面左侧只展示获准的真实会话，点击后直接对话；连接凭证收在设置弹窗。
Web adapter 把用户文本转换为 literal input.submit，状态查询和消息均固定显式 targetRef，延迟回复按原会话展示。L3 透传后端执行状态/版本/观测时间，页面定期同步；等待或未知状态不会冒充可发送。
配置与权限见 [用户授权配置](../channel%20design%20v2/user-session-access.md)；交互与 adapter 绑定见 [Web 聊天前端](../channel%20design%20v2/web-chat-frontend.md)；状态与 409 语义见 [会话状态同步](../channel%20design%20v2/session-state-synchronization.md)。

## v2.22 Adapter 历史查询

Web / 小程序声明 FULL，可通过 `host.ingress().queryHistory(admissionRef, new SessionHistory.Request(targetRef, PageRequest.of(cursor, 20)))` 分页读取正文。微信等聊天软件及旧 adapter 默认 SUMMARY，仅保留原有会话摘要查询。实例 settings.historyAccess 可设 none/summary/full，只能收缩工厂能力；接口能力不替代用户的 target.read/history.read 与个人授权范围。

Web 点击会话后加载历史，“加载更早消息”继续分页。对外 facade 为 POST `/web/{entryRef}/sessions/history`，Bearer 认证，body 含 targetRef、cursor?、limit?。物理后端身份与游标不向页面开放。Java 207、Python 54、Edge 21 项回归通过，真实现有会话历史及撤权已验收；查询没有增加执行或投递。

接口、安全与分页见 [历史设计](../channel%20design%20v2/session-history.md)，证据与范围见 [历史验收](../channel%20design%20v2/integration-history-2026-10-31.md)。
