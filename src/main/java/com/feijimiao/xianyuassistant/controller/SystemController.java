package com.feijimiao.xianyuassistant.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.common.ResultObject;
import jakarta.validation.Valid;
import com.feijimiao.xianyuassistant.controller.dto.ChangePasswordReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.CurrentUserRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.VersionInfoRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.SystemHealthRespDTO;
import com.feijimiao.xianyuassistant.entity.SysUser;
import com.feijimiao.xianyuassistant.service.AuthService;
import com.feijimiao.xianyuassistant.service.bo.ChangePasswordReqBO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 系统设置控制器
 * @author IAMLZY
 * @date 2026/4/22
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
public class SystemController {

    @Value("${app.version:3.4.10}")
    private String currentVersion;

    @Value("${app.update-check.enabled:false}")
    private boolean updateCheckEnabled;

    @Value("${app.update-check.api-url:}")
    private String updateApiUrl;

    /** GitHub 更新源仓库（owner/repository）。 */
    @Value("${app.update-check.github-repository:yuchen0x1/xianyu-seller-console}")
    private String updateGithubRepository = "yuchen0x1/xianyu-seller-console";

    /** 兼容旧配置的 Gitee 仓库；新默认不再使用 Gitee。 */
    @Value("${app.update-check.gitee-repository:}")
    private String updateGiteeRepository;

    /** 在线更新检查结果缓存时长，默认五分钟；设为 0 可关闭缓存。 */
    @Value("${app.update-check.cache-seconds:300}")
    private long updateCheckCacheSeconds = 300L;

    /** 合并并发更新检查，避免缓存失效瞬间重复访问公网。 */
    private final Object updateCheckCacheLock = new Object();
    private volatile UpdateCheckCacheEntry updateCheckCache;

    @Autowired
    private AuthService authService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.update.SelfUpdateService selfUpdateService;

    /**
     * 在线自更新状态（部署方式是否支持、当前更新进度）
     */
    @GetMapping("/selfUpdate/status")
    @com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission("settings:read")
    public ResultObject<com.feijimiao.xianyuassistant.controller.dto.SelfUpdateStatusDTO> selfUpdateStatus() {
        com.feijimiao.xianyuassistant.controller.dto.SelfUpdateStatusDTO dto =
                new com.feijimiao.xianyuassistant.controller.dto.SelfUpdateStatusDTO();
        dto.setAvailable(selfUpdateService.isAvailable());
        dto.setUnavailableReason(selfUpdateService.getUnavailableReason());
        var status = selfUpdateService.getStatus();
        dto.setPhase(status.phase());
        dto.setPercent(status.percent());
        dto.setDownloadedBytes(status.downloadedBytes());
        dto.setTotalBytes(status.totalBytes());
        dto.setTargetVersion(status.targetVersion());
        dto.setMessage(status.message());
        dto.setCanRestart(status.phase() != null
                && com.feijimiao.xianyuassistant.service.update.SelfUpdateService.PHASE_SWAPPED.equals(status.phase()));
        return ResultObject.success(dto);
    }

    /**
     * 开始在线更新到目标版本（下载 → SHA-256 校验 → 替换 JAR）
     */
    @PostMapping("/selfUpdate/apply")
    @com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission("settings:write")
    public ResultObject<?> applySelfUpdate(@RequestBody(required = false) Map<String, String> body) {
        String targetVersion = body == null ? null : body.get("targetVersion");
        boolean started = selfUpdateService.apply(targetVersion);
        if (started) {
            return ResultObject.success("开始下载新版本 v" + targetVersion);
        }
        var status = selfUpdateService.getStatus();
        String reason = selfUpdateService.isAvailable()
                ? (status.message() != null ? status.message()
                : "无法开始更新：目标版本无效或已有更新进行中（当前阶段 " + status.phase() + "）")
                : selfUpdateService.getUnavailableReason();
        return ResultObject.failed(reason);
    }

    /**
     * 新包就绪后执行重启（按 restart-mode 策略）
     */
    @PostMapping("/selfUpdate/restart")
    @com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission("settings:write")
    public ResultObject<?> restartSelfUpdate() {
        if (selfUpdateService.restartNow()) {
            return ResultObject.success("服务正在重启，页面将自动刷新");
        }
        return ResultObject.failed("当前没有可执行的重启（需先完成新包替换）");
    }

    /**
     * 获取当前用户信息
     */
    @PostMapping("/currentUser")
    public ResultObject<CurrentUserRespDTO> getCurrentUser(HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("currentUserId");
            if (userId == null) {
                return ResultObject.unauthorized(null);
            }
            SysUser user = authService.getCurrentUser(userId);
            if (user == null) {
                return ResultObject.failed("用户不存在");
            }
            CurrentUserRespDTO respDTO = new CurrentUserRespDTO();
            respDTO.setUsername(user.getUsername());
            respDTO.setLastLoginTime(user.getLastLoginTime() != null ? user.getLastLoginTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取当前用户信息失败", e);
            return ResultObject.failed("获取当前用户信息失败: " + e.getMessage());
        }
    }

    /**
     * 修改密码
     */
    @PostMapping("/changePassword")
    public ResultObject<?> changePassword(@Valid @RequestBody ChangePasswordReqDTO reqDTO, HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("currentUserId");
            if (userId == null) {
                return ResultObject.unauthorized(null);
            }
            // 参数校验
            if (reqDTO.getOldPassword() == null || reqDTO.getOldPassword().trim().isEmpty()) {
                return ResultObject.validateFailed("原密码不能为空");
            }
            if (reqDTO.getNewPassword() == null || reqDTO.getNewPassword().trim().isEmpty()) {
                return ResultObject.validateFailed("新密码不能为空");
            }
            if (reqDTO.getNewPassword().length() < 6 || reqDTO.getNewPassword().length() > 50) {
                return ResultObject.validateFailed("新密码长度需在6-50之间");
            }
            if (!reqDTO.getNewPassword().equals(reqDTO.getConfirmPassword())) {
                return ResultObject.validateFailed("两次密码不一致");
            }

            ChangePasswordReqBO reqBO = new ChangePasswordReqBO();
            reqBO.setUserId(userId);
            reqBO.setOldPassword(reqDTO.getOldPassword());
            reqBO.setNewPassword(reqDTO.getNewPassword());
            authService.changePassword(reqBO);

            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("修改密码失败", e);
            return ResultObject.failed(e.getMessage());
        }
    }

    @GetMapping("/version")
    public ResultObject<String> getVersion() {
        return ResultObject.success(currentVersion);
    }

    /**
     * 本机控制台的轻量健康探针。
     *
     * <p>该接口刻意不返回账号数、数据库路径、Cookie 或第三方连接状态，
     * 方便前端判断当前服务是否可达，同时避免将运行细节暴露给探针调用方。</p>
     */
    @GetMapping("/health")
    public ResultObject<SystemHealthRespDTO> getHealth() {
        SystemHealthRespDTO response = new SystemHealthRespDTO();
        response.setStatus("UP");
        response.setVersion(currentVersion);
        response.setTimestamp(System.currentTimeMillis());
        return ResultObject.success(response);
    }

    // 不设 settings:read 门控：AppLayout 对所有登录用户轮询新版本角标，
    // 403 会触发全局 toast；此接口仅返回版本信息，探测有固定 allowlist + 5 分钟缓存。
    @GetMapping("/checkUpdate")
    public ResultObject<VersionInfoRespDTO> checkUpdate() {
        String resolvedApiUrl = resolveUpdateApiUrl();
        UpdateCheckCacheKey cacheKey = new UpdateCheckCacheKey(
                updateCheckEnabled,
                resolvedApiUrl,
                currentVersion,
                updateCheckCacheSeconds);

        VersionInfoRespDTO cached = getCachedUpdate(cacheKey);
        if (cached != null) {
            return ResultObject.success(cached);
        }

        // The lock covers the network call deliberately: only one request repopulates
        // an expired cache entry while concurrent callers receive the same snapshot.
        synchronized (updateCheckCacheLock) {
            cached = getCachedUpdate(cacheKey);
            if (cached != null) {
                return ResultObject.success(copyVersionInfo(cached));
            }

            VersionInfoRespDTO response = fetchUpdateInfo(resolveUpdateApiUrl());
            cacheUpdate(cacheKey, response);
            return ResultObject.success(copyVersionInfo(response));
        }
    }

    private VersionInfoRespDTO fetchUpdateInfo(String resolvedApiUrl) {
        VersionInfoRespDTO respDTO = createNoUpdateResponse();
        respDTO.setChannel(UpdateChannel.GITHUB.label());
        if (!updateCheckEnabled || !hasText(resolvedApiUrl)) {
            respDTO.setCheckStatus("disabled");
            respDTO.setMessage("在线更新检查未启用");
            return respDTO;
        }

        try {
            URI updateUri = URI.create(resolvedApiUrl);
            if (!isAllowedUpdateUri(updateUri)) {
                log.warn("更新源协议或地址不受信任: scheme={}, host={}", updateUri.getScheme(), updateUri.getHost());
                respDTO.setCheckStatus("unavailable");
                respDTO.setMessage("更新源地址不受信任，请使用 HTTPS 地址");
                return respDTO;
            }

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest httpRequest = buildUpdateRequest(updateUri);
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("更新源请求失败, statusCode: {}", response.statusCode());
                respDTO.setLatestVersion(currentVersion);
                respDTO.setHasUpdate(false);
                respDTO.setCheckStatus("unavailable");
                int statusCode = response.statusCode();
                String message = switch (statusCode) {
                    case 401, 403 -> "无法访问 GitHub 更新仓库，请确认仓库权限或 Release 可见性";
                    case 404 -> "GitHub 更新仓库或 Release 不存在，请确认仓库地址和发布标签";
                    case 429 -> "GitHub API 请求频率受限，请稍后重试";
                    default -> statusCode >= 500 && statusCode <= 599
                            ? "GitHub 服务暂时不可用，请稍后重试"
                            : "GitHub 更新源返回了错误响应，请稍后重试";
                };
                respDTO.setMessage(message);
                return respDTO;
            }
            String responseBody = response.body();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseBody);
            if (root == null || !root.isObject()) {
                log.warn("更新源返回了无效的 JSON 响应");
                respDTO.setCheckStatus("unavailable");
                respDTO.setMessage("更新源响应格式无效，请稍后重试");
                return respDTO;
            }

            String tagName = root.path("tag_name").asText("");
            String latestVersion = normalizeVersion(tagName);
            String body = root.path("body").asText("");
            String publishedAt = root.path("published_at").asText("");
            // 部分 Release API 可能只带 created_at。
            if (!hasText(publishedAt)) {
                publishedAt = root.path("created_at").asText("");
            }
            String htmlUrl = root.path("html_url").asText("");

            if (latestVersion.isBlank()) {
                log.warn("更新源返回了无法识别的版本号");
                respDTO.setCheckStatus("unavailable");
                respDTO.setMessage("更新源返回的版本号无效");
            } else {
                respDTO.setLatestVersion(latestVersion);
                respDTO.setHasUpdate(compareVersion(latestVersion, currentVersion) > 0);
                respDTO.setUpdateContent(body);
                respDTO.setPublishedAt(publishedAt);
                respDTO.setDownloadUrl(resolveDownloadUrl(htmlUrl, tagName));
                respDTO.setCheckStatus("available");
                respDTO.setMessage("更新源检查成功");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("更新源返回了无效的 JSON 响应: errorType={}", e.getClass().getSimpleName());
            respDTO.setCheckStatus("unavailable");
            respDTO.setMessage("更新源响应格式无效，请稍后重试");
        } catch (java.io.IOException e) {
            log.warn("无法连接 GitHub 更新源: errorType={}", e.getClass().getSimpleName());
            respDTO.setCheckStatus("unavailable");
            respDTO.setMessage("无法连接 GitHub 更新源，请检查网络后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("检查 GitHub 更新源被中断: errorType={}", e.getClass().getSimpleName());
            respDTO.setCheckStatus("unavailable");
            respDTO.setMessage("无法连接 GitHub 更新源，请检查网络后重试");
        } catch (Exception e) {
            log.error("检查更新失败: errorType={}", e.getClass().getSimpleName());
            respDTO.setCheckStatus("unavailable");
            respDTO.setMessage("检查 GitHub 更新源失败，请稍后重试");
        }
        return respDTO;
    }

    private VersionInfoRespDTO createNoUpdateResponse() {
        VersionInfoRespDTO respDTO = new VersionInfoRespDTO();
        respDTO.setCurrentVersion(currentVersion);
        respDTO.setLatestVersion(currentVersion);
        respDTO.setHasUpdate(false);
        respDTO.setCheckStatus("disabled");
        return respDTO;
    }

    private HttpRequest buildUpdateRequest(URI updateUri) {
        return HttpRequest.newBuilder()
                .uri(updateUri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "XianYuAssistant")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
    }

    private VersionInfoRespDTO getCachedUpdate(UpdateCheckCacheKey cacheKey) {
        UpdateCheckCacheEntry cache = updateCheckCache;
        if (cache == null || !cache.key().equals(cacheKey)) return null;

        long cacheNanos = cacheNanos(cacheKey.cacheSeconds());
        if (cacheNanos <= 0) return null;
        long age = System.nanoTime() - cache.createdAtNanos();
        if (age < 0 || age >= cacheNanos) return null;
        return copyVersionInfo(cache.response());
    }

    private void cacheUpdate(UpdateCheckCacheKey cacheKey, VersionInfoRespDTO response) {
        if (cacheNanos(cacheKey.cacheSeconds()) <= 0) {
            updateCheckCache = null;
            return;
        }
        // Store a private immutable snapshot; callers never receive this DTO instance.
        updateCheckCache = new UpdateCheckCacheEntry(
                cacheKey,
                copyVersionInfo(response),
                System.nanoTime());
    }

    private long cacheNanos(long cacheSeconds) {
        if (cacheSeconds <= 0) return 0;
        return TimeUnit.SECONDS.toNanos(cacheSeconds);
    }

    private VersionInfoRespDTO copyVersionInfo(VersionInfoRespDTO source) {
        VersionInfoRespDTO copy = new VersionInfoRespDTO();
        copy.setCurrentVersion(source.getCurrentVersion());
        copy.setLatestVersion(source.getLatestVersion());
        copy.setHasUpdate(source.getHasUpdate());
        copy.setUpdateContent(source.getUpdateContent());
        copy.setPublishedAt(source.getPublishedAt());
        copy.setDownloadUrl(source.getDownloadUrl());
        copy.setChannel(source.getChannel());
        copy.setCheckStatus(source.getCheckStatus());
        copy.setMessage(source.getMessage());
        return copy;
    }

    /** 下载入口：仅返回受信任的 GitHub Release 页面地址。 */
    private String resolveDownloadUrl(String htmlUrl, String tagName) {
        String sanitized = sanitizeDownloadUrl(htmlUrl);
        if (hasText(sanitized)) {
            URI uri = URI.create(sanitized);
            if (isTrustedGithubPageUri(uri) || isTrustedGiteePageUri(uri)) return sanitized;
        }
        String repository = resolveRepositorySlug(updateGithubRepository);
        if (!hasText(repository)) return "";
        String releasesUrl = "https://github.com/" + repository + "/releases";
        return hasText(tagName) ? releasesUrl + "/tag/" + tagName : releasesUrl;
    }

    private String resolveUpdateApiUrl() {
        if (hasText(updateApiUrl)) {
            String configured = updateApiUrl.trim();
            Matcher githubReleaseMatcher = GITHUB_RELEASE_URL.matcher(configured);
            if (githubReleaseMatcher.matches()) return GITHUB_API_PREFIX + githubReleaseMatcher.group(1) + "/releases/latest";
            Matcher githubTagMatcher = GITHUB_TAG_URL.matcher(configured);
            if (githubTagMatcher.matches()) return GITHUB_API_PREFIX + githubTagMatcher.group(1) + "/releases/tags/" + githubTagMatcher.group(2);
            Matcher giteeReleaseMatcher = GITEE_RELEASE_URL.matcher(configured);
            if (giteeReleaseMatcher.matches()) return GITEE_API_PREFIX + giteeReleaseMatcher.group(1) + "/releases/latest";
            Matcher giteeTagMatcher = GITEE_TAG_URL.matcher(configured);
            if (giteeTagMatcher.matches()) return GITEE_API_PREFIX + giteeTagMatcher.group(1) + "/releases/tags/" + giteeTagMatcher.group(2);
            try {
                URI custom = URI.create(configured);
                return (isTrustedGithubApiUri(configured) || isTrustedGiteeApiUri(configured)
                        || ("http".equalsIgnoreCase(custom.getScheme()) && isLoopbackHost(normalizeHost(custom))))
                        ? configured : "";
            } catch (IllegalArgumentException e) {
                return "";
            }
        }
        String repository = resolveRepositorySlug(updateGithubRepository);
        if (hasText(repository)) return GITHUB_API_PREFIX + repository + "/releases/latest";
        String giteeRepository = resolveGiteeRepositorySlug(updateGiteeRepository);
        return hasText(giteeRepository) ? GITEE_API_PREFIX + giteeRepository + "/releases/latest" : "";
    }

    /** 归一化 owner/repository 配置，仅接受 GitHub slug 或 github.com 地址。 */
    private String resolveRepositorySlug(String configValue) {
        if (!hasText(configValue)) return null;
        String repository = configValue.trim()
                .replaceFirst("(?i)^https?://github\\.com/", "")
                .replaceFirst("^/+", "")
                .replaceFirst("\\.git$", "")
                .replaceAll("/+$", "")
                .replaceFirst("(?i)/releases(?:/latest|/tag/[^/]+)?$", "");
        return repository.matches("[^/\\s]+/[^/\\s]+") ? repository : null;
    }

    private boolean isTrustedGithubApiUri(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                    && "api.github.com".equals(normalizeHost(uri))
                    && uri.getPath() != null && uri.getPath().matches("/repos/[^/\\s]+/[^/\\s]+/releases(?:/latest|/tags/[^/\\s]+)");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isTrustedGiteeApiUri(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                    && "gitee.com".equals(normalizeHost(uri))
                    && uri.getPath() != null && uri.getPath().matches("/api/v5/repos/[^/\\s]+/[^/\\s]+/releases(?:/latest|/tags/[^/\\s]+)");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String resolveGiteeRepositorySlug(String configValue) {
        if (!hasText(configValue)) return null;
        String repository = configValue.trim()
                .replaceFirst("(?i)^https?://gitee\\.com/", "")
                .replaceFirst("^/+", "")
                .replaceFirst("\\.git$", "")
                .replaceAll("/+$", "")
                .replaceFirst("(?i)/releases(?:/latest|/tag/[^/]+)?$", "");
        return repository.matches("[^/\\s]+/[^/\\s]+") ? repository : null;
    }

    private boolean isTrustedGithubPageUri(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                && "github.com".equals(normalizeHost(uri));
    }

    private boolean isTrustedGiteePageUri(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                && "gitee.com".equals(normalizeHost(uri));
    }

    private boolean isAllowedUpdateUri(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getUserInfo() != null) return false;
        if ("http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(normalizeHost(uri))) return true;
        return "https".equalsIgnoreCase(uri.getScheme())
                && (isTrustedGithubApiUri(uri.toString()) || isTrustedGiteeApiUri(uri.toString()));
    }

    private String normalizeHost(URI uri) {
        if (uri == null || !hasText(uri.getHost())) return "";
        String host = uri.getHost().trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private boolean isLoopbackHost(String host) {
        if (!hasText(host)) return false;
        if ("localhost".equalsIgnoreCase(host) || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host)) {
            return true;
        }

        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) return false;
        for (String octet : octets) {
            if (!octet.matches("\\d{1,3}")) return false;
            try {
                if (Integer.parseInt(octet) > 255) return false;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }

    private String sanitizeDownloadUrl(String value) {
        if (!hasText(value)) return "";
        try {
            URI uri = URI.create(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) ? uri.toString() : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** GitHub Release API 前缀；自定义 api-url 仅允许同源 API 的 releases 路径。 */
    private static final String GITHUB_API_PREFIX = "https://api.github.com/repos/";
    private static final String GITEE_API_PREFIX = "https://gitee.com/api/v5/repos/";
    private static final String DEFAULT_GITHUB_REPOSITORY = "yuchen0x1/xianyu-seller-console";
    private static final Pattern GITHUB_RELEASE_URL = Pattern.compile(
            "https?://github\\.com/([^/\\s]+/[^/\\s]+)/releases/latest/?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_TAG_URL = Pattern.compile(
            "https?://github\\.com/([^/\\s]+/[^/\\s]+)/releases/tag/([^/\\s?#]+)/*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GITEE_RELEASE_URL = Pattern.compile("https?://gitee\\.com/([^/\\s]+/[^/\\s]+)/releases/latest/?", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITEE_TAG_URL = Pattern.compile("https?://gitee\\.com/([^/\\s]+/[^/\\s]+)/releases/tag/([^/\\s?#]+)/*", Pattern.CASE_INSENSITIVE);

    private String normalizeVersion(String raw) {
        if (!hasText(raw)) return "";
        String value = raw.trim().replaceFirst("^[vV]\\.?", "");
        Matcher matcher = Pattern.compile("^(\\d+(?:\\.\\d+){0,3})").matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private int compareVersion(String v1, String v2) {
        String normalized1 = normalizeVersion(v1);
        String normalized2 = normalizeVersion(v2);
        if (normalized1.isBlank() || normalized2.isBlank()) return 0;
        String[] parts1 = normalized1.split("\\.");
        String[] parts2 = normalized2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }
        return 0;
    }

    private int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Cache identity excludes secrets by design; the key only carries non-sensitive config. */
    private record UpdateCheckCacheKey(
            boolean enabled,
            String resolvedApiUrl,
            String currentVersion,
            long cacheSeconds) {
    }

    private record UpdateCheckCacheEntry(
            UpdateCheckCacheKey key,
            VersionInfoRespDTO response,
            long createdAtNanos) {
    }

    /** 更新源通道；响应中以小写标签返回。 */
    private enum UpdateChannel {
        GITHUB("github");

        private final String label;

        UpdateChannel(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }
}
