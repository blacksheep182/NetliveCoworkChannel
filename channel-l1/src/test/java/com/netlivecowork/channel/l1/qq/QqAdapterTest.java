package com.netlivecowork.channel.l1.qq;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.AdapterConfig;
import com.netlivecowork.channel.contract.adapter.AdapterInstanceConfig;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.l1.support.TestHost;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqAdapterTest {
    @TempDir Path root;

    @Test void officialTokenGatewayAndPassiveGroupReplyUseMatchingAccountAndMessageId() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        var tokens=new AtomicInteger();var sendBody=new AtomicReference<JsonNode>();
        var auth=new AtomicReference<String>();
        server.createContext("/token",x->{
            tokens.incrementAndGet();
            assertEquals("app-1",Json.mapper().readTree(x.getRequestBody()).path("appId").asText());
            reply(x,200,"{\"access_token\":\"token-1\",\"expires_in\":7200}");
        });
        server.createContext("/gateway",x->{
            auth.set(x.getRequestHeaders().getFirst("Authorization"));
            reply(x,200,"{\"url\":\"wss://api.sgroup.qq.com/websocket\"}");
        });
        server.createContext("/v2/groups/group-1/messages",x->{
            auth.set(x.getRequestHeaders().getFirst("Authorization"));
            sendBody.set(Json.mapper().readTree(x.getRequestBody()));
            reply(x,200,"{\"id\":\"out-1\"}");
        });
        server.start();
        try {
            var secret=root.resolve("qq.json");Files.writeString(secret,"{\"clientSecret\":\"secret-1\"}");
            var protocol=new QqProtocol(HttpClient.newHttpClient(),"app-1",secret,
                URI.create(base),URI.create(base+"/token"));
            assertEquals("wss://api.sgroup.qq.com/websocket",protocol.gateway().toString());
            assertEquals("out-1",protocol.send(true,"group-1","in-1",2,"hello",Duration.ofSeconds(5)));
            assertEquals(1,tokens.get());assertEquals("QQBot token-1",auth.get());
            assertEquals("in-1",sendBody.get().path("msg_id").asText());
            assertEquals(2,sendBody.get().path("msg_seq").asInt());
            assertEquals(0,sendBody.get().path("msg_type").asInt());
        } finally {server.stop(0);}
    }

    @Test void groupFixtureCarriesGroupScopedMemberAndBindingCommand() throws Exception {
        var host=new TestHost("qq-main");
        var settings=Json.obj().put("appId","app-1").put("secretFile",root.resolve("secret.json").toString());
        var config=new AdapterInstanceConfig("tenant","workspace","qq-main",1,1,1,"app-1",settings,List.of());
        var instance=new QqAdapterFactory().create(config,host.host());
        instance.activate().toCompletableFuture().join();
        instance.inbound("GROUP_AT_MESSAGE_CREATE",Json.read("""
            {"id":"msg-1","content":" /sessions","group_openid":"group-1",
             "author":{"member_openid":"member-1"}}
            """));
        VerifiedIngress verified=host.resolved.getFirst();
        assertEquals("qq-group",verified.externalScope().namespace());
        assertEquals("group-1",verified.externalScope().key());
        assertEquals("app-1:group-1:member-1",verified.externalSubject().key());
        assertEquals(VerifiedIngress.Audience.SHARED,verified.externalReplyTarget().audience());
        assertEquals("group-1",verified.externalReplyTarget().protocolData().path("conversation").asText());
        assertEquals(1,host.submitted.size());
        instance.inbound("GROUP_AT_MESSAGE_CREATE",Json.read("""
            {"id":"msg-2","content":" /bind abc123","group_openid":"group-1",
             "author":{"member_openid":"member-1"}}
            """));
        assertEquals(1,host.ownerBindings.size());
        assertEquals("abc123",host.ownerBindingTokens.getFirst());
        assertEquals("qq-group",host.ownerBindings.getFirst().externalScope().namespace());
        instance.deactivate(com.netlivecowork.channel.contract.adapter.StopReason.SHUTDOWN);
    }

    @Test void configurationRejectsSecretInSettingsAndNonLoopbackHttp() {
        var factory=new QqAdapterFactory();
        var settings=Json.obj().put("appId","app-1").put("secretFile","secret.json")
            .put("clientSecret","exposed").put("apiBaseUrl","http://example.com");
        var result=factory.validateConfig(new AdapterConfig(1,settings,List.of()));
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(i->"SECRET_IN_SETTINGS".equals(i.code())));
        assertTrue(result.issues().stream().anyMatch(i->"INSECURE_ENDPOINT".equals(i.code())));
    }

    @Test void longChineseReplyIsSplitWithoutLosingCodePointsOrExceedingQqLimit() {
        String text="完成".repeat(800);
        List<String> parts=QqAdapterInstance.chunks(text);
        assertEquals(text,String.join("",parts));
        assertTrue(parts.stream().allMatch(p->p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=1900));
        assertThrows(com.netlivecowork.channel.contract.ChannelContractException.class,
            ()->QqAdapterInstance.chunks("完成".repeat(2000)));
    }

    private static void reply(com.sun.net.httpserver.HttpExchange x,int status,String json) throws java.io.IOException {
        byte[] bytes=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        x.sendResponseHeaders(status,bytes.length);
        try(var out=x.getResponseBody()){out.write(bytes);}
    }
}
