package com.netlivecowork.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;
import static com.netlivecowork.channel.ChannelConfig.Target;

/** Uses the same REST contract as the desktop; does not import or duplicate ctx-weft. */
@Component
public class BackendClient {
    private final ChannelConfig config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    public BackendClient(ChannelConfig config, ObjectMapper json) { this.config=config; this.json=json; }
    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    JsonNode call(Target target, String method, String path, JsonNode body) {
        var backend = config.backends().stream().filter(b -> b.id().equals(target.backendId())).findFirst().orElseThrow();
        String base = backend.url().toString().replaceAll("/+$", "");
        var request = HttpRequest.newBuilder(URI.create(base + "/api/v1" + path)).timeout(Duration.ofSeconds(30));
        if (backend.token() != null && !backend.token().isBlank()) request.header("Authorization", "Bearer " + backend.token());
        try {
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw new BackendError(response.statusCode());
            return json.readTree(response.body());
        } catch (BackendError e) { throw e; }
        catch (Exception e) { if (e instanceof InterruptedException) Thread.currentThread().interrupt(); throw new BackendError(0); }
    }
    public JsonNode session(Target t) { return call(t,"GET","/sessions/"+encode(t.sessionId()),null); }
    public JsonNode pending(Target t) { return call(t,"GET","/hitl/pending?session_id="+encode(t.sessionId()),null); }
    public void prepare(Target t) { call(t,"POST","/sessions/"+encode(t.sessionId())+"/prepare",json.createObjectNode()); }
    public JsonNode send(Target t, String text, JsonNode session) {
        var body=json.createObjectNode().put("content",text).put("require_explicit_hitl",true);
        // /messages 的 null 表示切回默认模型；保留目标会话的现有模型和接收 agent。
        for (String key : List.of("llm_account","llm_model")) body.set(key,session.path(key));
        if (!session.path("waiting_agent_id").asText("").isEmpty()) body.set("agent_id",session.get("waiting_agent_id"));
        return call(t,"POST","/sessions/"+encode(t.sessionId())+"/messages",body);
    }
    public JsonNode resolve(Target t,String id,String action,String text) {
        var body=json.createObjectNode().put(action.equals("answer") ? "answer" : "message",text);
        return call(t,"POST","/hitl/"+encode(id)+"/"+action+"?session_id="+encode(t.sessionId()),body);
    }
    public JsonNode resume(Target t) { return call(t,"POST","/sessions/"+encode(t.sessionId())+"/resume",json.createObjectNode()); }
    JsonNode page(Target t,String before) {
        return call(t,"GET","/sessions/"+encode(t.sessionId())+"/history?history_version=2&limit=200"+
                    (before==null ? "" : "&before="+encode(before)),null);
    }
    public String baseline(Target t) {
        var events=page(t,null).path("events");
        return events.isEmpty() ? "" : events.get(events.size()-1).path("event_id").asText();
    }
    public List<JsonNode> after(Target t,String cursor) {
        var pages=new ArrayList<List<JsonNode>>();
        String before=null;
        for (int count=0; count<50; count++) {
            var page=page(t,before); var selected=new ArrayList<JsonNode>();
            boolean found=cursor.isEmpty() && !page.path("has_more").asBoolean();
            for (JsonNode event : page.path("events")) {
                if (!cursor.isEmpty() && event.path("event_id").asText().equals(cursor)) { found=true; selected.clear(); }
                else selected.add(event);
            }
            pages.add(selected);
            if (found) {
                Collections.reverse(pages);
                return pages.stream().flatMap(List::stream).toList();
            }
            if (!page.path("has_more").asBoolean() || page.path("before").asText().isBlank()) break;
            before=page.path("before").asText();
        }
        throw new IllegalStateException("History cursor is unavailable; delivery paused");
    }
    public static class BackendError extends RuntimeException {
        public final int status;
        BackendError(int status) { super("Session backend unavailable or rejected request (HTTP " + status + ")"); this.status=status; }
    }
}
