package com.netlivecowork.channel.app;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.l3.IngestResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后端事件回传入口（Agent → L3）。接收端按受信登记解析，不接受任意地址（INV-64）。
 * 只有 L3 持久接收并登记可恢复转交后才确认；确认不等待平台投递（INV-31）。
 */
@RestController
final class L3EventController {

    private final ChannelRuntime runtime;

    L3EventController(ChannelRuntime runtime) {
        this.runtime = runtime;
    }

    @PostMapping(path = "/api/v1/l3/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ObjectNode> receive(@RequestHeader(name = "X-Event-Sink-Ref", required = false) String sinkRef,
                                       @RequestHeader(name = "X-Backend-Resource-Epoch", required = false)
                                       String epoch,
                                       @RequestHeader(name = "X-Event-Sink-Revision", required = false)
                                       String revision,
                                       @RequestHeader(name = "Authorization", required = false)
                                       String authorization,
                                       @RequestBody String body) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        if (sinkRef == null || !sinkRef.matches("[A-Za-z0-9._~-]{1,128}")
            || !positiveInteger(epoch) || !positiveInteger(revision)) {
            return ResponseEntity.badRequest().body(Json.obj().set("error", Json.obj()
                .put("code", "INVALID_REQUEST").put("message", "Callback source headers are required")
                .put("retryable", false)));
        }
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Json.obj().set("error", Json.obj()
                .put("code", "CONTENT_TOO_LARGE").put("message", "Event exceeds configured limit")
                .put("retryable", false)));
        }
        IngestResult result = runtime.l3().events().ingest(sinkRef, token,
            Long.parseLong(epoch), Long.parseLong(revision), body);
        if (result.accepted()) {
            return ResponseEntity.ok().body(Json.obj()
                .put("event_id", result.eventId() == null ? "" : result.eventId())
                .put("status", "accepted"));
        }
        ChannelErrorCode code = result.error().errorCode();
        HttpStatus status = switch (code) {
            case AUTH_REQUIRED, ACCOUNT_MISMATCH -> HttpStatus.UNAUTHORIZED;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case REQUEST_CONFLICT, INTERACTION_STALE -> HttpStatus.CONFLICT;
            case INVALID_ARGUMENT, UNSUPPORTED_VERSION -> HttpStatus.BAD_REQUEST;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        ObjectNode error = Json.obj().put("code", result.error().code())
            .put("message", result.error().message()).put("retryable", status.is5xxServerError());
        return ResponseEntity.status(status).body(Json.obj().set("error", error));
    }

    private static boolean positiveInteger(String value) {
        if (value == null || !value.matches("[0-9]{1,16}")) return false;
        long parsed = Long.parseLong(value);
        return parsed >= 1 && parsed <= 9_007_199_254_740_991L;
    }
}
