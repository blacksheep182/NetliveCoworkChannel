package com.netlivecowork.channel.l3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 真实本机 HTTP 后端（端口 0），不用 mock 框架。协议前奏（capabilities 与会话 facts）
 * 由桩内建应答且不计入业务调用记录；其余请求交给测试配置的 handler。
 */
final class TestBackend implements AutoCloseable {

    static final String PREFIX = "/api/v1/channel/v1";
    /** 与 TestFixtures.identities() 的登记一致；capabilities/facts 应答据此对齐。 */
    static final String BACKEND_ID = "backend-1";
    static final String ADAPTER_ID = "test-adapter";

    record Call(String method, String path, String query, String body) {
    }

    record Response(int status, String body, long delayMs) {
        static Response json(int status, String body) {
            return new Response(status, body, 0);
        }

        static Response status(int status) {
            return new Response(status, "{}", 0);
        }

        static Response delayed(int status, String body, long delayMs) {
            return new Response(status, body, delayMs);
        }
    }

    @FunctionalInterface
    interface Handler {
        Response handle(Call call) throws Exception;
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Handler handler;
    volatile boolean workspaces;
    volatile boolean creation;
    volatile boolean interactions;
    final List<String> workspaceHeaders=new CopyOnWriteArrayList<>();

    TestBackend(Handler handler) throws IOException {
        this.handler = handler;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/", this::dispatch);
        this.server.start();
    }

    void handler(Handler next) {
        this.handler = next;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Call> calls() {
        return calls;
    }

    int callCount() {
        return calls.size();
    }

    Call lastCall() {
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void dispatch(HttpExchange exchange) {
        try {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String method = exchange.getRequestMethod();
            workspaceHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Channel-Features")));
            String path = exchange.getRequestURI().getPath();
            String protocolPreamble = preamble(method, path, exchange);
            if (protocolPreamble != null) {
                respond(exchange, 200, protocolPreamble);
                return;
            }
            Call call = new Call(method, path, exchange.getRequestURI().getQuery(), body);
            calls.add(call);
            Response response;
            try {
                response = handler.handle(call);
            } catch (Exception e) {
                response = Response.status(500);
            }
            if (response == null) {
                response = Response.status(500);
            }
            if (response.delayMs() > 0) {
                try {
                    Thread.sleep(response.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(exchange, response.status(), response.body());
        } catch (IOException ignored) {
            // 客户端超时断开后写响应失败是预期情况。
        } finally {
            exchange.close();
        }
    }

    /** 协议前奏应答体；非前奏路径返回 null 交回业务 handler。不记录为业务调用。 */
    private String preamble(String method, String path, HttpExchange exchange) {
        if (!"GET".equals(method)) {
            return null;
        }
        if ((PREFIX + "/capabilities").equals(path)) {
            return """
                {"protocol_version":"1","profile":"personal-text-v1","backend_id":"%s",\
                "features":{"resource_facts":true,"delegated_calls":true,"session_read":true,\
                "message_submit":true,"request_lookup":true,"event_push":true,"event_replay":true,"session_catalog":true,"session_workspaces":%s,"interaction_response":%s,"session_create":%s},\
                "limits":{},"retention":{"request_min_seconds":60,"acked_event_min_seconds":60},\
                "auth":{"service":"bearer","delegation":"backend-adapter","adapter_id":"%s"}}
                """.formatted(BACKEND_ID, workspaces, interactions, creation, ADAPTER_ID);
        }
        if (path.startsWith(PREFIX + "/sessions/") && path.endsWith("/facts")) {
            String session = path.substring((PREFIX + "/sessions/").length(), path.length() - "/facts".length());
            String epoch = exchange.getRequestHeaders().getFirst("X-Expected-Resource-Epoch");
            Instant now = Instant.now();
            return """
                {"backend_id":"%s","tenant_id":"tenant-1","workspace_id":"ws-1","session_id":"%s",\
                "resource_epoch":%s,"owner":{"subject_issuer":"w3","subject":"user-1"},"revision":1,\
                "observed_at":"%s","valid_until":"%s",\
                "capabilities":["session.read","message.submit"],"delegation":{"status":"available"}}
                """.formatted(BACKEND_ID, session, epoch == null ? "1" : epoch, now, now.plusSeconds(30));
        }
        return null;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body == null || body.isBlank() ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
    }
}
