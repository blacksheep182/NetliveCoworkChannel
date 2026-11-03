# QQ 群 Adapter（首版）

## 接入与配置

使用 [QQ 开放平台](https://q.qq.com/)创建官方机器人，取得 AppID 和 AppSecret。入口类型为 `qq`；`settings.appId` 保存 AppID，`settings.secretFile` 指向仅含 `{"clientSecret":"..."}` 的本机 JSON 文件。AppSecret 不进入配置接口、数据库、日志或桌面端。默认使用腾讯正式环境 `https://api.sgroup.qq.com` 和 `https://bots.qq.com/app/getAppAccessToken`；沙箱可把 `settings.apiBaseUrl` 设置为 `https://sandbox.api.sgroup.qq.com`。仅本机协议夹具允许 HTTP 端点。示例见 `application-example.yml`。

在 QQ 开放平台配置机器人使用范围后，由有权添加机器人的群主或管理员从机器人资料卡把机器人加入目标群。首版订阅 `GROUP_AND_C2C_EVENT`，处理 `GROUP_AT_MESSAGE_CREATE`、平台已授权时的 `GROUP_MESSAGE_CREATE`，以及用于绑定的 `C2C_MESSAGE_CREATE`。通常要在群里 **@机器人** 才会收到消息。Adapter 通过出站 WebSocket 连接，无需公网回调；保存网关 `session_id` 与已处理序号，断线优先 RESUME，失效后重新 IDENTIFY。QQ 账号按 AppID 隔离。

## 群授权与审批

群首次向机器人发送消息后，L2 从可信网关事件记录 `qq-group` 群范围和回群目标。持有人在 CoWork 的 Channel 配置页选择该群，开放可创建工作空间。仅该群内新建的会话进入群目录；原有个人会话不共享。群成员可发起任务、恢复本群中断会话和回答问答选项。批准或拒绝工具调用只允许授权持有人。

QQ 的 `member_openid` 按群变化，且群事件不提供可用于与单聊稳定对应的通用身份。因此持有人在**每个群**分别绑定：本机 W3 登录后发起该群的五分钟挑战；由持有人在目标群 @机器人发送 `/bind <挑战码>`；服务端把挑战的 W3 主体、机器人 AppID、账号代次、群范围及实际 QQ 成员身份固定为持久绑定。其他群的绑定不能用于本群审批。撤销绑定或群授权会阻断之后的审批和群投递。普通群成员无需 QQ/W3 绑定。

## 消息与平台边界

入站消息用 AppID 和 QQ 消息 ID 确定请求键；重复投递通过原请求结果与处置记录去重。Adapter 只在 L2 已受理或确定性拒绝落盘后推进网关序号。群回复使用最新群消息的 `msg_id` 与单调 `msg_seq`，同一消息最多五次。依据 [腾讯群消息发送文档](https://github.com/tencent-connect/bot-docs/blob/main/docs/develop/api-v2/server-inter/message/send-receive/send.md)，群被动回复窗口是五分钟，主动推送能力已停止。超时或超出回复次数的异步结果明确记为投递失败，无法保证在 QQ 群呈现；用户可再次 @机器人刷新回复窗口并查询会话状态。文本是首版收发格式，附件及富媒体不声明支持。

本实现已通过协议 HTTP 夹具、群消息字段夹具、群绑定隔离、授权和客户端路径测试。尚未使用真实 QQ 机器人账号联调，平台环境权限、实际事件字段与群回复成功率需在机器人加入目标群后现场验收。
