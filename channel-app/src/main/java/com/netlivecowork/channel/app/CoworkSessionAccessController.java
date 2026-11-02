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
