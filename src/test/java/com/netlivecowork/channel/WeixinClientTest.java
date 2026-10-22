package com.netlivecowork.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static com.netlivecowork.channel.ChannelConfig.*;

class WeixinClientTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    HttpServer server;
    ChannelStore store;
    WeixinClient client;
    Connection connection;
    ChannelService service;
    final AtomicReference<JsonNode> updates=new AtomicReference<>();
    final AtomicReference<JsonNode> sent=new AtomicReference<>();
    final AtomicReference<String> observedCursor=new AtomicReference<>();

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/ilink/bot",exchange->{
            String path=exchange.getRequestURI().getPath();JsonNode response;
            if (path.endsWith("get_bot_qrcode")) {
                assertThat(exchange.getRequestURI().getQuery()).isEqualTo("bot_type=3");
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                assertThat(json.readTree(exchange.getRequestBody()).has("local_token_list")).isTrue();
                response=json.createObjectNode().put("ret",0).put("qrcode","challenge").put("qrcode_img_content","https://example.invalid/auth");
            } else if (path.endsWith("get_qrcode_status")) {
                response=json.createObjectNode().put("status","confirmed").put("bot_token","fake-platform-token").put("baseurl",base);
            } else {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fake-platform-token");
                assertThat(exchange.getRequestHeaders().getFirst("iLink-App-ClientVersion")).isEqualTo("65536");
                JsonNode body=json.readTree(exchange.getRequestBody());
                if (path.endsWith("getupdates")) { observedCursor.set(body.path("get_updates_buf").asText());response=updates.get(); }
                else { sent.set(body);response=json.createObjectNode().put("ret",0).put("message_id","out-1"); }
            }
            byte[] bytes=json.writeValueAsBytes(response);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        connection=new Connection("wx","weixin",URI.create(base),root.resolve("weixin.json"));
        var config=new ChannelConfig("x".repeat(32),root.resolve("channel.db"),List.of(),List.of(connection),List.of());
        store=new ChannelStore(config,json);service=mock(ChannelService.class);
        ObjectProvider<ChannelService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(service);
        client=new WeixinClient(store,json,provider);
        client.beginAuth(connection);client.authStatus(connection);
    }
    @AfterEach void cleanup() throws Exception { if(client!=null)client.stop();if(server!=null)server.stop(0);if(store!=null)store.close(); }
    @Test void qr_login_keeps_token_in_secret_file_and_text_send_uses_context() throws Exception {
        assertThat(json.readTree(connection.secretFile().toFile()).path("bot_token").asText()).isEqualTo("fake-platform-token");
        assertThat(client.authStatus(connection).has("bot_token")).isFalse();
        client.send(connection,"alice","reply-context","stable-key","你好");
        var message=sent.get().path("msg");
        assertThat(message.path("client_id").asText()).isEqualTo("stable-key");
        assertThat(message.path("context_token").asText()).isEqualTo("reply-context");
        assertThat(message.path("item_list").get(0).path("text_item").path("text").asText()).isEqualTo("你好");
    }
    @Test void inbound_is_saved_before_platform_cursor_advances() throws Exception {
        updates.set(json.readTree("{\"get_updates_buf\":\"next\",\"msgs\":[{\"message_id\":\"m1\",\"from_user_id\":\"alice\",\"context_token\":\"ctx\",\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"hello\"}}]}]}"));
        doAnswer(call->{ assertThat(store.get("platform-cursor","wx")).isNull();return null; }).when(service).receive(any());
        client.pollOnce(connection);
        verify(service).receive(new ChannelService.Inbound("wx","m1","alice","alice","hello","ctx"));
        assertThat(store.get("platform-cursor","wx").path("cursor").asText()).isEqualTo("next");
    }
    @Test void failed_persistence_and_rate_limit_do_not_ack_platform_cursor() throws Exception {
        updates.set(json.readTree("{\"get_updates_buf\":\"next\",\"msgs\":[{\"message_id\":\"m1\",\"from_user_id\":\"alice\"}]}"));
        doThrow(new IllegalStateException("disk full")).when(service).receive(any());
        assertThatThrownBy(()->client.pollOnce(connection)).isInstanceOf(IllegalStateException.class);
        assertThat(store.get("platform-cursor","wx")).isNull();
        updates.set(json.createObjectNode().put("ret",-14));assertThat(client.pollOnce(connection)).isEqualTo(3_600_000);
        assertThat(store.get("platform-cursor","wx")).isNull();
    }
}
