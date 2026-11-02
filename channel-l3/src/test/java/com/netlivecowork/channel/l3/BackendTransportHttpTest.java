package com.netlivecowork.channel.l3;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A body-bearing request must remain HTTP/1.1 for FastAPI/Uvicorn interoperability. */
class BackendTransportHttpTest {
    @Test void postDoesNotAttemptH2cUpgradeAndPreservesJsonBody() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> upgrade=new AtomicReference<>();
        AtomicReference<String> received=new AtomicReference<>();
        server.createContext("/messages",exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body="{\"status\":\"accepted\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202,body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            BackendService service=new BackendService("service","tenant","workspace","HTTP fixture",
                "http://127.0.0.1:"+server.getAddress().getPort(),AccessType.LOCAL,null,true);
            PythonBackendClient client=new PythonBackendClient(L3Settings.defaults(),ServiceCredentialResolver.NOOP);
            String body="{\"content\":\"完整正文\",\"request_key\":\"request-1\"}";
            var reply=client.exchange(service,"POST","/messages",body,Map.of());
            assertEquals(202,reply.status());
            assertEquals(body,received.get());
            assertNull(upgrade.get(),"Uvicorn must not receive h2c upgrade framing with a JSON body");
        } finally { server.stop(0); }
    }
}
