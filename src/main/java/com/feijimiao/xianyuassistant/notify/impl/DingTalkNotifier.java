package com.feijimiao.xianyuassistant.notify.impl;

import com.feijimiao.xianyuassistant.notify.NotifyChannel;
import com.feijimiao.xianyuassistant.notify.NotifyMessage;
import com.feijimiao.xianyuassistant.notify.Notifier;
import com.feijimiao.xianyuassistant.service.SysSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钉钉机器人通知
 * 支持加签安全设置
 */
@Slf4j
@Component
public class DingTalkNotifier implements Notifier {

    private static final String KEY_DINGTALK_WEBHOOK = "notify_dingtalk_webhook";
    private static final String KEY_DINGTALK_SECRET = "notify_dingtalk_secret";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private SysSettingService sysSettingService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public boolean send(NotifyMessage message) {
        String webhookUrl = getWebhookUrl();
        if (webhookUrl.isEmpty()) {
            log.warn("[DingTalk] 钉钉Webhook未配置，跳过通知");
            return false;
        }

        try {
            // 如果配置了加签密钥，生成签名
            String secret = getSecret();
            if (!secret.isEmpty()) {
                webhookUrl = buildSignedUrl(webhookUrl, secret);
            }

            String color = switch (message.getLevel()) {
                case ERROR -> "#FF0000";
                case WARNING -> "#FFA500";
                case INFO -> "#1E90FF";
            };

            String markdown = String.format(
                    "## %s  \n---  \n%s  \n\n> 级别：<font color=\"%s\">%s</font>",
                    message.getTitle(),
                    message.getContent().replace("\n", "  \n"),
                    color,
                    message.getLevel().name()
            );

            // Jackson 序列化：手写 escapeJson 不处理 <0x20 控制字符，消息含控制字符时 payload 非法
            Map<String, Object> markdownBody = new LinkedHashMap<>();
            markdownBody.put("title", message.getTitle());
            markdownBody.put("text", markdown);
            Map<String, Object> payloadBody = new LinkedHashMap<>();
            payloadBody.put("msgtype", "markdown");
            payloadBody.put("markdown", markdownBody);
            String payload = OBJECT_MAPPER.writeValueAsString(payloadBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() == 200;
            if (success) {
                log.info("[DingTalk] 钉钉通知发送成功: title={}", message.getTitle());
            } else {
                log.warn("[DingTalk] 钉钉通知发送失败: status={}, body={}", response.statusCode(), response.body());
            }
            return success;
        } catch (Exception e) {
            log.error("[DingTalk] 钉钉通知发送异常: title={}", message.getTitle(), e);
            return false;
        }
    }

    @Override
    public NotifyChannel getChannel() {
        return NotifyChannel.DINGTALK;
    }

    @Override
    public boolean isConfigured() {
        return !getWebhookUrl().isEmpty();
    }

    private String getWebhookUrl() {
        String value = sysSettingService.getSettingValue(KEY_DINGTALK_WEBHOOK);
        return value != null ? value.trim() : "";
    }

    private String getSecret() {
        String value = sysSettingService.getSettingValue(KEY_DINGTALK_SECRET);
        return value != null ? value.trim() : "";
    }

    private String buildSignedUrl(String webhookUrl, String secret)
            throws NoSuchAlgorithmException, InvalidKeyException, java.io.UnsupportedEncodingException {
        long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] signBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signBytes), "UTF-8");
        return webhookUrl + "&timestamp=" + timestamp + "&sign=" + sign;
    }
}
