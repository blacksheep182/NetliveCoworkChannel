package com.netlivecowork.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import static com.netlivecowork.channel.ChannelConfig.Connection;

/** Ports the existing iLink text/QR protocol. Tokens remain in protected secret files. */
@Component
public class WeixinClient implements ChannelAdapter {
    private final ChannelStore store;
    private final ObjectMapper json;
    private final ObjectProvider<ChannelService> service;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String,String> states=new ConcurrentHashMap<>(), challenges=new ConcurrentHashMap<>();
    private final List<Thread> workers=new ArrayList<>();
    private volatile boolean stopping;
    public WeixinClient(ChannelStore store,ObjectMapper json,ObjectProvider<ChannelService> service) {
        this.store=store;this.json=json;this.service=service;
    }
    @Override public String type() { return "weixin"; }
    @Override public void validate(Connection connection) {
        if (connection.secretFile()==null) throw new IllegalArgumentException("Weixin secret-file is required");
    }
    JsonNode secret(Connection c) {
        try { return Files.exists(c.secretFile()) ? json.readTree(c.secretFile().toFile()) : json.createObjectNode(); }
        catch (Exception e) { throw new IllegalStateException("Cannot read Weixin secret file"); }
    }
    private ObjectNode baseInfo() { return json.createObjectNode().put("channel_version","0x00000100").put("bot_agent","NetliveCowork"); }
    private JsonNode request(Connection c,String path,JsonNode body,boolean auth) {
        JsonNode secret=secret(c);
        String base=secret.path("baseurl").asText(c.url()==null ? "https://ilinkai.weixin.qq.com" : c.url().toString()).replaceAll("/+$","");
        URI uri=URI.create(base+path);
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && List.of("127.0.0.1","localhost").contains(uri.getHost())))
            throw new IllegalArgumentException("Weixin endpoint requires HTTPS");
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(70))
            .header("Content-Type","application/json").header("iLink-App-Id","bot").header("iLink-App-ClientVersion","65536")
            .header("X-WECHAT-UIN",Base64.getEncoder().encodeToString(Integer.toUnsignedString(java.util.concurrent.ThreadLocalRandom.current().nextInt()).getBytes(StandardCharsets.UTF_8)));
        if (auth) request.header("AuthorizationType","ilink_bot_token").header("Authorization","Bearer "+secret.path("bot_token").asText());
        else if (path.startsWith("/ilink/bot/get_bot_qrcode?")) request.header("AuthorizationType","ilink_bot_token");
        try {
            if (body==null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) throw new IllegalStateException("Weixin HTTP "+response.statusCode());
            return json.readTree(response.body());
        } catch (Exception e) { if (e instanceof InterruptedException) Thread.currentThread().interrupt(); throw new IllegalStateException("Weixin request failed"); }
    }
    @Override public JsonNode beginAuth(Connection c) {
        var result=request(c,"/ilink/bot/get_bot_qrcode?bot_type=3",json.createObjectNode().set("local_token_list",json.createArrayNode()),false);
        if (result.path("ret").asInt()!=0 || result.path("qrcode").asText().isBlank()) throw new IllegalStateException("Cannot create Weixin QR challenge");
        challenges.put(c.id(),result.path("qrcode").asText());
        return json.createObjectNode().put("qrcode_url",result.path("qrcode_img_content").asText()).put("expires_hint",120);
    }
    @Override public JsonNode authStatus(Connection c) {
        String challenge=challenges.get(c.id());
        if (challenge==null) return json.createObjectNode().put("status",state(c));
        var result=request(c,"/ilink/bot/get_qrcode_status?qrcode="+BackendClient.encode(challenge),null,false);
        String state=result.path("status").asText("wait");
        if (state.equals("confirmed")) {
            if (result.path("bot_token").asText().isBlank()) throw new IllegalStateException("Weixin confirmation has no credential");
            var saved=json.createObjectNode();
            for (String field:List.of("bot_token","ilink_bot_id","ilink_user_id","baseurl")) if (result.has(field)) saved.set(field,result.get(field));
            try {
                var path=c.secretFile().toAbsolutePath();Files.createDirectories(path.getParent());
                var temp=Files.createTempFile(path.getParent(),".weixin-",".tmp");
                if (Files.getFileStore(temp).supportsFileAttributeView("posix"))
                    Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                json.writeValue(temp.toFile(),saved);
                Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) { throw new IllegalStateException("Cannot save Weixin credential"); }
            challenges.remove(c.id());states.put(c.id(),"connected");
        }
        return json.createObjectNode().put("status",state);
    }
    @Override public String state(Connection c) {
        return states.getOrDefault(c.id(),secret(c).path("bot_token").asText().isBlank() ? "auth_required" : "connected");
    }
    /** Cursor advances only after every received message has been saved by ChannelService. */
    long pollOnce(Connection c) {
        var offset=store.get("platform-cursor",c.id());
        var body=json.createObjectNode().put("get_updates_buf",offset==null ? "" : offset.path("cursor").asText());body.set("base_info",baseInfo());
        var result=request(c,"/ilink/bot/getupdates",body,true);
        if (result.path("ret").asInt()==-14) { states.put(c.id(),"rate_limited");return 3_600_000; }
        if (result.path("ret").asInt()!=0) throw new IllegalStateException("Weixin getupdates rejected");
        for (JsonNode message:result.path("msgs")) {
            if (message.path("message_type").asInt()==2) continue;
            String text="";
            for (JsonNode item:message.path("item_list")) if (item.path("type").asInt()==1) text+=item.path("text_item").path("text").asText();
            String sender=message.path("from_user_id").asText();
            service.getObject().receive(new ChannelService.Inbound(c.id(),message.path("message_id").asText(),sender,sender,text,message.path("context_token").asText()));
        }
        if (result.has("get_updates_buf")) store.put("platform-cursor",c.id(),json.createObjectNode().put("cursor",result.path("get_updates_buf").asText()));
        states.put(c.id(),"connected");return 1000;
    }
    @Override public void send(Connection c,String conversation,String context,String id,String text) {
        var message=json.createObjectNode().put("to_user_id",conversation).put("client_id",id).put("message_type",2).put("message_state",2);
        if (!context.isBlank()) message.put("context_token",context);
        var item=json.createObjectNode().put("type",1);item.set("text_item",json.createObjectNode().put("text",text));
        message.set("item_list",json.createArrayNode().add(item));
        var body=json.createObjectNode();body.set("msg",message);body.set("base_info",baseInfo());
        var result=request(c,"/ilink/bot/sendmessage",body,true);
        if (result.path("ret").asInt()!=0) throw new IllegalStateException("Weixin send rejected (ret="+result.path("ret").asInt()+")");
    }
    @Override public void start(Connection c) {
        workers.add(Thread.startVirtualThread(()-> {
            long backoff=1000;
            while (!stopping && !Thread.currentThread().isInterrupted()) {
                long wait=1000;
                try {
                    if (secret(c).path("bot_token").asText().isBlank()) states.put(c.id(),"auth_required");
                    else wait=pollOnce(c);
                    backoff=1000;
                } catch (RuntimeException e) { states.put(c.id(),"reconnecting");wait=backoff;backoff=Math.min(60000,backoff*2); }
                try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }));
    }
    @Override public void stop() { stopping=true; workers.forEach(Thread::interrupt); }
}
