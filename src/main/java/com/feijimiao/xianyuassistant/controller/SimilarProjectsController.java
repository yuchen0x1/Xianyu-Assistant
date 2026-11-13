package com.feijimiao.xianyuassistant.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同类资源：收录与本系统定位相近的开源项目，检测其上游更新。
 * 项目清单固定在服务端（不接受前端传入的 URL，避免 SSRF），
 * 更新检测结果按清单整体缓存 10 分钟。
 */
@Slf4j
@RestController
@RequestMapping("/api/similar-projects")
@RequirePermission("settings:read")
public class SimilarProjectsController {

    /** 更新检测结果缓存时间（秒） */
    private static final long CACHE_SECONDS = 600;
    private static final String GITHUB_RELEASE_API = "https://api.github.com/repos/%s/releases/latest";
    private static final String GITHUB_REPO_API = "https://api.github.com/repos/%s";

    /**
     * useCommitDate=true 的项目没有版本化 Release，用仓库最近 push 日期判断是否有更新。
     * knownVersion/knownDate 为清单整理时的基线。
     */
    private static final List<Entry> PROJECTS = List.of(
            entry("XianyuAutoAgent", "shaxiu/XianyuAutoAgent", "github",
                    "闲鱼智能客服机器人（多专家协同决策），8.9k star", null, "2026-06-10", true),
            entry("xianyu-auto-reply", "zhinianboke/xianyu-auto-reply", "github",
                    "Python+FastAPI 自动化客服系统，6.9k star", null, "2026-09-01", true),
            entry("xianyu-auto-reply-fix", "GuDong2003/xianyu-auto-reply-fix", "github",
                    "上者的社区修复版：多账号/AI回复/自动发货", "v2.0.7", "2026-08-22", false),
            entry("Ydisks-Xianyu-Helper", "Christ9038/Ydisks-Xianyu-Helper", "github",
                    "Go+React 多账号自动发货、消息回复系统", "v1.0.7", "2026-09-01", false),
            entry("XianYuApis", "cv-cat/XianYuApis", "github",
                    "闲鱼协议逆向与 API/WS 基座", "goofish", "2026-04-06", false),
            entry("xianyu-super-butler", "23Star/xianyu-super-butler", "github",
                    "闲鱼超级管家：滑块/发货/评价/擦亮", "v3.1.0", "2026-08-16", false),
            entry("XianYu Assistant (当前发布仓库)", "yuchen0x1/Xianyu-Assistant", "github",
                    "当前项目的公开发布仓库；用于核对本地功能同步后的发布版本", "v3.5.24", "2026-11-13", false)
    );

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Map<String, CachedResult> checkCache = new ConcurrentHashMap<>();

    @Autowired
    public SimilarProjectsController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static Entry entry(String name, String repo, String platform, String description,
                               String knownVersion, String knownDate, boolean useCommitDate) {
        return new Entry(name, repo, platform, description, knownVersion, knownDate, useCommitDate);
    }

    @GetMapping("/list")
    public ResultObject<List<Map<String, Object>>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Entry p : PROJECTS) {
            result.add(Map.of(
                    "name", p.name,
                    "repo", p.repo,
                    "platform", p.platform,
                    "description", p.description,
                    "url", "https://github.com/" + p.repo,
                    "knownVersion", p.knownVersion == null ? "" : p.knownVersion,
                    "knownDate", p.knownDate,
                    "useCommitDate", p.useCommitDate
            ));
        }
        return ResultObject.success(result);
    }

    @GetMapping("/check")
    public ResultObject<List<Map<String, Object>>> check(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean force) {
        CachedResult cached = checkCache.get("all");
        if (!force && cached != null && cached.freshUntil() > System.currentTimeMillis()) {
            return ResultObject.success(cached.data());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Entry p : PROJECTS) {
            result.add(checkOne(p));
        }
        checkCache.put("all", new CachedResult(result, System.currentTimeMillis() + CACHE_SECONDS * 1000));
        return ResultObject.success(result);
    }

    private Map<String, Object> checkOne(Entry p) {
        String latestVersion = null;
        String latestDate = null;
        String status = "ok";
        try {
            if (p.useCommitDate) {
                JsonNode repo = fetchJson(String.format(GITHUB_REPO_API, p.repo));
                latestDate = repo.path("pushed_at").asText("").substring(0, Math.min(10, repo.path("pushed_at").asText("").length()));
                latestVersion = latestDate;
            } else {
                JsonNode release = fetchJson(String.format(GITHUB_RELEASE_API, p.repo));
                latestVersion = release.path("tag_name").asText(null);
                String publishedAt = release.path("published_at").asText("");
                latestDate = publishedAt.isEmpty() ? "" : publishedAt.substring(0, 10);
                if (latestVersion == null || latestVersion.isBlank()) {
                    // 没有 Release 时退化为 push 日期
                    JsonNode repo = fetchJson(String.format(GITHUB_REPO_API, p.repo));
                    latestDate = repo.path("pushed_at").asText("").substring(0, 10);
                    latestVersion = "commit " + latestDate;
                }
            }
        } catch (Exception e) {
            log.warn("同类资源更新检测失败: repo={}, errorType={}", p.repo, e.getClass().getSimpleName());
            status = "unavailable";
        }

        boolean hasUpdate = false;
        if ("ok".equals(status)) {
            if (p.useCommitDate) {
                hasUpdate = latestDate != null && latestDate.compareTo(p.knownDate) > 0;
            } else {
                hasUpdate = latestVersion != null && !latestVersion.equals(p.knownVersion);
            }
        }
        return Map.of(
                "name", p.name,
                "repo", p.repo,
                "url", "https://github.com/" + p.repo,
                "description", p.description,
                "knownVersion", p.knownVersion == null ? "" : p.knownVersion,
                "knownDate", p.knownDate,
                "latestVersion", latestVersion == null ? "" : latestVersion,
                "latestDate", latestDate == null ? "" : latestDate,
                "hasUpdate", hasUpdate,
                "status", status
        );
    }

    private JsonNode fetchJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "xianyu-seller-console")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub API " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private record Entry(String name, String repo, String platform, String description,
                         String knownVersion, String knownDate, boolean useCommitDate) {
    }

    private record CachedResult(List<Map<String, Object>> data, long freshUntil) {
    }
}
