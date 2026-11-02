package com.netlivecowork.channel.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;

/**
 * 联调配置就绪：仓库根的 application-example.yml 必须能原样绑定到
 * ChannelProperties——后端身份、W3 主体映射与 sink 回调是真实后端联通的
 * 必填项（backend-http-v1 §3、v2.11 §2.6），示例缺字段会在联调现场才暴露。
 */
class ExampleConfigBindingTest {

    @Test
    void exampleFileBindsBackendIdentityPrincipalsAndSinkCallback() throws Exception {
        Path example = Path.of("..", "application-example.yml").toAbsolutePath().normalize();
        assertTrue(Files.exists(example), "application-example.yml must exist at the repo root");

        StandardEnvironment environment = new StandardEnvironment();
        for (var source : new org.springframework.boot.env.YamlPropertySourceLoader().load("example",
                new org.springframework.core.io.FileSystemResource(example))) {
            environment.getPropertySources().addFirst(source);
        }

        ChannelProperties properties = Binder.get(environment)
            .bind("channel", ChannelProperties.class).get();

        assertEquals(1, properties.services().size());
        ChannelProperties.Service service = properties.services().get(0);
        assertEquals("netlivecowork-desktop", service.backendId());
        assertEquals("channel-local", service.clientId());
        assertEquals("tenant-local", service.backendTenantId());
        assertEquals("workspace-local", service.backendWorkspaceId());
        assertEquals("w3-desktop-session", service.adapterId());
        assertEquals("env:NLC_CHANNEL_SERVICE_TOKEN", service.credentialRef());
        assertEquals(1, service.principals().size());
        ChannelProperties.Principal principal = service.principals().get(0);
        assertEquals("w3", principal.subjectIssuer());
        assertTrue(principal.subject().startsWith("REPLACE_WITH"));
        assertTrue(principal.sessionProofRef().startsWith("file:"));
        assertTrue(Path.of(principal.sessionProofRef().substring(5)).isAbsolute(),
            "example proof path must satisfy DeploymentCredentials");

        ChannelProperties.Sink sink = properties.sinks().get(0);
        assertEquals("channel-l3", sink.receiverId());
        assertEquals("http://127.0.0.1:17927/api/v1/l3/events", sink.callbackUrl());
        assertEquals("channel-callback", sink.callbackAuthBindingRef());
        assertEquals(0, sink.expectedRevision());
        assertEquals("sink-register-1", sink.requestKey());
    }

}
