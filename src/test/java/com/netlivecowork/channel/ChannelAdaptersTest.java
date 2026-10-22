package com.netlivecowork.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.netlivecowork.channel.ChannelConfig.*;

@SpringBootTest(classes={ChannelApplication.class,ChannelAdaptersTest.PlatformConfig.class},properties={
    "channel.admin-token=adapter-test-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "channel.connections[0].id=sample-main", "channel.connections[0].type=sample",
    "channel.poll-ms=600000"})
@AutoConfigureMockMvc
@DirtiesContext
class ChannelAdaptersTest {
    @TempDir static Path root;
    static final String ADMIN="Bearer adapter-test-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
    static final String BASE="/api/v1/channels/connections/sample-main";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("channel.data-file",()->root.resolve("channel.db").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ChannelService service;
    @Autowired ChannelConfig config;
    @Autowired ChannelStore store;
    @Autowired SampleAdapter sample;

    @Test void new_adapter_bean_handles_startup_status_and_auth_without_core_changes() throws Exception {
        mvc.perform(get("/api/v1/channels/connections")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/channels/connections").header("Authorization",ADMIN))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].adapter_type").value("sample"))
            .andExpect(jsonPath("$[0].status").value("connected"));
        mvc.perform(post(BASE+"/auth")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE+"/auth").header("Authorization",ADMIN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.challenge").value("sample-challenge"));
        mvc.perform(get(BASE+"/auth").header("Authorization",ADMIN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("sample-auth"));
    }
    @Test void platform_callback_verifies_before_admission_and_shared_outbox_uses_its_adapter() throws Exception {
        int before=store.all("inbox").size();
        mvc.perform(post(BASE+"/webhook").content("hello")).andExpect(status().isBadRequest());
        assertThat(store.all("inbox")).hasSize(before);
        // This test-only adapter stands in for a platform's signature-verification implementation.
        mvc.perform(post(BASE+"/webhook").header("X-Test-Signature","verified-test-request").content("/list"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        service.tick();
        assertThat(sample.sent).anySatisfy(message->assertThat(message).contains("sample-main|chat|reply-context|").contains("尚未授权"));
        assertThat(store.all("outbox")).anySatisfy(row->assertThat(row.path("state").asText()).isEqualTo("sent"));
        mvc.perform(post(BASE+"/test/inbound").header("X-Test-Signature","verified-test-request").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
    }
    @Test void unknown_duplicate_and_invalid_adapter_configuration_fail_before_start() {
        var unsupported=new ChannelConfig(config.adminToken(),config.dataFile(),List.of(),
            List.of(new Connection("other","missing",null,null)),List.of());
        assertThatThrownBy(()->new ChannelAdapters(unsupported,List.of(new FakeAdapter())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported adapter");
        assertThatThrownBy(()->new ChannelAdapters(config,List.of(sample,sample)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        var invalidWeixin=new ChannelConfig(config.adminToken(),config.dataFile(),List.of(),
            List.of(new Connection("wx","weixin",null,null)),List.of());
        assertThatThrownBy(()->new ChannelAdapters(invalidWeixin,List.of(new WeixinClient(store,new ObjectMapper(),null))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("secret-file");
    }
    @Test void registry_stops_adapter_once_for_multiple_connections() {
        var fake=new FakeAdapter() { int stops; @Override public void stop() { stops++; } };
        var two=new ChannelConfig(config.adminToken(),config.dataFile(),List.of(),
            List.of(new Connection("one","fake",null,null),new Connection("two","fake",null,null)),List.of());
        var registry=new ChannelAdapters(two,List.of(fake));registry.start();registry.stop();
        assertThat(fake.stops).isEqualTo(1);
    }

    @TestConfiguration static class PlatformConfig {
        @Bean SampleAdapter sampleAdapter(ObjectProvider<ChannelService> service,ObjectMapper json) {
            return new SampleAdapter(service,json);
        }
    }
    static class SampleAdapter implements ChannelAdapter {
        private final ObjectProvider<ChannelService> service;
        private final ObjectMapper json;
        final List<String> sent=new ArrayList<>();
        boolean started;
        SampleAdapter(ObjectProvider<ChannelService> service,ObjectMapper json) { this.service=service;this.json=json; }
        @Override public String type() { return "sample"; }
        @Override public void start(Connection connection) { started=true; }
        @Override public String state(Connection connection) { return started ? "connected" : "stopped"; }
        @Override public JsonNode beginAuth(Connection connection) { return json.createObjectNode().put("challenge","sample-challenge"); }
        @Override public JsonNode authStatus(Connection connection) { return json.createObjectNode().put("status","sample-auth"); }
        @Override public Object webhook(Connection connection,HttpServletRequest request) {
            if (!"verified-test-request".equals(request.getHeader("X-Test-Signature"))) throw new IllegalArgumentException("Invalid platform signature");
            service.getObject().receive(new ChannelService.Inbound(connection.id(),"sample-event","user","chat","/list","reply-context"));
            return json.createObjectNode().put("accepted",true);
        }
        @Override public void send(Connection connection,String conversation,String context,String id,String text) {
            sent.add(connection.id()+"|"+conversation+"|"+context+"|"+id+"|"+text);
        }
    }
}
