package com.feijimiao.xianyuassistant.service.update;

import com.feijimiao.xianyuassistant.controller.dto.VersionInfoRespDTO;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class UpdateCheckServiceTest {

    @Test
    void disabledUpdateCheckReturnsLocalVersionWithoutContactingConfiguredUrl() {
        UpdateCheckService service = new UpdateCheckService();
        ReflectionTestUtils.setField(service, "currentVersion", "2.1.2");
        ReflectionTestUtils.setField(service, "updateCheckEnabled", false);
        ReflectionTestUtils.setField(service, "updateApiUrl", "http://10.255.255.1/never-contact");

        VersionInfoRespDTO result = assertTimeoutPreemptively(
                Duration.ofMillis(500), service::checkUpdate);

        assertThat(result.getCurrentVersion()).isEqualTo("2.1.2");
        assertThat(result.getLatestVersion()).isEqualTo("2.1.2");
        assertThat(result.getHasUpdate()).isFalse();
    }

    @Test
    void loopbackReleaseCheckParsesReleaseVersion() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/release", exchange -> {
            byte[] body = "{\"tag_name\":\"v2.2.0\",\"body\":\"夜间模式\",\"published_at\":\"2026-07-18T00:00:00Z\",\"html_url\":\"https://gitee.com/example/app/releases/tag/v2.2.0\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            UpdateCheckService service = new UpdateCheckService();
            ReflectionTestUtils.setField(service, "currentVersion", "2.1.8");
            ReflectionTestUtils.setField(service, "updateCheckEnabled", true);
            ReflectionTestUtils.setField(service, "updateApiUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/release");

            VersionInfoRespDTO result = service.checkUpdate();

            assertThat(result.getLatestVersion()).isEqualTo("2.2.0");
            assertThat(result.getHasUpdate()).isTrue();
            assertThat(result.getCheckStatus()).isEqualTo("available");
            assertThat(result.getChannel()).isEqualTo("github");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void betaChannelSelectsNewestPrereleaseAndKeepsPrereleaseSuffix() throws Exception {
        HttpServer server = startServer(exchange -> sendJson(exchange, 200,
                "[{\"tag_name\":\"v2.2.0\",\"prerelease\":false,\"published_at\":\"2026-07-18T00:00:00Z\",\"html_url\":\"https://github.com/example/app/releases/tag/v2.2.0\"},"
                        + "{\"tag_name\":\"v2.3.0-beta.1\",\"prerelease\":true,\"published_at\":\"2026-07-19T00:00:00Z\",\"html_url\":\"https://github.com/example/app/releases/tag/v2.3.0-beta.1\"},"
                        + "{\"tag_name\":\"v2.3.0-beta.2\",\"prerelease\":true,\"published_at\":\"2026-07-20T00:00:00Z\",\"html_url\":\"https://github.com/example/app/releases/tag/v2.3.0-beta.2\"}]"));
        try {
            UpdateCheckService service = configuredService(server, 0);
            ReflectionTestUtils.setField(service, "currentVersion", "2.3.0-beta.1");
            ReflectionTestUtils.setField(service, "updateChannel", "beta");

            VersionInfoRespDTO result = service.checkUpdate();

            assertThat(result.getLatestVersion()).isEqualTo("2.3.0-beta.2");
            assertThat(result.getHasUpdate()).isTrue();
            assertThat(result.getPrerelease()).isTrue();
            assertThat(result.getReleaseChannel()).isEqualTo("beta");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void githubRepositorySettingBuildsGithubReleaseApiUrl() {
        UpdateCheckService service = new UpdateCheckService();
        ReflectionTestUtils.setField(service, "updateGithubRepository", "https://github.com/example/private-app");

        String resolved = ReflectionTestUtils.invokeMethod(service, "resolveUpdateApiUrl");

        assertThat(resolved).isEqualTo("https://api.github.com/repos/example/private-app/releases/latest");
    }

    @Test
    void devChannelUsesReleaseListApi() {
        UpdateCheckService service = new UpdateCheckService();
        ReflectionTestUtils.setField(service, "updateGithubRepository", "example/private-app");
        ReflectionTestUtils.setField(service, "updateChannel", "dev");

        String resolved = ReflectionTestUtils.invokeMethod(service, "resolveUpdateApiUrl");

        assertThat(resolved).isEqualTo("https://api.github.com/repos/example/private-app/releases?per_page=50");
    }

    @Test
    void successfulUpdateCheckUsesOneShortCacheEntryAndReturnsCopies() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            requests.incrementAndGet();
            sendJson(exchange, 200,
                    "{\"tag_name\":\"v2.2.0\",\"body\":\"release\",\"published_at\":\"2026-07-18T00:00:00Z\",\"html_url\":\"https://gitee.com/example/app/releases/tag/v2.2.0\"}");
        });
        try {
            UpdateCheckService service = configuredService(server, 300);

            VersionInfoRespDTO first = service.checkUpdate();
            first.setLatestVersion("tampered-by-caller");
            VersionInfoRespDTO second = service.checkUpdate();

            assertThat(requests).hasValue(1);
            assertThat(second.getLatestVersion()).isEqualTo("2.2.0");
            assertThat(second).isNotSameAs(first);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void concurrentChecksShareOneCachePopulation() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("测试服务被中断", interrupted);
            }
            sendJson(exchange, 200,
                    "{\"tag_name\":\"v2.2.0\",\"body\":\"release\",\"published_at\":\"\",\"html_url\":\"https://gitee.com/example/app/releases/tag/v2.2.0\"}");
        });
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            UpdateCheckService service = configuredService(server, 300);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<VersionInfoRespDTO>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.checkUpdate();
                }));
            }
            start.countDown();

            for (Future<VersionInfoRespDTO> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS).getLatestVersion())
                        .isEqualTo("2.2.0");
            }
            assertThat(requests).hasValue(1);
        } finally {
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void unavailableUpdateCheckIsAlsoCached() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            requests.incrementAndGet();
            sendJson(exchange, 503, "{}");
        });
        try {
            UpdateCheckService service = configuredService(server, 300);

            VersionInfoRespDTO first = service.checkUpdate();
            VersionInfoRespDTO second = service.checkUpdate();

            assertThat(requests).hasValue(1);
            assertThat(first.getCheckStatus()).isEqualTo("unavailable");
            assertThat(second.getCheckStatus()).isEqualTo("unavailable");
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void accessDeniedResponsesExplainGithubVisibilityProblem(int status) throws Exception {
        assertStatusMessage(status, "无法访问 GitHub 更新仓库，请确认仓库权限或 Release 可见性");
    }

    @Test
    void notFoundResponseExplainsGithubReleaseAddressProblem() throws Exception {
        assertStatusMessage(404, "GitHub 更新仓库或 Release 不存在，请确认仓库地址和发布标签");
    }

    @Test
    void rateLimitResponseExplainsGithubApiRateLimit() throws Exception {
        assertStatusMessage(429, "GitHub API 请求频率受限，请稍后重试");
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void serverErrorResponsesExplainGithubServiceUnavailability(int status) throws Exception {
        assertStatusMessage(status, "GitHub 服务暂时不可用，请稍后重试");
    }

    @Test
    void invalidJsonResponseExplainsResponseFormatProblem() throws Exception {
        HttpServer server = startServer(exchange -> sendJson(exchange, 200, "不是 JSON"));
        try {
            VersionInfoRespDTO result = configuredService(server, 0).checkUpdate();

            assertThat(result.getCheckStatus()).isEqualTo("unavailable");
            assertThat(result.getMessage()).isEqualTo("更新源响应格式无效，请稍后重试");
            assertThat(result.getMessage()).doesNotContain("不是 JSON");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void connectionFailureExplainsGithubSourceConnectivityProblem() throws Exception {
        HttpServer server = startServer(exchange -> sendJson(exchange, 200, "{}"));
        int port = server.getAddress().getPort();
        server.stop(0);

        UpdateCheckService service = new UpdateCheckService();
        ReflectionTestUtils.setField(service, "currentVersion", "2.1.8");
        ReflectionTestUtils.setField(service, "updateCheckEnabled", true);
        ReflectionTestUtils.setField(service, "updateApiUrl", "http://127.0.0.1:" + port + "/release");
        ReflectionTestUtils.setField(service, "updateCheckCacheSeconds", 0L);

        VersionInfoRespDTO result = service.checkUpdate();

        assertThat(result.getCheckStatus()).isEqualTo("unavailable");
        assertThat(result.getMessage()).isEqualTo("无法连接 GitHub 更新源，请检查网络后重试");
    }

    private void assertStatusMessage(int status, String expectedMessage) throws Exception {
        HttpServer server = startServer(exchange -> sendJson(exchange, status, "{\"message\":\"内部详情不应返回\"}"));
        try {
            VersionInfoRespDTO result = configuredService(server, 0).checkUpdate();

            assertThat(result.getCheckStatus()).isEqualTo("unavailable");
            assertThat(result.getMessage()).isEqualTo(expectedMessage);
            assertThat(result.getMessage()).doesNotContain("内部详情");
        } finally {
            server.stop(0);
        }
    }

    private UpdateCheckService configuredService(HttpServer server, long cacheSeconds) {
        UpdateCheckService service = new UpdateCheckService();
        ReflectionTestUtils.setField(service, "currentVersion", "2.1.8");
        ReflectionTestUtils.setField(service, "updateCheckEnabled", true);
        ReflectionTestUtils.setField(service, "updateApiUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/release");
        ReflectionTestUtils.setField(service, "updateCheckCacheSeconds", cacheSeconds);
        return service;
    }

    private HttpServer startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/release", handler);
        server.start();
        return server;
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
