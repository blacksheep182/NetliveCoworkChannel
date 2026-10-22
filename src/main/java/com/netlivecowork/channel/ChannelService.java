package com.netlivecowork.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static com.netlivecowork.channel.ChannelConfig.*;

/** Text-only admission and delivery. Session preparation/execution stay in the Python host. */
@Service
public class ChannelService {
    static final String HELP="/list 查看授权会话；/use <序号或后端/会话ID> 选择；/status；/resume；"
        +"answer <交互ID> <内容>；approve <交互ID>；reject <交互ID> <理由>。普通文字交给当前会话。";
    static final Pattern RESOLVE=Pattern.compile("^/?(answer|approve|reject)(?:\\s+(.*))?$",Pattern.DOTALL);
    private final ChannelConfig config;
    private final ChannelStore store;
    private final BackendClient backend;
    private final ChannelAdapters adapters;
    private final ObjectMapper json;

    public ChannelService(ChannelConfig config,ChannelStore store,BackendClient backend,ChannelAdapters adapters,ObjectMapper json) {
        this.config=config; this.store=store; this.backend=backend; this.adapters=adapters; this.json=json;
    }
    static String key(String... parts) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\0",parts).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private Binding binding(String id) { return config.bindings().stream().filter(b->b.id().equals(id)).findFirst().orElse(null); }
    private String identityKey(JsonNode row,String suffix) {
        var source=row.path("source");
        return key(row.path("binding").asText(),source.path("connectionId").asText(),
            source.path("externalUserId").asText(),source.path("conversationId").asText(),suffix);
    }
    private Target target(JsonNode node) {
        try { return json.treeToValue(node,Target.class); } catch (Exception e) { throw new IllegalArgumentException("Invalid target",e); }
    }
    private boolean authorized(JsonNode row) {
        var binding=binding(row.path("binding").asText());
        var source=row.path("source");
        return binding!=null && binding.connectionId().equals(source.path("connectionId").asText())
            && binding.externalUserId().equals(source.path("externalUserId").asText())
            && binding.conversationId().equals(source.path("conversationId").asText())
            && binding.targets().contains(target(row.path("target")));
    }
    public record Inbound(String connectionId,String eventId,String externalUserId,String conversationId,String text,String contextToken) {}

    public synchronized ObjectNode receive(Inbound input) {
        if (input.eventId()==null || input.eventId().isBlank() || input.externalUserId()==null || input.conversationId()==null || input.text()==null)
            throw new IllegalArgumentException("Stable eventId, externalUserId, conversationId and text are required");
        if (input.text().length()>64000) throw new IllegalArgumentException("Text is too long");
        if (config.connections().stream().noneMatch(c->c.id().equals(input.connectionId()))) throw new IllegalArgumentException("Unknown connection");
        String id=key(input.connectionId(),input.eventId());
        var previous=store.get("inbox",id);
        var source=json.valueToTree(input);
        if (previous!=null) {
            if (!previous.path("source").equals(source)) throw new IllegalArgumentException("Event ID was reused for different content");
            return previous;
        }
        var bound=config.bindings().stream().filter(b->b.connectionId().equals(input.connectionId())
            && b.externalUserId().equals(input.externalUserId()) && b.conversationId().equals(input.conversationId())).findFirst().orElse(null);
        var row=json.createObjectNode().put("id",id).put("state","received"); row.set("source",source);
        store.atomic(()-> {
            if (input.contextToken()!=null && !input.contextToken().isBlank())
                store.put("context",key(input.connectionId(),input.conversationId()),json.createObjectNode().put("token",input.contextToken()));
            if (bound==null) { row.put("state","rejected"); reply(row,"unpaired","此聊天尚未授权，请在服务配置中绑定身份和会话。"); }
            else {
                row.put("binding",bound.id());
                String selectionKey=identityKey(row,"selection");
                var selected=store.get("target",selectionKey);
                if (selected!=null && bound.targets().contains(target(selected))) row.set("target",selected);
                String text=input.text().strip();
                if (text.equals("/help") || text.equals("/list") || text.equals("/ws") || text.equals("/workspaces")) {
                    var lines=new ArrayList<String>(); lines.add(HELP);
                    for (int i=0;i<bound.targets().size();i++) { var t=bound.targets().get(i); lines.add((i+1)+". "+t.backendId()+" / "+t.workspaceRef()+" / "+t.sessionId()); }
                    finish(row,"completed",String.join("\n",lines));
                } else if (text.startsWith("/use ")) {
                    String choice=text.substring(5).strip();
                    var choices=bound.targets().stream().filter(t->(t.backendId()+"/"+t.sessionId()).equals(choice)).toList();
                    if (choice.matches("[0-9]{1,6}")) {
                        int index=Integer.parseInt(choice)-1;
                        choices=index>=0 && index<bound.targets().size() ? List.of(bound.targets().get(index)) : List.of();
                    }
                    if (choices.size()!=1) finish(row,"rejected","目标不存在或未授权。请发送 /list，再用 /use <序号> 选择。");
                    else { store.put("target",selectionKey,json.valueToTree(choices.getFirst())); finish(row,"completed","已选择 "+choices.getFirst().sessionId()); }
                } else if (RESOLVE.matcher(text).matches()) {
                    var match=RESOLVE.matcher(text); match.matches();
                    String rest=match.group(2)==null ? "" : match.group(2).strip();
                    String[] args=rest.split("\\s+",2);
                    row.put("action",match.group(1)).put("interaction",args[0]).put("answer",args.length==2 ? args[1] : "");
                    var interaction=store.get("interaction",identityKey(row,args[0]));
                    if (args[0].isBlank() || (match.group(1).equals("answer") && row.path("answer").asText().isBlank()))
                        finish(row,"rejected",HELP);
                    else if (interaction==null) finish(row,"rejected","交互不存在、已过期或不属于此聊天。");
                    else row.set("target",interaction.path("target")); // 应答原会话，不随 /use 切换。
                } else if (text.startsWith("/") && !List.of("/status","/resume").contains(text)) finish(row,"rejected",HELP);
                else if (!row.has("target")) finish(row,"rejected","请先 /list 查看，再 /use 选择获授权会话。");
                else if (text.isBlank()) finish(row,"rejected","暂只支持文字，请发送文字内容。");
            }
            store.put("inbox",id,row);
        });
        return row;
    }
    private void finish(ObjectNode row,String state,String text) { row.put("state",state); reply(row,"result",text); }
    private void reply(JsonNode row,String event,String text) {
        if (text==null || text.isBlank()) return;
        String label=row.has("target") ? "【"+row.path("target").path("workspaceRef").asText()+" / "+row.path("target").path("sessionId").asText()+"】\n" : "";
        int part=0;
        for (int start=0;start<text.length();) {
            int end=Math.min(start+1500,text.length());
            if (end<text.length() && Character.isHighSurrogate(text.charAt(end-1))) end--;
            String id=key(row.path("id").asText(),event,Integer.toString(part++));
            if (store.get("outbox",id)==null) {
                var out=json.createObjectNode().put("id",id).put("state","pending").put("text",label+text.substring(start,end));
                out.set("source",row.path("source"));
                if (row.has("target")) out.set("target",row.path("target"));
                if (row.has("binding")) out.set("binding",row.path("binding"));
                store.put("outbox",id,out);
            }
            start=end;
        }
    }
    private ObjectNode stream(JsonNode row) {
        String id=identityKey(row,row.path("target").toString());
        var stream=store.get("stream",id);
        if (stream==null || !stream.path("active").asBoolean()) {
            stream=((ObjectNode)row).deepCopy(); stream.put("id",id).put("active",true).put("cursor",backend.baseline(target(row.path("target"))));
            store.put("stream",id,stream);
        }
        return stream;
    }
    private void rememberPending(ObjectNode stream,JsonNode pending) {
        for (JsonNode item:pending) {
            String id=item.path("id").asText(); String key=identityKey(stream,id);
            if (store.get("interaction",key)!=null) continue;
            var saved=json.createObjectNode().put("state","pending").put("form",item.path("form").asText()).put("id",id);
            saved.set("target",stream.path("target")); store.put("interaction",key,saved);
            String question=item.path("question").asText("Agent 需要你的输入");
            if (!item.path("questions").isEmpty()) question+="\n"+item.path("questions");
            String hint=item.path("form").asText().equals("approval") ? "approve "+id+" 或 reject "+id+" <理由>" : "answer "+id+" <内容> 或 reject "+id+" <理由>";
            reply(stream,"hitl:"+id,question+(item.path("arguments").isEmpty() ? "" : "\n"+item.path("arguments"))+"\n"+hint);
        }
    }
    private void process(ObjectNode row) {
        if (!authorized(row)) { finish(row,"rejected","会话授权已撤销，请重新配置。"); store.put("inbox",row.path("id").asText(),row); return; }
        Target target=target(row.path("target")); String text=row.path("source").path("text").asText().strip();
        try {
            backend.prepare(target); // 使用主线的懒装填/执行准备，不在 Java 重建会话。
            var session=backend.session(target);
            if (text.equals("/status")) { finish(row,"completed",session.path("status").asText()); return; }
            if (!row.has("interaction") && !text.equals("/resume") && List.of("RUNNING","INTERRUPTED").contains(session.path("status").asText())) {
                finish(row,"rejected",session.path("status").asText().equals("RUNNING") ? "会话正在执行，请稍后再发。" : "会话已中断，请先 /resume。"); return;
            }
            var pending=backend.pending(target);
            var stream=stream(row);
            store.atomic(()->rememberPending(stream,pending));
            if (row.has("interaction")) {
                String id=row.path("interaction").asText(), action=row.path("action").asText();
                var saved=store.get("interaction",identityKey(row,id));
                String outcome=action.equals("reject") ? "rejected" : "accepted";
                if (!saved.path("state").asText().equals("pending")) {
                    finish(row,saved.path("state").asText().equals(outcome) ? "completed" : "rejected","该交互已处理（"+saved.path("state").asText()+"）。"); return;
                }
                JsonNode request=null;
                for (JsonNode item:pending) if (item.path("id").asText().equals(id)) request=item;
                if (request==null) { finish(row,"rejected","交互已过期或已由其他入口处理。"); return; }
                boolean approval=request.path("form").asText().equals("approval");
                if ((approval && action.equals("answer")) || (!approval && action.equals("approve"))) { finish(row,"rejected","动作与交互类型不匹配。"); return; }
                dispatch(row,()-> {
                    var result=backend.resolve(target,id,action,row.path("answer").asText());
                    saved.put("state",result.path("status").asText(outcome)); store.put("interaction",identityKey(row,id),saved);
                    return "已记录应答（"+saved.path("state").asText()+"）。";
                });
            } else if (text.equals("/resume")) dispatch(row,()->{ backend.resume(target); return "已请求恢复。"; });
            else if (!pending.isEmpty()) finish(row,"rejected","会话等待人工应答，请使用明确的 answer / approve / reject 和交互ID。");
            else dispatch(row,()->{ backend.send(target,text,session); return null; });
        } catch (BackendClient.BackendError e) {
            row.put("error",e.getMessage());
            if (List.of(401,403,404,409,422).contains(e.status)) finish(row,"rejected",e.getMessage());
            else reply(row,"unavailable","会话后端暂不可用，消息已保留。尚未提交的消息将在恢复后重试。");
        } catch (IllegalStateException e) {
            row.put("state","blocked").put("error",e.getMessage());
            reply(row,"blocked","消费位置无法核对，消息尚未提交。请检查服务状态。");
        } finally { store.put("inbox",row.path("id").asText(),row); }
    }
    private void dispatch(ObjectNode row,java.util.function.Supplier<String> action) {
        row.put("state","dispatching"); store.put("inbox",row.path("id").asText(),row);
        try { String reply=action.get(); row.put("state","completed"); if (reply!=null) reply(row,"result",reply); }
        catch (RuntimeException e) {
            // 原后端没有稳定请求查询接口。响应丢失时不能声称失败，更不能盲目重放。
            boolean rejected=e instanceof BackendClient.BackendError b && List.of(401,403,404,409,422).contains(b.status);
            finish(row,rejected ? "rejected" : "unknown",rejected ? e.getMessage() : "提交结果未知，请核对原会话；不会自动重复提交。");
        }
    }
    private void poll(ObjectNode stream) {
        if (!authorized(stream)) { stream.put("active",false); store.put("stream",stream.path("id").asText(),stream); return; }
        Target target=target(stream.path("target"));
        try {
            var session=backend.session(target);
            var frames=backend.after(target,stream.path("cursor").asText());
            var pending=backend.pending(target);
            var next=stream.deepCopy();
            store.atomic(()-> {
                for (JsonNode event:frames) {
                    String id=event.path("event_id").asText();
                    if (id.isBlank()) throw new IllegalStateException("History frame has no stable identity");
                    if (event.path("type").asText().equals("text_done")) reply(stream,id,event.path("text").asText());
                    if (event.path("type").asText().equals("session_notice")) reply(stream,id,event.path("reason_text").asText());
                    next.put("cursor",id);
                }
                rememberPending(next,pending);
                if (pending.isEmpty() && List.of("SUCCEEDED","FAILED","CANCELED","PAUSED","INTERRUPTED").contains(session.path("status").asText())) next.put("active",false);
                next.remove("error");
                store.put("stream",next.path("id").asText(),next);
            });
        } catch (RuntimeException e) { stream.put("error",e.getMessage()); store.put("stream",stream.path("id").asText(),stream); }
    }
    private void deliver(ObjectNode out) {
        if (out.has("target") && !authorized(out)) { out.put("state","suppressed"); store.put("outbox",out.path("id").asText(),out); return; }
        String cid=out.path("source").path("connectionId").asText();
        var connection=config.connections().stream().filter(c->c.id().equals(cid)).findFirst().orElse(null);
        if (connection==null) { out.put("state","suppressed"); store.put("outbox",out.path("id").asText(),out); return; }
        out.put("state","sending"); store.put("outbox",out.path("id").asText(),out);
        try {
            String conversation=out.path("source").path("conversationId").asText();
            var context=store.get("context",key(cid,conversation));
            adapters.get(connection).send(connection,conversation,context==null ? "" : context.path("token").asText(),out.path("id").asText(),out.path("text").asText());
            out.put("state","sent");
        } catch (RuntimeException e) { out.put("state","unknown").put("error",e.getMessage()); }
        store.put("outbox",out.path("id").asText(),out);
    }
    @Scheduled(fixedDelayString="${channel.poll-ms:1000}")
    public synchronized void tick() {
        for (var inbox:store.all("inbox")) if (inbox.path("state").asText().equals("received")) process(inbox);
        for (var stream:store.all("stream")) if (stream.path("active").asBoolean()) poll(stream);
        for (var out:store.all("outbox")) if (out.path("state").asText().equals("pending")) deliver(out);
    }
}
