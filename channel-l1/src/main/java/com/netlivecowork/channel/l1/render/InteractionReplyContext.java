package com.netlivecowork.channel.l1.render;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.*;
import com.netlivecowork.channel.contract.port.ProtocolStateStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Durable, instance/account/conversation scoped text reply addresses. Contains no authorization decision. */
public final class InteractionReplyContext {
    private final ProtocolStateStore store;
    public InteractionReplyContext(ProtocolStateStore store){this.store=store;}
    private static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private String key(String account,String conversation){return "interaction-replies."+hash(account+"\u0000"+conversation);}
    public synchronized String delivered(String account,String conversation,EventPacket packet) {
        String key=key(account,conversation);var entry=store.get(key).toCompletableFuture().join();
        ObjectNode contexts=entry==null?Json.obj():(ObjectNode)entry.value().deepCopy();
        String ref=null;long revision=0;
        if(packet.payload() instanceof EventPayload.InteractionRequired p){ref=p.payload().interactionRef();revision=p.payload().revision();}
        else if(packet.payload() instanceof EventPayload.InteractionResolved p){ref=p.interactionRef();revision=p.revision();}
        else if(packet.payload() instanceof EventPayload.InteractionExpired p){ref=p.interactionRef();revision=p.revision();}
        else if(packet.payload() instanceof EventPayload.InteractionCancelled p){ref=p.interactionRef();revision=p.revision();}
        else return "";
        String code=hash(ref).substring(0,12);
        var old=contexts.get(code);
        if(old!=null&&old.path("payload").path("revision").asLong()>revision)return "";
        if(packet.payload() instanceof EventPayload.InteractionRequired)contexts.set(code,PacketJson.enc(packet));
        else contexts.set(code,PacketJson.enc(packet));
        store.put(key,entry==null?null:entry.revision(),contexts).toCompletableFuture().join();
        return packet.payload() instanceof EventPayload.InteractionRequired?
            "\n回复 #"+code+" 同意 / 拒绝（审批），或 #"+code+" 选项序号（问题）。多题用 1:答案;2:答案；多选用逗号，其他答案用 其他=内容。":"";
    }
    public synchronized ChannelOperation.InteractionRespond reply(String account,String conversation,String requestId,String text) {
        // Explicit address avoids treating an ordinary chat message as a decision.
        if(!text.startsWith("#"))return null;
        String requestKey="interaction-request."+hash(account+"\u0000"+conversation+"\u0000"+requestId);
        var saved=store.get(requestKey).toCompletableFuture().join();
        if(saved!=null) {
            if(!hash(text).equals(saved.value().path("digest").asText()))throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,"Reply message changed");
            return new ChannelOperation.InteractionRespond(PacketJson.decRespondPayload(saved.value().get("payload")));
        }
        String[] parts=text.strip().split("\\s+",2);
        if(parts.length!=2)throw invalid();
        var entry=store.get(key(account,conversation)).toCompletableFuture().join();
        var value=entry==null?null:entry.value().get(parts[0].substring(1));
        if(value==null)throw invalid();
        var packet=PacketJson.decEvent(Json.write(value));
        if(!(packet.payload() instanceof EventPayload.InteractionRequired required))throw invalid();
        var pending=required.payload();
        var operation=InteractionReplyMapper.reply(pending,parts[1]);
        var savedValue=Json.obj().put("digest",hash(text));savedValue.set("payload",PacketJson.enc(operation.payload()));
        store.put(requestKey,null,savedValue).toCompletableFuture().join();
        return operation;
    }
    private ChannelContractException invalid(){return new ChannelContractException(ChannelErrorCode.INTERACTION_STALE,"请使用待办消息中的 #编号 回复，事项可能已结束。");}
}
