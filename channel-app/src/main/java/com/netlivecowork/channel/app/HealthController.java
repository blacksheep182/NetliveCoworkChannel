package com.netlivecowork.channel.app;

import com.netlivecowork.channel.contract.json.Json;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 存活检查：不返回配置、凭证或内部地址。 */
@RestController
final class HealthController {

    @GetMapping("/health")
    Map<String, Object> health() {
        return Json.mapper().convertValue(Json.obj().put("status", "ok")
            .put("service", "netlivecowork-channel"), Map.class);
    }
}
