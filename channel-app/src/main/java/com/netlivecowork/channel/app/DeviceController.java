package com.netlivecowork.channel.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
final class DeviceController {
    private final ChannelRuntime runtime;
    DeviceController(ChannelRuntime runtime){this.runtime=runtime;}
    @PostMapping("/api/v1/channel/devices/enrollments")
    ResponseEntity<ObjectNode> authorize(@RequestBody JsonNode body){return respond(()->{
        Json.onlyFields(body,"requestId","serviceRef","deviceRef");
        var ticket=runtime.l2().issueDeviceEnrollment(Json.text(body,"requestId"),Json.text(body,"serviceRef"),Json.text(body,"deviceRef"));
        return Json.obj().put("deviceRef",ticket.deviceRef()).put("ticket",ticket.ticket()).put("expiresAt",ticket.expiresAt().toString());
    });}
    @PostMapping("/api/v1/l3/devices/enroll")
    ResponseEntity<ObjectNode> enroll(@RequestBody JsonNode body){return respond(()->{
        Json.onlyFields(body,"ticket","exchangeSecret");
        var value=runtime.l3().devices().redeem(Json.text(body,"ticket"),Json.text(body,"exchangeSecret"));
        return Json.obj().put("deviceRef",value.deviceRef()).put("token",value.token());
    });}
    @GetMapping("/api/v1/channel/devices/{deviceRef}")
    ResponseEntity<ObjectNode> status(@PathVariable String deviceRef){return respond(()->Json.obj().put("deviceRef",deviceRef).put("state",runtime.l3().connectors().state(deviceRef)));}
    @PostMapping("/api/v1/channel/devices/{deviceRef}/revoke")
    ResponseEntity<ObjectNode> revoke(@PathVariable String deviceRef){return respond(()->{runtime.l2().revokeDevice(deviceRef);return Json.obj().put("deviceRef",deviceRef).put("state","revoked");});}
    private ResponseEntity<ObjectNode> respond(Supplier<ObjectNode> work){
        try{return ResponseEntity.ok(work.get());}catch(RuntimeException e){
            Throwable error=e;while(error instanceof java.util.concurrent.CompletionException&&error.getCause()!=null)error=error.getCause();
            ChannelErrorCode code=error instanceof ChannelContractException c?c.code():ChannelErrorCode.UNAVAILABLE;
            int status=switch(code){case AUTH_REQUIRED->401;case PERMISSION_DENIED->403;case REQUEST_CONFLICT->409;case INVALID_ARGUMENT->400;default->503;};
            return ResponseEntity.status(status).body(Json.obj().set("error",Json.obj().put("code",code.name()).put("message",error instanceof ChannelContractException?error.getMessage():"Device operation could not be confirmed")));
        }
    }
}
