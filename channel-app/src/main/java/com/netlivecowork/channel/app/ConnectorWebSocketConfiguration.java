package com.netlivecowork.channel.app;

import com.netlivecowork.channel.l3.ConnectorHub;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Only the physical handshake lives in app; authentication, ownership and routing remain in L3. */
@Configuration
@EnableWebSocket
class ConnectorWebSocketConfiguration implements WebSocketConfigurer {
    private final ChannelRuntime runtime;
    ConnectorWebSocketConfiguration(ChannelRuntime runtime){this.runtime=runtime;}
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        var handler=new Handler(runtime.l3().connectors());
        registry.addHandler(handler,"/api/v1/l3/connector").addInterceptors(new HandshakeInterceptor(){
            public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler h,Map<String,Object> attributes){
                // Machine connector only. Tokens are never accepted in a URL or from browser origins.
                if(request.getURI().getRawQuery()!=null || request.getHeaders().getOrigin()!=null){response.setStatusCode(HttpStatus.FORBIDDEN);return false;}
                String auth=request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                if(auth==null||!auth.startsWith("Bearer ")){response.setStatusCode(HttpStatus.UNAUTHORIZED);return false;}
                try{String token=auth.substring(7);runtime.l3().connectors().authenticate(token);attributes.put("deviceToken",token);return true;}
                catch(RuntimeException e){response.setStatusCode(HttpStatus.UNAUTHORIZED);return false;}
            }
            public void afterHandshake(ServerHttpRequest req,ServerHttpResponse res,WebSocketHandler h,Exception e){}
        });
    }
    private static final class Handler extends TextWebSocketHandler {
        private final ConnectorHub hub;
        private final Map<String,ConnectorHub.Connection> connections=new ConcurrentHashMap<>();
        Handler(ConnectorHub hub){this.hub=hub;}
        @Override public void afterConnectionEstablished(WebSocketSession original)throws Exception{
            original.setTextMessageSizeLimit(ConnectorHub.MAX_FRAME_BYTES);
            var session=new ConcurrentWebSocketSessionDecorator(original,5_000,ConnectorHub.MAX_FRAME_BYTES);
            try{var c=hub.open((String)original.getAttributes().get("deviceToken"),new ConnectorHub.Wire(){
                public void send(String value)throws java.io.IOException{session.sendMessage(new TextMessage(value));}
                public void close()throws java.io.IOException{session.close(CloseStatus.NORMAL);}
            });connections.put(original.getId(),c);}
            catch(Exception e){original.close(CloseStatus.POLICY_VIOLATION);}
            finally{original.getAttributes().remove("deviceToken");}
        }
        @Override protected void handleTextMessage(WebSocketSession session,TextMessage message)throws Exception{
            var c=connections.get(session.getId());
            if(c==null){session.close(CloseStatus.POLICY_VIOLATION);return;}
            try{c.receive(message.getPayload());}catch(Exception e){c.disconnected();session.close(CloseStatus.POLICY_VIOLATION);}
        }
        @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){
            var c=connections.remove(session.getId());if(c!=null)c.disconnected();
        }
        @Override public void handleTransportError(WebSocketSession session,Throwable error)throws Exception{
            var c=connections.remove(session.getId());if(c!=null)c.disconnected();session.close(CloseStatus.SERVER_ERROR);
        }
    }
}
