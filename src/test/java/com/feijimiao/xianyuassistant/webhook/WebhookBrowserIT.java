package com.feijimiao.xianyuassistant.webhook;

import com.feijimiao.xianyuassistant.config.PlaywrightManager;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: build the JAR and frontend, then run -Dtest=WebhookBrowserIT test. Uses only a fresh target directory. */
class WebhookBrowserIT {
    @Test
    void isolatedConsoleSupportsRegistrationPersistenceAndDeliveryHistory() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path jar;
        try (var files = Files.list(root.resolve("target"))) {
            jar = files.filter(p -> p.getFileName().toString().matches("XianYuSellerConsole-[0-9.]+\\.jar"))
                    .max(java.util.Comparator.comparing(p -> p.toFile().lastModified())).orElseThrow();
        }
        Path run = Files.createDirectories(root.resolve("target/webhook-browser-" + UUID.randomUUID()));
        Files.createDirectories(run.resolve("dbdata"));
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        String origin = "http://127.0.0.1:" + port;
        Process server = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Djava.nio.channels.spi.SelectorProvider=sun.nio.ch.WindowsSelectorProvider",
                "-jar", jar.toString(), "--server.port=" + port, "--server.address=127.0.0.1",
                "--app.update-check.enabled=false", "--app.self-update.enabled=false", "--app.self-update.auto-update=false",
                "--app.backup.enabled=false", "--rag.enabled=false")
                .directory(run.toFile()).redirectErrorStream(true).redirectOutput(run.resolve("server.log").toFile()).start();
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            boolean ready = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
            while (server.isAlive() && System.nanoTime() < deadline) {
                try {
                    var response = http.send(HttpRequest.newBuilder(URI.create(origin + "/actuator/health"))
                            .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200 && response.body().contains("UP")) { ready = true; break; }
                } catch (Exception ignored) { }
                Thread.sleep(500);
            }
            assertTrue(ready, "Isolated server failed; see " + run.resolve("server.log"));
            var anonymous = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/webhooks/1/deliveries")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(anonymous.statusCode() == 401 || anonymous.body().contains("401"), "History must require authentication");
            try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
                 Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)
                         .setExecutablePath(new PlaywrightManager().getDetectedBrowserPath()))) {
                Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1440, 1000));
                var errors = new ArrayList<String>();
                page.onPageError(errors::add);
                page.navigate(origin + "/login");
                page.waitForLoadState(LoadState.NETWORKIDLE);
                // Inspect actual rendered inputs before exercising the registration form.
                assertThat(page.locator("#register-username")).isVisible();
                String password = "Verify-" + UUID.randomUUID().toString().substring(0, 12) + "8!";
                page.locator("#register-username").fill("webhook_verifier");
                page.locator("#register-password").fill(password);
                page.locator("#register-confirm-password").fill(password);
                page.locator("button[type=submit]").click();
                page.waitForURL(url -> !url.contains("/login"));
                page.navigate(origin + "/webhooks");
                page.waitForLoadState(LoadState.NETWORKIDLE);
                assertThat(page.getByText("暂无 Webhook 配置", new Page.GetByTextOptions().setExact(true))).isVisible();
                page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("新建 Webhook")).click();
                page.getByPlaceholder("如：通知服务器").fill("隔离验收（测试记录）");
                page.getByPlaceholder("https://example.com/webhook").fill("https://93.184.216.34/validation");
                page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("保存").setExact(true)).click();
                assertThat(page.locator(".hook-card")).hasCount(1);
                // Disable the fixture endpoint before inserting historical test records; never send to an external receiver.
                page.locator(".toggle-switch").click();
                assertThat(page.getByLabel("启用 隔离验收（测试记录）")).not().isChecked();
                page.waitForLoadState(LoadState.NETWORKIDLE);
                page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("投递历史").setExact(true)).click();
                assertThat(page.getByText("暂无投递记录，监听事件触发后会显示在这里")).isVisible();
                try (var connection = DriverManager.getConnection("jdbc:sqlite:" + run.resolve("dbdata/xianyu_assistant.db"));
                     var sql = connection.createStatement()) {
                    sql.execute("PRAGMA busy_timeout=5000");
                    sql.execute("INSERT INTO webhook_delivery (webhook_id,event,payload_json,status,http_status,attempts,last_error) VALUES (1,'validation.failed','{\"orderId\":\"fixture-only\"}','FAILED',500,3,'HTTP 500')");
                    sql.execute("INSERT INTO webhook_delivery (webhook_id,event,payload_json,status,http_status,attempts,delivered_at) VALUES (1,'validation.success','{\"orderId\":\"fixture-only\"}','SUCCESS',204,2,datetime('now','localtime'))");
                }
                page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("刷新历史")).click();
                assertThat(page.locator(".delivery-record")).hasCount(2);
                assertThat(page.locator(".delivery-history")).not().containsText("fixture-only");
                page.getByLabel("投递状态", new Page.GetByLabelOptions().setExact(true)).selectOption("FAILED");
                assertThat(page.locator(".delivery-record")).hasCount(1);
                assertThat(page.locator(".delivery-history")).containsText("自动重试已停止");
                page.getByLabel("投递状态", new Page.GetByLabelOptions().setExact(true)).selectOption("");
                page.waitForFunction("document.querySelectorAll('.toast-container > div').length === 0");
                page.screenshot(new Page.ScreenshotOptions().setPath(root.resolve("docs/images/webhook-history-desktop-2026-11-13.png")).setFullPage(true));
                page.setViewportSize(390, 844);
                page.waitForFunction("document.querySelector('.sidebar').getBoundingClientRect().right <= 0");
                assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Mobile page must not overflow horizontally");
                page.screenshot(new Page.ScreenshotOptions().setPath(root.resolve("docs/images/webhook-history-mobile-2026-11-13.png")).setFullPage(true));
                page.reload();
                page.waitForLoadState(LoadState.NETWORKIDLE);
                assertThat(page.locator(".hook-card")).hasCount(1);
                assertThat(page.getByLabel("启用 隔离验收（测试记录）")).not().isChecked();
                assertTrue(errors.isEmpty(), "Browser errors: " + errors);
            }
        } finally {
            server.destroy();
            if (!server.waitFor(10, TimeUnit.SECONDS)) server.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }
}
