package com.feijimiao.xianyuassistant.service.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.common.VersionUtils;
import com.feijimiao.xianyuassistant.controller.dto.VersionInfoRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在线更新检查服务：查询 GitHub/Gitee Release 最新版本并对比本地版本。
 *
 * <p>请求目标有固定 HTTPS 主机白名单（api.github.com / gitee.com，本机 loopback 放行用于测试），
 * 结果带内存缓存（默认 5 分钟）且仅缓存非敏感信息。供 {@code SystemController /checkUpdate}
 * 与定时自动更新调度器共用。</p>
 *
 * @author IAMLZY
 */
@Slf4j
@Service
public class UpdateCheckService {

    @Value("${app.version:3.5.24}")
    private String currentVersion;

    @Value("${app.update-check.enabled:false}")
    private boolean updateCheckEnabled;

    @Value("${app.update-check.api-url:}")
    private String updateApiUrl;

    /** GitHub 更新源仓库（owner/repository）。 */
    @Value("${app.update-check.github-repository:yuchen0x1/Xianyu-Assistant}")
    private String updateGithubRepository = "yuchen0x1/Xianyu-Assistant";

    /** 兼容旧配置的 Gitee 仓库；新默认不再使用 Gitee。 */
    @Value("${app.update-check.gitee-repository:}")
    private String updateGiteeRepository;

    /** 在线更新检查结果缓存时长，默认五分钟；设为 0 可关闭缓存。 */
    @Value("${app.update-check.cache-seconds:300}")
    private long updateCheckCacheSeconds = 300L;

    /** 更新通道：stable=稳定版，beta=仅预发布版，dev=最新稳定版或预发布版。 */
    @Value("${app.update-check.channel:stable}")
    private String updateChannel = "stable";

    /** 合并并发更新检查，避免缓存失效瞬间重复访问公网。 */
    private final Object updateCheckCacheLock = new Object();
    private volatile UpdateCheckCacheEntry updateCheckCache;

    /**
     * 检查更新：返回最新版本信息与是否存在更新；网络异常、更新源不可用时返回
     * checkStatus=unavailable 的降级响应，绝不抛出异常。
     */
    public VersionInfoRespDTO checkUpdate() {
        String resolvedApiUrl = resolveUpdateApiUrl();
        UpdateCheckCacheKey cacheKey = new UpdateCheckCacheKey(
                updateCheckEnabled,
                resolvedApiUrl,
                currentVersion,
                updateCheckCacheSeconds,
                normalizeUpdateChannel());

        VersionInfoRespDTO cached = getCachedUpdate(cacheKey);
        if (cached != null) {
            return cached;
        }

        // The lock covers the network call deliberately: only one request repopulates
        // an expired cache entry while concurrent callers receive the same snapshot.
        synchronized (updateCheckCacheLock) {
            cached = getCachedUpdate(cacheKey);
            if (cached != null) {
                return copyVersionInfo(cached);
            }

            VersionInfoRespDTO response = fetchUpdateInfo(resolveUpdateApiUrl());
            cacheUpdate(cacheKey, response);
            return copyVersionInfo(response);
        }
    }

    private VersionInfoRespDTO fetchUpdateInfo(String resolvedApiUrl) {
        VersionInfoRespDTO respDTO = createNoUpdateResponse();
        respDTO.setChannel(UpdateChannel.GITHUB.label());
        respDTO.setReleaseChannel(normalizeUpdateChannel());
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
            JsonNode release = selectRelease(root);
            if (release == null) {
                if (root != null && root.isArray()) {
                    respDTO.setLatestVersion(currentVersion);
                    respDTO.setHasUpdate(false);
                    respDTO.setCheckStatus("available");
                    respDTO.setMessage("当前更新通道暂无可用 Release");
                    return respDTO;
                }
                log.warn("更新源返回了无效的 JSON 响应");
                respDTO.setCheckStatus("unavailable");
                respDTO.setMessage("更新源响应格式无效，请稍后重试");
                return respDTO;
            }

            String tagName = release.path("tag_name").asText("");
            String latestVersion = normalizeVersion(tagName);
            String body = release.path("body").asText("");
            String publishedAt = release.path("published_at").asText("");
            // 部分 Release API 可能只带 created_at。
            if (!hasText(publishedAt)) {
                publishedAt = release.path("created_at").asText("");
            }
            String htmlUrl = release.path("html_url").asText("");
            populateReleaseMetadata(respDTO, release);

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
        copy.setAssetName(source.getAssetName());
        copy.setAssetSize(source.getAssetSize());
        copy.setPrerelease(source.getPrerelease());
        copy.setMandatory(source.getMandatory());
        copy.setSecurityFix(source.getSecurityFix());
        copy.setReleaseChannel(source.getReleaseChannel());
        copy.setChannel(source.getChannel());
        copy.setCheckStatus(source.getCheckStatus());
        copy.setMessage(source.getMessage());
        return copy;
    }

    private void populateReleaseMetadata(VersionInfoRespDTO response, JsonNode release) {
        JsonNode assets = release.path("assets");
        JsonNode jar = null;
        if (assets.isArray()) {
            for (JsonNode asset : assets) {
                String name = asset.path("name").asText("");
                if (name.toLowerCase(Locale.ROOT).endsWith(".jar")
                        && !name.toLowerCase(Locale.ROOT).endsWith(".jar.sha256")
                        && !name.toLowerCase(Locale.ROOT).endsWith(".jar.sig")
                        && (jar == null || asset.path("size").asLong(0) > jar.path("size").asLong(0))) {
                    jar = asset;
                }
            }
        }
        if (jar != null) {
            response.setAssetName(jar.path("name").asText(""));
            response.setAssetSize(jar.path("size").asLong(0));
        }
        response.setPrerelease(release.path("prerelease").asBoolean(false));
        String body = release.path("body").asText("");
        response.setMandatory(hasReleaseMarker(body, "mandatory"));
        response.setSecurityFix(hasReleaseMarker(body, "security-fix"));
    }

    private boolean hasReleaseMarker(String body, String marker) {
        if (!hasText(body)) return false;
        if (body.matches("(?is).*<!--\\s*" + Pattern.quote(marker) + "\\s*-->.*")) {
            return true;
        }
        Pattern lineMarker = Pattern.compile("^\\s*\\[" + Pattern.quote(marker) + "\\]\\s*$",
                Pattern.CASE_INSENSITIVE);
        return body.lines().anyMatch(line -> lineMarker.matcher(line).matches());
    }

    private JsonNode selectRelease(JsonNode root) {
        if (root == null) return null;
        if (root.isObject()) {
            return isReleaseCandidate(root) ? root : null;
        }
        if (!root.isArray()) return null;

        JsonNode best = null;
        String bestVersion = "";
        String bestPublishedAt = "";
        for (JsonNode candidate : root) {
            if (!isReleaseCandidate(candidate)) continue;
            String version = normalizeVersion(candidate.path("tag_name").asText(""));
            String publishedAt = candidate.path("published_at").asText("");
            if (!hasText(publishedAt)) publishedAt = candidate.path("created_at").asText("");
            if (best == null || compareVersion(version, bestVersion) > 0
                    || (compareVersion(version, bestVersion) == 0 && publishedAt.compareTo(bestPublishedAt) > 0)) {
                best = candidate;
                bestVersion = version;
                bestPublishedAt = publishedAt;
            }
        }
        return best;
    }

    private boolean isReleaseCandidate(JsonNode release) {
        if (release == null || !release.isObject() || release.path("draft").asBoolean(false)) return false;
        String version = normalizeVersion(release.path("tag_name").asText(""));
        if (version.isBlank()) return false;
        return switch (normalizeUpdateChannel()) {
            case "beta" -> release.path("prerelease").asBoolean(false);
            case "dev" -> true;
            default -> !release.path("prerelease").asBoolean(false);
        };
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
            if (githubReleaseMatcher.matches()) return githubReleaseApiUrl(githubReleaseMatcher.group(1));
            Matcher githubTagMatcher = GITHUB_TAG_URL.matcher(configured);
            if (githubTagMatcher.matches()) return GITHUB_API_PREFIX + githubTagMatcher.group(1) + "/releases/tags/" + githubTagMatcher.group(2);
            Matcher giteeReleaseMatcher = GITEE_RELEASE_URL.matcher(configured);
            if (giteeReleaseMatcher.matches()) return giteeReleaseApiUrl(giteeReleaseMatcher.group(1));
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
        if (hasText(repository)) return githubReleaseApiUrl(repository);
        String giteeRepository = resolveGiteeRepositorySlug(updateGiteeRepository);
        return hasText(giteeRepository) ? giteeReleaseApiUrl(giteeRepository) : "";
    }

    private String githubReleaseApiUrl(String repository) {
        return isStableChannel()
                ? GITHUB_API_PREFIX + repository + "/releases/latest"
                : GITHUB_API_PREFIX + repository + "/releases?per_page=50";
    }

    private String giteeReleaseApiUrl(String repository) {
        return isStableChannel()
                ? GITEE_API_PREFIX + repository + "/releases/latest"
                : GITEE_API_PREFIX + repository + "/releases?per_page=50";
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
                    && uri.getPath() != null && uri.getPath().matches("/repos/[^/\\s]+/[^/\\s]+/releases(?:/latest|/tags/[^/\\s]+)?");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isTrustedGiteeApiUri(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                    && "gitee.com".equals(normalizeHost(uri))
                    && uri.getPath() != null && uri.getPath().matches("/api/v5/repos/[^/\\s]+/[^/\\s]+/releases(?:/latest|/tags/[^/\\s]+)?");
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

    private String normalizeUpdateChannel() {
        String value = updateChannel == null ? "stable" : updateChannel.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "beta", "dev" -> value;
            default -> "stable";
        };
    }

    private boolean isStableChannel() {
        return "stable".equals(normalizeUpdateChannel());
    }

    /** GitHub Release API 前缀；自定义 api-url 仅允许同源 API 的 releases 路径。 */
    private static final String GITHUB_API_PREFIX = "https://api.github.com/repos/";
    private static final String GITEE_API_PREFIX = "https://gitee.com/api/v5/repos/";
    private static final String DEFAULT_GITHUB_REPOSITORY = "yuchen0x1/Xianyu-Assistant";
    private static final Pattern GITHUB_RELEASE_URL = Pattern.compile(
            "https?://github\\.com/([^/\\s]+/[^/\\s]+)/releases/latest/?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_TAG_URL = Pattern.compile(
            "https?://github\\.com/([^/\\s]+/[^/\\s]+)/releases/tag/([^/\\s?#]+)/*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GITEE_RELEASE_URL = Pattern.compile("https?://gitee\\.com/([^/\\s]+/[^/\\s]+)/releases/latest/?", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITEE_TAG_URL = Pattern.compile("https?://gitee\\.com/([^/\\s]+/[^/\\s]+)/releases/tag/([^/\\s?#]+)/*", Pattern.CASE_INSENSITIVE);

    private String normalizeVersion(String raw) {
        return VersionUtils.normalize(raw);
    }

    private int compareVersion(String v1, String v2) {
        return VersionUtils.compare(v1, v2);
    }

    /** Cache identity excludes secrets by design; the key only carries non-sensitive config. */
    private record UpdateCheckCacheKey(
            boolean enabled,
            String resolvedApiUrl,
            String currentVersion,
            long cacheSeconds,
            String releaseChannel) {
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
