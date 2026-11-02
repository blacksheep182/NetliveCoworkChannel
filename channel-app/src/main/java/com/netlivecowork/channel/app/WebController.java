package com.netlivecowork.channel.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.l1.web.WebAdapterInstance;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手机网页入口。页面只做协议搬运：令牌校验、统一请求与投递缓冲区都在 Web Adapter 内完成。
 * 未配置或未激活的接入口明确报不可用，不伪造受理。
 */
@RestController
@RequestMapping("/web")
final class WebController {

    private final ChannelRuntime runtime;

    WebController(ChannelRuntime runtime) {
        this.runtime = runtime;
    }

    private WebAdapterInstance adapter(String entryRef) {
        Object instance = runtime.l2().connections().instance(entryRef);
        return instance instanceof WebAdapterInstance web ? web : null;
    }

    @PostMapping("/{entryRef}/selection/query")
    ResponseEntity<ObjectNode> selection(@PathVariable String entryRef,@RequestBody JsonNode body,
            @RequestHeader(name="Authorization",required=false) String authorization) {
        return navigate(entryRef,body,authorization,"getSelection");
    }
    @PostMapping("/{entryRef}/workspaces/query")
    ResponseEntity<ObjectNode> workspaces(@PathVariable String entryRef,@RequestBody JsonNode body,
            @RequestHeader(name="Authorization",required=false) String authorization) {
        return navigate(entryRef,body,authorization,"listWorkspaces");
    }
    @PostMapping("/{entryRef}/workspaces/select")
    ResponseEntity<ObjectNode> selectWorkspace(@PathVariable String entryRef,@RequestBody JsonNode body,
            @RequestHeader(name="Authorization",required=false) String authorization) {
        return navigate(entryRef,body,authorization,"selectWorkspace");
    }
    @PostMapping("/{entryRef}/sessions/select")
    ResponseEntity<ObjectNode> selectSession(@PathVariable String entryRef,@RequestBody JsonNode body,
            @RequestHeader(name="Authorization",required=false) String authorization) {
        return navigate(entryRef,body,authorization,"selectSession");
    }
    private ResponseEntity<ObjectNode> navigate(String entryRef,JsonNode body,String authorization,String action) {
        var web=adapter(entryRef);
        if(web==null)return ResponseEntity.status(503).body(Json.obj().put("status","rejected"));
        try {
            Json.onlyFields(body,"requestId","viewId","sessionWorkspaceRef","targetRef","expectedBindingRevision");
            String token=pageToken(authorization,null),request=Json.text(body,"requestId"),view=Json.text(body,"viewId");
            var result=switch(action) {
                case "getSelection" -> web.getSelection(token,request,view);
                case "listWorkspaces" -> web.listWorkspaces(token,request,view);
                case "selectWorkspace" -> web.selectWorkspace(token,request,view,Json.text(body,"sessionWorkspaceRef"),Json.reqLong(body,"expectedBindingRevision"));
                case "selectSession" -> web.selectSession(token,request,view,Json.text(body,"targetRef"),Json.reqLong(body,"expectedBindingRevision"));
                default -> throw new IllegalArgumentException("Unknown navigation action");
            };
            return ResponseEntity.ok(PacketJson.enc(result));
        } catch(SecurityException e) {return unauthorized();}
        catch(IllegalArgumentException | com.netlivecowork.channel.contract.ChannelContractException e) {
            return ResponseEntity.badRequest().body(Json.obj().put("status","rejected").set("error",
                Json.obj().put("code","INVALID_ARGUMENT").put("message","Invalid navigation parameters")));
        }
    }

    @PostMapping("/{entryRef}/messages")
    ResponseEntity<ObjectNode> send(@PathVariable String entryRef, @RequestBody JsonNode body) {
        WebAdapterInstance web = adapter(entryRef);
        if (web == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Json.obj().put("status", "rejected")
                    .set("error", Json.obj().put("code", "UNAVAILABLE")
                        .put("message", "entry is not active")));
        }
        try {
            return ResponseEntity.ok(PacketJson.enc(web.submitInbound(Json.text(body, "token"),
                Json.text(body, "requestId"), Json.text(body, "text"), Json.optText(body, "targetRef"))));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Json.obj().put("status", "rejected").set("error",
                    Json.obj().put("code", "IDENTITY_UNBOUND").put("message", "invalid page token")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Json.obj().put("status", "rejected").set("error",
                Json.obj().put("code", "INVALID_ARGUMENT").put("message", "requestId and text are required")));
        }
    }

    @PostMapping("/{entryRef}/chat/messages")
    ResponseEntity<ObjectNode> chat(@PathVariable String entryRef, @RequestBody JsonNode body,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        WebAdapterInstance web = adapter(entryRef);
        if (web == null) return ResponseEntity.status(503).body(Json.obj().put("status", "rejected"));
        try {
            return ResponseEntity.ok(PacketJson.enc(body.has("viewId")
                ? web.submitChat(pageToken(authorization,null),Json.text(body,"requestId"),Json.text(body,"text"),Json.text(body,"targetRef"),Json.text(body,"viewId"),Json.reqLong(body,"expectedBindingRevision"))
                : web.submitChat(pageToken(authorization,null),Json.text(body,"requestId"),Json.text(body,"text"),Json.text(body,"targetRef"))));
        } catch (SecurityException e) { return unauthorized(); }
        catch (IllegalArgumentException | com.netlivecowork.channel.contract.ChannelContractException e) {
            return ResponseEntity.badRequest().body(Json.obj().put("status", "rejected").set("error",
                Json.obj().put("code", "INVALID_ARGUMENT").put("message", "valid requestId, text and targetRef are required")));
        }
    }

    @PostMapping("/{entryRef}/interactions/respond")
    ResponseEntity<ObjectNode> respond(@PathVariable String entryRef,@RequestBody JsonNode body,
            @RequestHeader(name="Authorization",required=false) String authorization) {
        var web=adapter(entryRef);
        if(web==null)return ResponseEntity.status(503).body(Json.obj().put("status","rejected"));
        try {
            Json.onlyFields(body,"requestId","viewId","interactionRef","expectedRevision","response");
            return ResponseEntity.ok(PacketJson.enc(web.respond(pageToken(authorization,null),Json.text(body,"requestId"),
                Json.text(body,"viewId"),Json.text(body,"interactionRef"),Json.reqLong(body,"expectedRevision"),body.get("response"))));
        } catch(SecurityException e){return unauthorized();}
        catch(IllegalArgumentException | com.netlivecowork.channel.contract.ChannelContractException e){
            return ResponseEntity.badRequest().body(Json.obj().put("status","rejected").set("error",
                Json.obj().put("code","INVALID_ARGUMENT").put("message","Invalid interaction response")));
        }
    }

    @PostMapping("/{entryRef}/sessions/get")
    ResponseEntity<ObjectNode> session(@PathVariable String entryRef, @RequestBody JsonNode body,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        WebAdapterInstance web = adapter(entryRef);
        if (web == null) return ResponseEntity.status(503).body(Json.obj().put("status", "rejected"));
        try {
            return ResponseEntity.ok(PacketJson.enc(body.has("viewId")
                ? web.getSession(pageToken(authorization,null),Json.text(body,"requestId"),Json.text(body,"targetRef"),Json.text(body,"viewId"))
                : web.getSession(pageToken(authorization,null),Json.text(body,"requestId"),Json.text(body,"targetRef"))));
        } catch (SecurityException e) { return unauthorized(); }
        catch (IllegalArgumentException | com.netlivecowork.channel.contract.ChannelContractException e) {
            return ResponseEntity.badRequest().body(Json.obj().put("status", "rejected").set("error",
                Json.obj().put("code", "INVALID_ARGUMENT").put("message", "valid requestId and targetRef are required")));
        }
    }

    @PostMapping("/{entryRef}/sessions/query")
    ResponseEntity<ObjectNode> sessions(@PathVariable String entryRef, @RequestBody JsonNode body,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        WebAdapterInstance web = adapter(entryRef);
        if (web == null) return ResponseEntity.status(503).body(Json.obj().put("status", "rejected")
            .set("error", Json.obj().put("code", "UNAVAILABLE").put("message", "entry is not active")));
        try {
            Integer limit = null;
            if (body.has("limit")) {
                if (!body.get("limit").isIntegralNumber() || !body.get("limit").canConvertToInt())
                    throw new IllegalArgumentException("invalid page limit");
                limit = body.get("limit").intValue();
            }
            return ResponseEntity.ok(PacketJson.enc(body.has("viewId")
                ? web.listSessions(pageToken(authorization,null),Json.text(body,"requestId"),Json.optText(body,"cursor"),limit,Json.text(body,"viewId"),Json.optText(body,"sessionWorkspaceRef"))
                : web.listSessions(pageToken(authorization,Json.optText(body,"token")),Json.text(body,"requestId"),Json.optText(body,"cursor"),limit)));
        } catch (SecurityException e) { return unauthorized(); }
        catch (IllegalArgumentException | com.netlivecowork.channel.contract.ChannelContractException e) {
            return ResponseEntity.badRequest().body(Json.obj().put("status", "rejected").set("error",
                Json.obj().put("code", "INVALID_ARGUMENT").put("message", "valid requestId, cursor and page limit are required")));
        }
    }

    @PostMapping("/{entryRef}/sessions/history")
    ResponseEntity<ObjectNode> history(@PathVariable String entryRef, @RequestBody JsonNode body,
            @RequestHeader(name="Authorization",required=false) String authorization) {
        var web=adapter(entryRef);
        if(web==null)return ResponseEntity.status(503).body(Json.obj().put("status","rejected"));
        try {
            Json.onlyFields(body,"targetRef","cursor","limit");
            if(body.has("limit") && (!body.path("limit").isIntegralNumber() || !body.path("limit").canConvertToInt()))
                throw new IllegalArgumentException("invalid limit");
            Integer limit=body.has("limit")?body.path("limit").intValue():null;
            var page=web.queryHistory(pageToken(authorization,null),Json.text(body,"targetRef"),Json.optText(body,"cursor"),limit);
            var result=Json.obj().put("targetRef",page.targetRef());
            var items=Json.arr();
            for(var item:page.items())items.add(Json.obj().put("messageRef",item.messageRef()).put("role",item.role())
                .put("text",item.text()).put("unsupportedContent",item.unsupportedContent()).put("createdAt",item.createdAt().toString()));
            result.set("items",items);if(page.nextCursor()!=null)result.put("nextCursor",page.nextCursor());
            return ResponseEntity.ok(result);
        } catch(SecurityException e) { return unauthorized(); }
        catch(java.util.concurrent.CompletionException e) {
            if(e.getCause() instanceof com.netlivecowork.channel.contract.ChannelContractException contract)return historyError(contract);
            return ResponseEntity.status(503).body(Json.obj().set("error",Json.obj().put("code","UNAVAILABLE").put("message","历史记录暂时无法加载，请重试。")));
        } catch(com.netlivecowork.channel.contract.ChannelContractException e) { return historyError(e); }
        catch(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Json.obj().set("error",Json.obj().put("code","INVALID_ARGUMENT").put("message","请提供有效的会话和分页参数。"))); }
    }

    private static ResponseEntity<ObjectNode> historyError(com.netlivecowork.channel.contract.ChannelContractException e) {
        int status=switch(e.code()) {
            case PERMISSION_DENIED, UNSUPPORTED_CAPABILITY -> 403;
            case IDENTITY_UNBOUND, AUTH_REQUIRED -> 401;
            case INVALID_ARGUMENT -> 400;
            default -> 503;
        };
        return ResponseEntity.status(status).body(Json.obj().set("error",Json.obj().put("code",e.code().name()).put("message",e.getMessage())));
    }

    @GetMapping("/{entryRef}/requests/{requestId}")
    ResponseEntity<ObjectNode> lookup(@PathVariable String entryRef, @PathVariable String requestId,
                                      @RequestHeader(name = "Authorization", required = false) String authorization) {
        WebAdapterInstance web = adapter(entryRef);
        if (web == null) return ResponseEntity.status(503).body(Json.obj().put("status", "rejected")
            .set("error", Json.obj().put("code", "UNAVAILABLE").put("message", "entry is not active")));
        try {
            return ResponseEntity.ok(PacketJson.enc(web.lookupInbound(pageToken(authorization, null), requestId)));
        } catch (SecurityException e) {
            return unauthorized();
        }
    }

    private static String pageToken(String authorization, String legacyToken) {
        if (authorization == null) return legacyToken;
        if (!authorization.startsWith("Bearer ") || authorization.length() <= 7) throw new SecurityException();
        return authorization.substring(7);
    }

    private static ResponseEntity<ObjectNode> unauthorized() {
        return ResponseEntity.status(401).body(Json.obj().put("status", "rejected").set("error",
            Json.obj().put("code", "IDENTITY_UNBOUND").put("message", "invalid page token")));
    }

    @GetMapping("/{entryRef}/inbox")
    ResponseEntity<ObjectNode> inbox(@PathVariable String entryRef,
            @RequestHeader(name="Authorization",required=false) String authorization,
            @RequestParam String consumerKey,@RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit) {
        var web=adapter(entryRef);if(web==null)return ResponseEntity.status(503).body(Json.obj().put("status","unavailable"));
        try {
            var result=web.readInbox(pageToken(authorization,null),new com.netlivecowork.channel.contract.DeliveryInbox.Request(consumerKey,cursor,limit));
            var response=Json.obj().put("status",result.status());
            if(result.page()!=null) {
                var page=result.page();var messages=Json.arr();
                for(var item:page.items()) {
                    var d=item.delivery();var p=d.packet();
                    var message=Json.obj().put("sequence",item.sequence()).put("deliveryId",d.deliveryId())
                        .put("type",p.type().wire()).put("sourceLabel",d.presentation().sourceLabel())
                        .put("text",com.netlivecowork.channel.l1.render.PlainTextRenderer.body(p)).put("targetRef",p.context().targetRef());
                    message.set("presentation",Json.mapper().valueToTree(d.presentation()));
                    if(item.readAt()!=null)message.put("readAt",item.readAt());
                    if(p.payload() instanceof com.netlivecowork.channel.contract.EventPayload.MessageReady ready)message.put("messageRef",ready.message().messageRef());
                    if(p.type().wire().startsWith("interaction.") || p.type()==com.netlivecowork.channel.contract.EventType.TASK_COMPLETED)message.set("packet",PacketJson.enc(p));
                    messages.add(message);
                }
                response.put("consumerRef",page.consumerRef()).put("nextCursor",page.nextCursor()).put("hasMore",page.hasMore());response.set("messages",messages);
            } else response.put("reason",result.reason()).put("resyncCursor",result.resyncCursor());
            return ResponseEntity.ok(response);
        } catch(RuntimeException e) {return inboxError(e);}
    }

    @PostMapping("/{entryRef}/inbox/ack")
    ResponseEntity<ObjectNode> inboxAck(@PathVariable String entryRef,@RequestBody JsonNode body,
            @RequestHeader(name="Authorization",required=false) String authorization) {
        var web=adapter(entryRef);if(web==null)return ResponseEntity.status(503).body(Json.obj().put("status","unavailable"));
        try {
            Json.onlyFields(body,"consumerRef","kind","throughCursor","deliveryIds");
            java.util.List<String> ids=null;
            if(body.has("deliveryIds")) { ids=new java.util.ArrayList<>();for(var id:Json.optArray(body,"deliveryIds")) { if(!id.isTextual())throw new IllegalArgumentException();ids.add(id.asText()); } }
            web.acknowledgeInbox(pageToken(authorization,null),new com.netlivecowork.channel.contract.DeliveryInbox.Acknowledgement(
                Json.text(body,"consumerRef"),Json.text(body,"kind"),Json.optText(body,"throughCursor"),ids));
            return ResponseEntity.ok(Json.obj().put("status","completed"));
        } catch(RuntimeException e) {return inboxError(e);}
    }

    private ResponseEntity<ObjectNode> inboxError(RuntimeException error) {
        Throwable cause=error;while(cause.getCause()!=null && cause instanceof java.util.concurrent.CompletionException)cause=cause.getCause();
        if(cause instanceof SecurityException)return unauthorized();
        String code=cause instanceof com.netlivecowork.channel.contract.ChannelContractException c?c.code().name():"UNAVAILABLE";
        int status=code.equals("PERMISSION_DENIED")||code.equals("IDENTITY_UNBOUND")||code.equals("AUTH_REQUIRED")?403:code.equals("INVALID_ARGUMENT")||cause instanceof IllegalArgumentException?400:503;
        return ResponseEntity.status(status).body(Json.obj().put("status","rejected").set("error",Json.obj().put("code",code).put("message","收件读取或确认失败，请重新连接后重试。")));
    }

    @GetMapping("/{entryRef}/messages")
    ResponseEntity<ObjectNode> poll(@PathVariable String entryRef,
                                    @RequestParam(name = "token", required = false) String token,
                                    @RequestParam(name = "after", defaultValue = "0") long after,
                                    @RequestParam(name = "stream", required = false) String stream,
                                    @RequestHeader(name = "Authorization", required = false) String authorization) {
        WebAdapterInstance web = adapter(entryRef);
        if (web == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Json.obj().put("status", "rejected"));
        }
        try {
            // An instance restart resets its in-memory sequence. A known old stream must
            // restart from zero; no other caller's sequence or buffer is exposed.
            long cursor = stream != null && !stream.isBlank() && !web.streamId().equals(stream) ? 0 : Math.max(0, after);
            var delivered = web.poll(pageToken(authorization, token), cursor);
            ArrayNode messages = Json.arr();
            long next = cursor;
            for (WebAdapterInstance.Delivered item : delivered) {
                var message=Json.obj().put("sequence", item.sequence()).put("type", item.type())
                    .put("sourceLabel", item.sourceLabel()).put("text", item.text()).put("targetRef", item.targetRef())
                    .put("messageRef",item.messageRef());
                if(item.packet()!=null)message.set("packet",PacketJson.enc(item.packet()));
                messages.add(message);
                next = Math.max(next, item.sequence());
            }
            ObjectNode response = Json.obj();
            response.set("messages", messages);
            response.put("next", next);
            response.put("streamId", web.streamId());
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Json.obj().put("status", "rejected").set("error",
                    Json.obj().put("code", "IDENTITY_UNBOUND").put("message", "invalid page token")));
        }
    }
}
