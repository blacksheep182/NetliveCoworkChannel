package com.netlivecowork.channel.contract;

/** 规范指令类型。type 与 payload 可辨识联合一一对应（data-contracts.md §3、§4.2）。 */
public enum CommandType {
    SESSION_CREATE("session.create", CommandPayload.SessionCreate.class),
    INPUT_SUBMIT("input.submit", CommandPayload.InputSubmit.class),
    SELECTION_GET("selection.get", CommandPayload.SelectionGet.class),
    WORKSPACE_LIST("workspace.list", CommandPayload.WorkspaceList.class),
    WORKSPACE_SELECT("workspace.select", CommandPayload.WorkspaceSelect.class),
    SESSION_LIST("session.list", CommandPayload.SessionList.class),
    SESSION_SELECT("session.select", CommandPayload.SessionSelect.class),
    SESSION_GET("session.get", CommandPayload.SessionGet.class),
    SESSION_RESUME("session.resume", CommandPayload.SessionResume.class),
    TASK_GET("task.get", CommandPayload.TaskGet.class),
    TASK_CANCEL("task.cancel", CommandPayload.TaskCancel.class),
    INTERACTION_RESPOND("interaction.respond", CommandPayload.InteractionRespond.class),
    SUBSCRIPTION_CREATE("subscription.create", CommandPayload.SubscriptionCreate.class),
    SUBSCRIPTION_REMOVE("subscription.remove", CommandPayload.SubscriptionRemove.class);

    private final String wire;
    private final Class<? extends CommandPayload> payloadType;

    CommandType(String wire, Class<? extends CommandPayload> payloadType) {
        this.wire = wire;
        this.payloadType = payloadType;
    }

    public String wire() {
        return wire;
    }

    public Class<? extends CommandPayload> payloadType() {
        return payloadType;
    }

    /** 是否需要 L3 访问后端。session.list / session.select 与订阅管理由 L2 本地执行。 */
    public boolean requiresBackend() {
        return this != SELECTION_GET && this != WORKSPACE_LIST && this != WORKSPACE_SELECT && this != SESSION_LIST && this != SESSION_SELECT && this != SUBSCRIPTION_CREATE
            && this != SUBSCRIPTION_REMOVE;
    }

    /** 只读查询必须走查询端口，不能复用写提交（L3 会拒绝把读命令当写操作）。 */
    public boolean isReadOnly() {
        return this == SESSION_GET || this == TASK_GET;
    }

    public static CommandType parse(String wire) {
        for (CommandType type : values()) {
            if (type.wire.equals(wire)) {
                return type;
            }
        }
        throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "unknown command type: " + wire);
    }

    public static CommandType ofOperation(ChannelOperation operation) {
        return parse(operation.type());
    }
}
