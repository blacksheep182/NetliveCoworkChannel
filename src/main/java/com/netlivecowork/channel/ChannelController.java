package com.netlivecowork.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@RestController
public class ChannelController {
    private final ChannelConfig config;
    private final ChannelService service;
    private final ChannelStore store;
    private final ChannelAdapters adapters;
    private final ObjectMapper json;
    public ChannelController(ChannelConfig config,ChannelService service,ChannelStore store,ChannelAdapters adapters,ObjectMapper json) {
        this.config=config;this.service=service;this.store=store;this.adapters=adapters;this.json=json;
    }
    @GetMapping("/health") public Object health() { return java.util.Map.of("service","channel","protocol_version",1); }
    @GetMapping("/api/v1/channels/connections") public Object connections() {
        return config.connections().stream().map(c->java.util.Map.of("id",c.id(),"adapter_type",c.type(),"status",adapters.get(c).state(c))).toList();
    }
    @PostMapping("/api/v1/channels/connections/{id}/auth") public JsonNode beginAuth(@PathVariable String id) {
        var connection=adapters.connection(id);return adapters.get(connection).beginAuth(connection);
    }
    @GetMapping("/api/v1/channels/connections/{id}/auth") public JsonNode auth(@PathVariable String id) {
        var connection=adapters.connection(id);return adapters.get(connection).authStatus(connection);
    }
    @RequestMapping(value="/api/v1/channels/connections/{id}/webhook",method={RequestMethod.GET,RequestMethod.POST})
    public Object webhook(@PathVariable String id,HttpServletRequest request) {
        var connection=adapters.connection(id);return adapters.get(connection).webhook(connection,request);
    }
    @PostMapping("/api/v1/channels/connections/{id}/test/inbound")
    public Object inbound(@PathVariable String id,@RequestBody ChannelService.Inbound input) {
        if (config.connections().stream().noneMatch(c->c.id().equals(id) && c.type().equals("fake"))) throw new IllegalArgumentException("Test input requires a fake connection");
        var row=service.receive(new ChannelService.Inbound(id,input.eventId(),input.externalUserId(),input.conversationId(),input.text(),input.contextToken()));
        return java.util.Map.of("id",row.path("id").asText(),"state",row.path("state").asText());
    }
    @GetMapping("/api/v1/channels/deliveries") public List<ObjectNode> deliveries() { return visible("outbox",List.of("id","state","text","error","target")); }
    @GetMapping("/api/v1/channels/inbox") public List<ObjectNode> inbox() {
        return store.all("inbox").stream().map(row->{
            var out=json.createObjectNode();
            for(String field:List.of("id","state","error","target")) if(row.has(field))out.set(field,row.get(field));
            for(String field:List.of("connectionId","externalUserId","conversationId")) out.set(field,row.path("source").path(field));
            return out;
        }).toList();
    }
    private List<ObjectNode> visible(String kind,List<String> fields) {
        return store.all(kind).stream().map(row->{ var out=json.createObjectNode();for (String field:fields) if (row.has(field)) out.set(field,row.get(field));return out; }).toList();
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object invalid(IllegalArgumentException e) { return java.util.Map.of("detail",e.getMessage()); }
    @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Object unavailable(IllegalStateException e) { return java.util.Map.of("detail",e.getMessage()); }

    @Configuration static class Authentication implements WebMvcConfigurer {
        private final ChannelConfig config;
        Authentication(ChannelConfig config) { this.config=config; }
        @Override public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) throws Exception {
                    // Only the dedicated platform callback delegates authentication to its adapter.
                    if (handler instanceof HandlerMethod method && method.getBeanType()==ChannelController.class
                        && method.getMethod().getName().equals("webhook")) return true;
                    String provided=req.getHeader("Authorization");
                    if (provided!=null && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),("Bearer "+config.adminToken()).getBytes(StandardCharsets.UTF_8))) return true;
                    res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"detail\":\"Invalid Channel management credential\"}");return false;
                }
            });
        }
    }
}
