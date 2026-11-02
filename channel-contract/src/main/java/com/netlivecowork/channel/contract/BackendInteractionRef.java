package com.netlivecowork.channel.contract;

/** 后端事项引用：终结事件使用，不携带提示内容。 */
public record BackendInteractionRef(String backendInteractionId, long revision) {
    public BackendInteractionRef {
        Producer.requireText(backendInteractionId, "backendInteractionId");
    }
}
