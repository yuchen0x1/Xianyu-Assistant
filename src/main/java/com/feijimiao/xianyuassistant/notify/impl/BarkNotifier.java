package com.feijimiao.xianyuassistant.notify.impl;

import com.feijimiao.xianyuassistant.notify.NotifyChannel;
import com.feijimiao.xianyuassistant.notify.NotifyMessage;
import com.feijimiao.xianyuassistant.notify.Notifier;
import com.feijimiao.xianyuassistant.service.SysSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Bark 通知（iOS 推送）。
 *
 * <p>Bark 服务端：{@code https://api.day.app/{deviceKey}/{title}/{content}}，
 * 支持自定义服务器地址（如自托管 Bark）。</p>
 */
@Slf4j
@Component
public class BarkNotifier implements Notifier {

    private static final String KEY_BARK_DEVICE_KEY = "notify_bark_device_key";
    private static final String KEY_BARK_SERVER = "notify_bark_server";

    @Autowired
    private SysSettingService sysSettingService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public boolean send(NotifyMessage message) {
        String deviceKey = getDeviceKey();
        if (deviceKey.isEmpty()) {
            log.warn("[Bark] Bark设备Key未配置，跳过通知");
            return false;
        }

        try {
            String baseUrl = getServerUrl();
            String levelParam = switch (message.getLevel()) {
                case ERROR -> "?level=timeSensitive";
                case WARNING -> "?level=active";
                case INFO -> "";
            };

            String url = baseUrl + "/" + URLEncoder.encode(deviceKey, StandardCharsets.UTF_8)
                    + "/" + URLEncoder.encode(message.getTitle(), StandardCharsets.UTF_8)
                    + "/" + URLEncoder.encode(message.getContent(), StandardCharsets.UTF_8)
                    + levelParam;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() == 200;
            if (success) {
                log.info("[Bark] Bark通知发送成功: title={}", message.getTitle());
            } else {
                log.warn("[Bark] Bark通知发送失败: status={}, body={}", response.statusCode(), response.body());
            }
            return success;
        } catch (Exception e) {
            log.error("[Bark] Bark通知发送异常: title={}", message.getTitle(), e);
            return false;
        }
    }

    @Override
    public NotifyChannel getChannel() {
        return NotifyChannel.BARK;
    }

    @Override
    public boolean isConfigured() {
        return !getDeviceKey().isEmpty();
    }

    private String getDeviceKey() {
        String value = sysSettingService.getSettingValue(KEY_BARK_DEVICE_KEY);
        return value != null ? value.trim() : "";
    }

    private String getServerUrl() {
        String value = sysSettingService.getSettingValue(KEY_BARK_SERVER);
        if (value != null && !value.isBlank()) {
            return value.trim().replaceAll("/+$", "");
        }
        return "https://api.day.app";
    }
}
