package com.netlivecowork.channel.l3;

/**
 * L3 运行参数。{@code sinkRef} 是本次提交选定的、已登记的事件接收端（l3-backend.md §3.2）：
 * 地址与认证留在 L3 登记，不接受聊天输入指定。路径模板由 {@link Endpoints} 覆盖，缺省值对应
 * 后端专用接入。
 */
public record L3Settings(boolean filesSupported, boolean sessionWatchSupported, long httpTimeoutMs,
                         int forwardMaxAttempts, long forwardInitialBackoffMs, long forwardMaxBackoffMs,
                         int forwardLeaseSeconds, String sinkRef, Endpoints endpoints) {

    /** 出站文件读取上限：超过即拒绝，不做静默截断（INV-38、46）。 */
    public static final long FILE_SIZE_LIMIT_BYTES = 32L * 1024 * 1024;

    public L3Settings {
        if (httpTimeoutMs <= 0) {
            throw new IllegalArgumentException("httpTimeoutMs must be > 0");
        }
        if (forwardMaxAttempts < 1) {
            throw new IllegalArgumentException("forwardMaxAttempts must be >= 1");
        }
        if (forwardInitialBackoffMs < 0 || forwardMaxBackoffMs < forwardInitialBackoffMs) {
            throw new IllegalArgumentException("forward backoff must satisfy 0 <= initial <= max");
        }
        if (forwardLeaseSeconds < 1) {
            throw new IllegalArgumentException("forwardLeaseSeconds must be >= 1");
        }
        sinkRef = sinkRef == null || sinkRef.isBlank() ? null : sinkRef;
        endpoints = endpoints == null ? Endpoints.defaults() : endpoints;
    }

    /** 与设计文档一致的 7 参构造：默认事件接收端由调用方另行配置。 */
    public L3Settings(boolean filesSupported, boolean sessionWatchSupported, long httpTimeoutMs,
                      int forwardMaxAttempts, long forwardInitialBackoffMs, long forwardMaxBackoffMs,
                      int forwardLeaseSeconds) {
        this(filesSupported, sessionWatchSupported, httpTimeoutMs, forwardMaxAttempts, forwardInitialBackoffMs,
            forwardMaxBackoffMs, forwardLeaseSeconds, null, Endpoints.defaults());
    }

    public L3Settings(boolean filesSupported, boolean sessionWatchSupported, long httpTimeoutMs,
                      int forwardMaxAttempts, long forwardInitialBackoffMs, long forwardMaxBackoffMs,
                      int forwardLeaseSeconds, String sinkRef) {
        this(filesSupported, sessionWatchSupported, httpTimeoutMs, forwardMaxAttempts, forwardInitialBackoffMs,
            forwardMaxBackoffMs, forwardLeaseSeconds, sinkRef, Endpoints.defaults());
    }

    /** 保守缺省：文件与会话观察能力默认关闭，回传 5 次、2s 起、5 分钟上限。 */
    public static L3Settings defaults() {
        return new L3Settings(false, false, 10_000L, 5, 2_000L, 300_000L, 30);
    }

    /**
     * 后端专用接入路径模板，占位符在请求时替换：{@code {sessionId}}、{@code {interactionId}}、
     * {@code {action}}、{@code {taskRef}}、{@code {fileId}}、{@code {watchRef}}。
     * 具体 URL 属后端协议事实，只在此集中维护（l3-backend.md §3.2、§4.6、§5.3）。
     */
    public record Endpoints(String submit, String watch, String watchRemove, String interaction,
                            String session, String taskCancel, String file) {

        public Endpoints {
            submit = requireText(submit, "submit");
            watch = requireText(watch, "watch");
            watchRemove = requireText(watchRemove, "watchRemove");
            interaction = requireText(interaction, "interaction");
            session = requireText(session, "session");
            taskCancel = requireText(taskCancel, "taskCancel");
            file = requireText(file, "file");
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("endpoint " + field + " must not be blank");
            }
            return value;
        }

        public static Endpoints defaults() {
            return new Endpoints(
                "/api/v1/channel/v1/sessions/{sessionId}/messages",
                "/api/v1/channel/v1/sessions/{sessionId}/watches",
                "/api/v1/channel/v1/sessions/{sessionId}/watches/{watchRef}",
                "/api/v1/hitl/{interactionId}/{action}",
                "/api/v1/sessions/{sessionId}",
                "/api/v1/channel/v1/sessions/{sessionId}/tasks/{taskRef}/cancel",
                "/api/v1/workspace/files/{fileId}");
        }
    }
}
