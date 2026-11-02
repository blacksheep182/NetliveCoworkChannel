package com.netlivecowork.channel.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 进程装配冒烟测试：上下文可启动、管理端鉴权生效、Adapter 目录可读。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChannelApplicationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path dataFile = Files.createTempDirectory("channel-app-test").resolve("channel.db");
        registry.add("channel.data-file", dataFile::toString);
        registry.add("channel.admin-token", () -> "test-admin-token");
        registry.add("channel.l3.sink-token", () -> "test-sink-token");
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthIsOpenAndManagementRequiresAdminToken() {
        assertEquals(HttpStatus.OK, rest.getForEntity("/health", String.class).getStatusCode());

        ResponseEntity<String> unauthorized = rest.getForEntity("/api/v1/channel/adapters", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, unauthorized.getStatusCode());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-admin-token");
        ResponseEntity<String> authorized = rest.exchange("/api/v1/channel/adapters", HttpMethod.GET,
            new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, authorized.getStatusCode());
        assertTrue(authorized.getBody().contains("weixin"));
        assertTrue(authorized.getBody().contains("web"));
        assertTrue(authorized.getBody().contains("fake"));
    }

    @Test
    void deploymentTokenCannotImpersonateAResourceOwner() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-admin-token");
        ResponseEntity<String> response = rest.exchange("/api/v1/channel/grants/unknown", HttpMethod.GET,
            new HttpEntity<>(headers), String.class);
        assertTrue(response.getBody().contains("UNSUPPORTED_CAPABILITY"));
    }
}
