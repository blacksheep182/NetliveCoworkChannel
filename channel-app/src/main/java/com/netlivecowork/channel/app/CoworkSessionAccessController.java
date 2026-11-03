package com.netlivecowork.channel.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** CoWork server-to-server control plane. Service credentials alone never establish user authority. */
@RestController
@RequestMapping("/api/v1/cowork/session-access/{serviceRef}")
final class CoworkSessionAccessController {
    private final ChannelRuntime runtime;
    CoworkSessionAccessController(ChannelRuntime runtime){this.runtime=runtime;}
    @GetMapping
    ResponseEntity<ObjectNode> get(@PathVariable String serviceRef,@RequestHeader(value="Authorization",required=false)String authorization,@RequestHeader(value="X-Cowork-Delegation",required=false)String proof) {
        return respond(()->{
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            var view=runtime.l2().getSessionAccess(manager);
            var result=Json.obj().put("status","completed").put("revision",view.revision()).put("principalRef",view.principalRef());
            var items=result.putArray("items");
            for(var item:view.items())items.add(Json.obj().put("targetRef",item.targetRef()).put("label",item.label()).put("workspaceLabel",item.workspaceLabel()).put("state",item.status()).put("selected",item.selected()));
            return result;
        });
    }
    @PostMapping
    ResponseEntity<ObjectNode> save(@PathVariable String serviceRef,@RequestHeader(value="Authorization",required=false)String authorization,@RequestHeader(value="X-Cowork-Delegation",required=false)String proof,@RequestBody JsonNode body) {
        return respond(()->{
            Json.onlyFields(body,"requestId","expectedRevision","targetRefs");
            if(!body.path("expectedRevision").isIntegralNumber() || !body.path("expectedRevision").canConvertToLong() || !body.path("targetRefs").isArray())throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,"Current revision and exact selection are required");
            var refs=new ArrayList<String>();for(var ref:body.path("targetRefs")) {
                if(!ref.isTextual())throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,"Logical session references must be strings");refs.add(ref.asText());
            }
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            return saved(runtime.l2().saveSessionAccess(manager,new SessionAccess.Save(Json.text(body,"requestId"),body.path("expectedRevision").asLong(),refs)));
        });
    }
    @GetMapping("/requests/{requestId}")
    ResponseEntity<ObjectNode> lookup(@PathVariable String serviceRef,@PathVariable String requestId,@RequestHeader(value="Authorization",required=false)String authorization,@RequestHeader(value="X-Cowork-Delegation",required=false)String proof) {
        return respond(()->{
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            return saved(runtime.l2().lookupSessionAccess(manager,requestId));
        });
    }
    @PostMapping("/weixin-bindings")
    ResponseEntity<ObjectNode> beginWeixinBinding(@PathVariable String serviceRef,
            @RequestHeader(value="Authorization",required=false)String authorization,
            @RequestHeader(value="X-Cowork-Delegation",required=false)String proof,@RequestBody JsonNode body) {
        return respond(()->{
            Json.onlyFields(body,"entryRef");
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            var challenge=runtime.l2().beginOwnerWeixinBinding(manager,Json.text(body,"entryRef"));
            return Json.obj().put("challenge","/bind "+challenge.token())
                .put("expiresAt",challenge.expiresAt().toString());
        });
    }
    @GetMapping("/weixin-bindings")
    ResponseEntity<ObjectNode> weixinBindingEntries(@PathVariable String serviceRef,
            @RequestHeader(value="Authorization",required=false)String authorization,
            @RequestHeader(value="X-Cowork-Delegation",required=false)String proof) {
        return respond(()->{
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            var result=Json.obj();var items=result.putArray("items");
            for(var item:runtime.l2().ownerWeixinBindingEntries(manager))
                items.add(Json.obj().put("entryRef",item.entryRef()).put("name",item.name())
                    .put("accountVerified",item.accountVerified()).put("bound",item.bound()));
            return result;
        });
    }
    @GetMapping("/weixin-bindings/{entryRef}")
    ResponseEntity<ObjectNode> weixinBindingStatus(@PathVariable String serviceRef,@PathVariable String entryRef,
            @RequestHeader(value="Authorization",required=false)String authorization,
            @RequestHeader(value="X-Cowork-Delegation",required=false)String proof) {
        return respond(()->{
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            var status=runtime.l2().ownerWeixinBindingStatus(manager,entryRef);
            var result=Json.obj().put("bound",status.bound());
            if(status.bound())result.put("weixinSubject",status.weixinSubject());
            return result;
        });
    }
    @DeleteMapping("/weixin-bindings/{entryRef}")
    ResponseEntity<ObjectNode> revokeWeixinBinding(@PathVariable String serviceRef,@PathVariable String entryRef,
            @RequestHeader(value="Authorization",required=false)String authorization,
            @RequestHeader(value="X-Cowork-Delegation",required=false)String proof) {
        return respond(()->{
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            runtime.l2().revokeOwnerWeixinBinding(manager,entryRef);
            return Json.obj().put("bound",false);
        });
    }
    @GetMapping("/qq-bindings")
    ResponseEntity<ObjectNode> qqBindingEntries(@PathVariable String serviceRef,
            @RequestHeader(value="Authorization",required=false)String authorization,
            @RequestHeader(value="X-Cowork-Delegation",required=false)String proof) {
        return respond(()->{
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            var result=Json.obj();var items=result.putArray("items");
            for(var item:runtime.l2().ownerQqBindingEntries(manager))
                items.add(Json.obj().put("entryRef",item.entryRef()).put("name",item.name())
                    .put("scopeRef",item.scopeRef()).put("groupId",item.groupId())
                    .put("accountVerified",item.accountVerified()).put("bound",item.bound()));
            return result;
        });
    }
    @PostMapping("/qq-bindings")
    ResponseEntity<ObjectNode> beginQqBinding(@PathVariable String serviceRef,
            @RequestHeader(value="Authorization",required=false)String authorization,
            @RequestHeader(value="X-Cowork-Delegation",required=false)String proof,@RequestBody JsonNode body) {
        return respond(()->{
            Json.onlyFields(body,"entryRef","scopeRef");
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            var challenge=runtime.l2().beginOwnerQqBinding(manager,Json.text(body,"entryRef"),Json.text(body,"scopeRef"));
            return Json.obj().put("challenge","/bind "+challenge.token())
                .put("expiresAt",challenge.expiresAt().toString());
        });
    }
    @GetMapping("/qq-bindings/{entryRef}/{scopeRef}")
    ResponseEntity<ObjectNode> qqBindingStatus(@PathVariable String serviceRef,@PathVariable String entryRef,
            @PathVariable String scopeRef,@RequestHeader(value="Authorization",required=false)String authorization,
            @RequestHeader(value="X-Cowork-Delegation",required=false)String proof) {
        return respond(()->{
            var status=runtime.l2().ownerQqBindingStatus(runtime.coworkManager(serviceRef,authorization,proof),entryRef,scopeRef);
            var result=Json.obj().put("bound",status.bound());
            if(status.bound())result.put("qqSubject",status.weixinSubject());
            return result;
        });
    }
    @DeleteMapping("/qq-bindings/{entryRef}/{scopeRef}")
    ResponseEntity<ObjectNode> revokeQqBinding(@PathVariable String serviceRef,@PathVariable String entryRef,
            @PathVariable String scopeRef,@RequestHeader(value="Authorization",required=false)String authorization,
            @RequestHeader(value="X-Cowork-Delegation",required=false)String proof) {
        return respond(()->{
            runtime.l2().revokeOwnerQqBinding(runtime.coworkManager(serviceRef,authorization,proof),entryRef,scopeRef);
            return Json.obj().put("bound",false);
        });
    }
    @GetMapping("/group-workspaces")
    ResponseEntity<ObjectNode> groupWorkspaces(@PathVariable String serviceRef,
            @RequestHeader(value="Authorization",required=false)String authorization,
            @RequestHeader(value="X-Cowork-Delegation",required=false)String proof) {
        return respond(()->{
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            var result=Json.obj();var groups=result.putArray("groups");var grants=result.putArray("grants");
            for(var group:runtime.l2().observedGroups(manager))
                groups.add(Json.obj().put("scopeRef",group.scopeRef()).put("entryRef",group.entryRef())
                    .put("groupId",group.groupId()));
            for(var grant:runtime.l2().groupWorkspaceGrants(manager))grants.add(grantJson(grant));
            return result;
        });
    }
    @GetMapping("/group-workspaces/entries/{entryRef}/workspaces")
    ResponseEntity<ObjectNode> groupCreationWorkspaces(@PathVariable String serviceRef,@PathVariable String entryRef,
            @RequestHeader(value="Authorization",required=false)String authorization,
            @RequestHeader(value="X-Cowork-Delegation",required=false)String proof) {
        return respond(()->{
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            var result=Json.obj();var items=result.putArray("items");
            for(var workspace:runtime.l2().groupCreationWorkspaces(manager,entryRef))
                items.add(Json.obj().put("sessionWorkspaceRef",workspace.sessionWorkspaceRef())
                    .put("label",workspace.label()));
            return result;
        });
    }
    @PostMapping("/group-workspaces")
    ResponseEntity<ObjectNode> openGroupWorkspace(@PathVariable String serviceRef,
            @RequestHeader(value="Authorization",required=false)String authorization,
            @RequestHeader(value="X-Cowork-Delegation",required=false)String proof,@RequestBody JsonNode body) {
        return respond(()->{
            Json.onlyFields(body,"entryRef","scopeRef","sessionWorkspaceRef");
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            return grantJson(runtime.l2().openGroupWorkspace(manager,Json.text(body,"entryRef"),
                Json.text(body,"scopeRef"),Json.text(body,"sessionWorkspaceRef")));
        });
    }
    @DeleteMapping("/group-workspaces/{grantRef}")
    ResponseEntity<ObjectNode> revokeGroupWorkspace(@PathVariable String serviceRef,@PathVariable String grantRef,
            @RequestHeader(value="Authorization",required=false)String authorization,
            @RequestHeader(value="X-Cowork-Delegation",required=false)String proof,@RequestBody JsonNode body) {
        return respond(()->{
            Json.onlyFields(body,"expectedRevision");
            if(!body.path("expectedRevision").isIntegralNumber()||!body.path("expectedRevision").canConvertToLong())
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,"Expected group grant revision is required");
            var manager=runtime.coworkManager(serviceRef,authorization,proof);
            return grantJson(runtime.l2().revokeGroupWorkspace(manager,grantRef,body.path("expectedRevision").asLong()));
        });
    }
    private ObjectNode grantJson(com.netlivecowork.channel.l2.L2Module.GroupWorkspaceGrant grant) {
        return Json.obj().put("grantRef",grant.grantRef()).put("scopeRef",grant.scopeRef())
            .put("entryRef",grant.entryRef()).put("sessionWorkspaceRef",grant.sessionWorkspaceRef())
            .put("ownerRef",grant.ownerRef()).put("revision",grant.revision()).put("active",grant.active());
    }
    private ObjectNode saved(SessionAccess.Saved saved) {
        var body=Json.obj().put("status","committed").put("requestId",saved.requestId()).put("revision",saved.revision());body.set("targetRefs",Json.mapper().valueToTree(saved.targetRefs()));return body;
    }
    private ResponseEntity<ObjectNode> respond(Supplier<ObjectNode> action) {
        try{return ResponseEntity.ok(action.get());}
        catch(RuntimeException error) {
            Throwable actual=error;while(actual instanceof java.util.concurrent.CompletionException && actual.getCause()!=null)actual=actual.getCause();
            var code=actual instanceof ChannelContractException c?c.code():ChannelErrorCode.OUTCOME_UNKNOWN;
            int status=switch(code){case AUTH_REQUIRED,IDENTITY_UNBOUND->401;case PERMISSION_DENIED->403;case GRANT_REVISION_CONFLICT,BINDING_CONFLICT,REQUEST_CONFLICT->409;case INVALID_ARGUMENT->400;case REQUEST_NOT_FOUND->404;default->503;};
            return ResponseEntity.status(status).body(Json.obj().put("status",code==ChannelErrorCode.OUTCOME_UNKNOWN?"unknown":"rejected").set("error",Json.obj().put("code",code.name()).put("message",actual instanceof ChannelContractException?actual.getMessage():"Cannot confirm the configuration operation; look up the original request")));
        }
    }
}
