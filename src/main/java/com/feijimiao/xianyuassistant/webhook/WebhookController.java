package com.feijimiao.xianyuassistant.webhook;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.webhook.dto.WebhookDeliveryVO;
import com.feijimiao.xianyuassistant.webhook.dto.WebhookVO;
import com.feijimiao.xianyuassistant.webhook.entity.WebhookConfig;
import com.feijimiao.xianyuassistant.webhook.service.WebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    @GetMapping
    @RequirePermission("webhook:read")
    public ResultObject<List<WebhookVO>> list() {
        return ResultObject.success(webhookService.listAll());
    }

    @GetMapping("/{id}")
    @RequirePermission("webhook:read")
    public ResultObject<WebhookVO> get(@PathVariable Long id) {
        return ResultObject.success(webhookService.getById(id));
    }

    @PostMapping
    @RequirePermission("webhook:write")
    public ResultObject<WebhookVO> create(@RequestBody WebhookConfig config) {
        return ResultObject.success(webhookService.create(config));
    }

    @PutMapping
    @RequirePermission("webhook:write")
    public ResultObject<Void> update(@RequestBody WebhookConfig config) {
        webhookService.update(config);
        return ResultObject.success(null);
    }

    @DeleteMapping("/{id}")
    @RequirePermission("webhook:write")
    public ResultObject<Void> delete(@PathVariable Long id) {
        webhookService.delete(id);
        return ResultObject.success(null);
    }

    @PostMapping("/{id}/toggle")
    @RequirePermission("webhook:write")
    public ResultObject<Void> toggle(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        webhookService.toggle(id, body.getOrDefault("enabled", true));
        return ResultObject.success(null);
    }

    @PostMapping("/test")
    @RequirePermission("webhook:write")
    public ResultObject<Boolean> test(@RequestBody Map<String, String> body) {
        Long id = body.get("id") == null || body.get("id").isBlank() ? null : Long.valueOf(body.get("id"));
        boolean ok = webhookService.test(body.get("url"), body.get("secret"), id);
        return ResultObject.success(ok);
    }

    @GetMapping("/{id}/deliveries")
    @RequirePermission("webhook:read")
    public ResultObject<List<WebhookDeliveryVO>> deliveries(
            @PathVariable Long id,
            @RequestParam(defaultValue = "50") int limit) {
        return ResultObject.success(webhookService.listDeliveries(id, limit));
    }
}
