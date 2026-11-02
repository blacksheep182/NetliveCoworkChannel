package com.netlivecowork.channel.l3;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated reverse connections. Socket ownership is runtime-only; generations survive restarts. */
public final class ConnectorHub implements BackendTransport, AutoCloseable {
    public interface Wire { void send(String text) throws IOException; void close() throws IOException; }
    public final class Connection {
        private final String device,token;
        private final long generation;
        private final Wire wire;
        private final ConcurrentMap<String,CompletableFuture<JsonNode>> pending=new ConcurrentHashMap<>();
        private final AtomicBoolean ended=new AtomicBoolean();
        private volatile long seen;
        private Connection(String device,String token,long generation,Wire wire){
            this.device=device;this.token=token;this.generation=generation;this.wire=wire;seen=clock.millis();
        }
        public String deviceRef(){return device;}
        public long generation(){return generation;}
        public void receive(String text) throws IOException { ConnectorHub.this.receive(this,text); }
        public void disconnected(){drop(this);}
    }
    public static final int MAX_FRAME_BYTES=48*1024*1024;
    private static final int MAX_IN_FLIGHT=64;
    private static final long HEARTBEAT_MS=20_000, DEAD_MS=65_000;
    private final ConcurrentMap<String,Connection> online=new ConcurrentHashMap<>();
    private final DeviceRegistry devices;
    private final BackendRegistry backends;
    private final EventIngestService events;
    private final ServiceCredentialResolver credentials;
    private final Clock clock;
    private final L3Settings settings;
    ConnectorHub(DeviceRegistry devices,BackendRegistry backends,EventIngestService events,
                 ServiceCredentialResolver credentials,Clock clock,L3Settings settings){
        this.devices=devices;this.backends=backends;this.events=events;this.credentials=credentials;this.clock=clock;this.settings=settings;
    }
    public String authenticate(String token){return devices.authenticate(token);}
    public synchronized Connection open(String token,Wire wire)throws IOException{
        String device=devices.authenticate(token);long generation=devices.connect(token);
        var connection=new Connection(device,token,generation,wire);
        Connection prior=online.put(device,connection);if(prior!=null)drop(prior);
        try {wire.send(Json.write(Json.obj().put("type","welcome").put("protocol","connector-v1")
            .put("generation",generation).put("heartbeatMs",HEARTBEAT_MS)));}
        catch(IOException e){drop(connection);throw e;}
        return connection;
    }
    public String state(String device){
        if(devices.revoked(device))return "revoked";
        Connection c=online.get(device);return c!=null&&current(c)?"online":"offline";
    }
    public void revoke(String device){devices.revoke(device);Connection c=online.get(device);if(c!=null)drop(c);}
    public void tick(){for(Connection c:online.values())if(clock.millis()-c.seen>DEAD_MS||!current(c))drop(c);}
    private boolean current(Connection c){return !c.ended.get()&&online.get(c.device)==c&&devices.current(c.device,c.token,c.generation);}
    private void drop(Connection c){
        if(!c.ended.compareAndSet(false,true))return;
        online.remove(c.device,c);
        c.pending.values().forEach(f->f.completeExceptionally(new IOException("Device connection is unavailable")));
        c.pending.clear();try{c.wire.close();}catch(IOException ignored){}
    }
    private void receive(Connection c,String text)throws IOException{
        if(!current(c)){drop(c);throw new IOException("Stale device connection");}
        try{
            if(text.length()>MAX_FRAME_BYTES)throw new IllegalArgumentException("Frame exceeds limit");
            JsonNode frame=Json.read(text);
            if(!frame.path("generation").isIntegralNumber()||frame.path("generation").asLong()!=c.generation)
                throw new IllegalArgumentException("Connection generation mismatch");
            String type=Json.text(frame,"type");c.seen=clock.millis();
            switch(type){
                case "ping" -> {Json.onlyFields(frame,"type","generation");c.wire.send(Json.write(Json.obj().put("type","pong").put("generation",c.generation)));}
                case "response" -> {
                    Json.onlyFields(frame,"type","id","generation","status","headers","body","bodyBase64");
                    if(!frame.path("status").isIntegralNumber()||frame.path("status").asInt()<100||frame.path("status").asInt()>599)
                        throw new IllegalArgumentException("Invalid response status");
                    var future=c.pending.get(Json.text(frame,"id"));
                    if(future!=null)future.complete(frame);
                }
                case "event" -> event(c,frame);
                default -> throw new IllegalArgumentException("Unsupported connector frame");
            }
        }catch(RuntimeException error){drop(c);throw new IOException("Invalid connector frame",error);}
    }
    private void event(Connection c,JsonNode frame)throws IOException{
        Json.onlyFields(frame,"type","id","generation","serviceRef","headers","body");
        String serviceRef=Json.text(frame,"serviceRef");BackendService service=backends.service(serviceRef);
        if(service==null||!service.enabled()||service.accessType()!=AccessType.CONNECTOR||!c.device.equals(service.deviceRef()))
            throw new IllegalArgumentException("Device cannot carry this service");
        devices.requireScope(c.device,service.tenantRef(),service.workspaceRef());
        JsonNode headers=frame.path("headers");
        String sink=Json.text(headers,"X-Event-Sink-Ref"),authorization=Json.text(headers,"Authorization");
        long epoch=positive(headers,"X-Backend-Resource-Epoch"),revision=positive(headers,"X-Event-Sink-Revision");
        String bearer=authorization.startsWith("Bearer ")?authorization.substring(7):null;
        IngestResult result=events.ingestFromService(serviceRef,sink,bearer,epoch,revision,Json.write(frame.path("body")));
        int status=result.accepted()?200:switch(result.error().errorCode()){case UNAVAILABLE,INTERNAL_ERROR->503;case REQUEST_CONFLICT->409;case AUTH_REQUIRED->401;case INVALID_ARGUMENT->400;default->403;};
        var body=Json.obj();if(result.accepted())body.put("event_id",result.eventId()).put("status","accepted");
        else body.set("error",Json.obj().put("code",result.error().code()).put("message",result.error().message()));
        c.wire.send(Json.write(Json.obj().put("type","event_ack").put("id",Json.text(frame,"id"))
            .put("generation",c.generation).put("status",status).set("body",body)));
    }
    private static long positive(JsonNode n,String key){String s=Json.text(n,key);if(!s.matches("[0-9]{1,15}"))throw new IllegalArgumentException("Invalid event binding");long v=Long.parseLong(s);if(v<1)throw new IllegalArgumentException("Invalid event binding");return v;}
    private JsonNode request(BackendService service,String method,String path,String body,Map<String,String> headers,boolean binary)
            throws IOException,InterruptedException{
        if(service.accessType()!=AccessType.CONNECTOR||!service.enabled())throw new IOException("Connector service is unavailable");
        BackendService registered=backends.service(service.serviceRef());
        if(registered==null||!registered.equals(service))throw new IOException("Service registration changed");
        Connection c=online.get(service.deviceRef());if(c==null||!current(c))throw new IOException("Device is offline");
        devices.requireScope(service.deviceRef(),service.tenantRef(),service.workspaceRef());
        if(!allowed(method,path))throw new IOException("Backend route is outside the connector allowlist");
        String id=UUID.randomUUID().toString();var future=new CompletableFuture<JsonNode>();
        synchronized(c.pending){if(c.pending.size()>=MAX_IN_FLIGHT)throw new IOException("Device request limit reached");c.pending.put(id,future);}
        try{
            var forwarded=Json.obj();headers.forEach(forwarded::put);
            if(service.credentialRef()!=null){String token=credentials.resolve(service.credentialRef());
                if(token==null||token.isBlank())throw new IOException("Backend service credential unavailable");
                forwarded.put("Authorization","Bearer "+token);}
            var frame=Json.obj().put("type","request").put("id",id).put("generation",c.generation)
                .put("serviceRef",service.serviceRef()).put("method",method).put("path",path).put("body",body).put("binary",binary);
            frame.set("headers",forwarded);
            if(!current(c))throw new IOException("Device connection changed");
            String wire=Json.write(frame);if(wire.length()>MAX_FRAME_BYTES)throw new IOException("Frame exceeds limit");
            c.wire.send(wire);
            return future.get(binary?Math.max(30_000,settings.httpTimeoutMs()):settings.httpTimeoutMs(),TimeUnit.MILLISECONDS);
        }catch(ExecutionException|TimeoutException error){throw new IOException("Device response was not confirmed",error);}
        finally{c.pending.remove(id,future);}
    }
    static boolean allowed(String method,String path){
        if(!Set.of("GET","POST","PUT","DELETE").contains(method)||path==null||path.length()>4096
            ||!path.startsWith("/api/v1/")||path.contains("\\")||path.contains("#")||path.contains("://"))return false;
        String decoded;try{decoded=java.net.URLDecoder.decode(path,java.nio.charset.StandardCharsets.UTF_8);}catch(IllegalArgumentException e){return false;}
        if(decoded.contains("..")||decoded.contains("\\")||decoded.contains("%")||decoded.contains("//")||decoded.chars().anyMatch(x->x<32))return false;
        return path.startsWith("/api/v1/channel/v1/") || (method.equals("GET")&&path.startsWith("/api/v1/workspace/files/"));
    }
    public PythonBackendClient.Reply exchange(BackendService s,String m,String p,String b,Map<String,String> h)throws IOException,InterruptedException{
        JsonNode response=request(s,m,p,b,h,false);Long retry=null;
        String value=response.path("headers").path("retry-after").asText("");
        try{retry=Long.parseLong(value)*1000;}catch(NumberFormatException ignored){}
        if(!response.path("body").isTextual())throw new IOException("Backend response body is invalid");
        return new PythonBackendClient.Reply(response.path("status").asInt(),response.path("body").asText(),retry);
    }
    public FileReadResult readFile(BackendService s,String id,FileMetadata metadata)throws IOException,InterruptedException{
        JsonNode response=request(s,"GET",PythonBackendClient.fill(settings.endpoints().file(),"fileId",id),null,Map.of(),true);
        if(response.path("status").asInt()!=200)throw new IOException("Backend file is unavailable");
        byte[] bytes;try{bytes=Base64.getDecoder().decode(Json.text(response,"bodyBase64"));}catch(RuntimeException e){throw new IOException("Invalid file response",e);}
        if(bytes.length>L3Settings.FILE_SIZE_LIMIT_BYTES)throw new IOException("Backend file exceeds limit");
        return new FileReadResult(metadata,bytes);
    }
    public void close(){for(Connection c:online.values())drop(c);}
}
