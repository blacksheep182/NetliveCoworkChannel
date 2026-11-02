package com.netlivecowork.channel.app;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.json.Json;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/** External adapters consume granted sessions. Configuration belongs exclusively to CoWork. */
@RestController
@RequestMapping("/web/{entryRef}/access")
final class UserSessionAccessController {
    @RequestMapping(value={"","/requests/{requestId}"},method={RequestMethod.GET,RequestMethod.POST})
    ResponseEntity<ObjectNode> closed() {
        return ResponseEntity.status(403).body(Json.obj().put("status","rejected").set("error",Json.obj().put("code","PERMISSION_DENIED").put("message","请在 CoWork 客户端的设置 → Channel 接入中配置可访问会话")));
    }
}
