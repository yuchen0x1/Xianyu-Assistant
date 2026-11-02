package com.feijimiao.xianyuassistant.config;

import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class PlaywrightManager {

    private volatile Playwright playwright;
    private volatile Browser browser;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean initialized = false;

    private static final String BROWSER_CACHE_DIR;

    static {
        String configuredPath = System.getProperty("PLAYWRIGHT_BROWSERS_PATH");
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        }
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = getJarDirectory() + File.separator + "ms-playwright";
        }
        BROWSER_CACHE_DIR = configuredPath;
        System.setProperty("PLAYWRIGHT_BROWSERS_PATH", BROWSER_CACHE_DIR);
        log.info("Playwright浏览器缓存目录: {}", BROWSER_CACHE_DIR);
    }

    private static String getJarDirectory() {
        String fallbackDirectory = System.getProperty("user.dir");
        try {
            var codeSource = PlaywrightManager.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return fallbackDirectory;
            }

            var locationUri = codeSource.getLocation().toURI();
            if (!"file".equalsIgnoreCase(locationUri.getScheme())) {
                return fallbackDirectory;
            }

            Path location = Paths.get(locationUri).toAbsolutePath().normalize();
            if (Files.isRegularFile(location) && location.getParent() != null) {
                return location.getParent().toString();
            }
            return location.toString();
        } catch (Exception e) {
            log.debug("无法获取JAR目录，使用user.dir: {}", fallbackDirectory, e);
            return fallbackDirectory;
        }
    }

    @PostConstruct
    public void init() {
        log.info("PlaywrightManager初始化，浏览器缓存目录: {}", BROWSER_CACHE_DIR);
    }

    public BrowserContext createContext() {
        lock.lock();
        try {
            ensureBrowserReady();
            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    // The remote captcha bridge reports coordinates in this viewport.
                    // Keep the browser and the screenshot coordinate space identical.
                    .setViewportSize(1280, 800)
                    .setUserAgent(XianyuProtocolProfile.WEB_USER_AGENT);
            return browser.newContext(contextOptions);
        } catch (Exception e) {
            log.error("创建BrowserContext失败，尝试重建浏览器实例", e);
            try {
                rebuild();
                Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                        .setViewportSize(1280, 800)
                        .setUserAgent(XianyuProtocolProfile.WEB_USER_AGENT);
                return browser.newContext(contextOptions);
            } catch (Exception ex) {
                log.error("重建浏览器后仍然失败", ex);
                throw new RuntimeException("Playwright浏览器不可用", ex);
            }
        } finally {
            lock.unlock();
        }
    }

    private void ensureBrowserReady() {
        if (browser != null && browser.isConnected()) {
            return;
        }
        log.info("Playwright浏览器未就绪或已断开，开始初始化...");
        doInit();
    }

    private void doInit() {
        try {
            if (this.playwright != null) {
                try {
                    this.playwright.close();
                } catch (Exception ignored) {
                }
            }
            this.playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setTimeout(20_000)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"));
            Path systemChromium = findSystemChromium();
            if (systemChromium != null) {
                launchOptions.setExecutablePath(systemChromium);
                log.info("PlaywrightManager使用系统Chromium: {}", systemChromium);
            }
            this.browser = this.playwright.chromium().launch(launchOptions);
            this.initialized = true;
            log.info("Playwright浏览器初始化成功");
        } catch (Exception e) {
            log.error("Playwright浏览器初始化失败", e);
            this.initialized = false;
            throw new RuntimeException("Playwright浏览器初始化失败", e);
        }
    }

    private static Path findSystemChromium() {
        for (String candidate : List.of(
                "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium", "/usr/bin/chromium-browser")) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path;
            }
        }

        Path browsersPath = Paths.get(BROWSER_CACHE_DIR);
        if (Files.isDirectory(browsersPath)) {
            try (var browserDirs = Files.list(browsersPath)) {
                Path chromium = browserDirs
                        .filter(path -> path.getFileName().toString().startsWith("chromium-"))
                        .sorted()
                        .map(path -> path.resolve("chrome-linux/chrome"))
                        .filter(Files::isRegularFile)
                        .findFirst()
                        .orElse(null);
                if (chromium != null && Files.isExecutable(chromium)) {
                    return chromium;
                }
            } catch (Exception e) {
                log.debug("查找Playwright缓存Chromium失败: {}", browsersPath, e);
            }
        }
        return null;
    }

    private void rebuild() {
        log.info("重建Playwright浏览器实例...");
        closeQuietly();
        doInit();
    }

    private void closeQuietly() {
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception ignored) {
        }
        browser = null;
        playwright = null;
        initialized = false;
    }

    @PreDestroy
    public void destroy() {
        log.info("PlaywrightManager销毁，关闭浏览器资源...");
        lock.lock();
        try {
            closeQuietly();
        } finally {
            lock.unlock();
        }
        log.info("PlaywrightManager已销毁");
    }

    public boolean isInitialized() {
        return initialized && browser != null && browser.isConnected();
    }

    public void cleanTempFiles() {
        try {
            Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
            long now = System.currentTimeMillis();
            long thresholdMs = TimeUnit.HOURS.toMillis(1);
            long[] deletedCount = {0};
            long[] deletedSize = {0};
            // 浏览器正在运行时不动任何 profile 目录：那是当前实例的 userDataDir，
            // 删掉会让浏览器崩溃，表现为"隔一段时间就要重新验证/掉登录"。
            boolean browserAlive = isInitialized();

            try (java.util.stream.Stream<Path> paths = Files.list(tmpDir)) {
                paths.filter(path -> {
                            String name = path.getFileName().toString();
                            return name.startsWith("playwright") || name.contains("chromium")
                                    || name.startsWith("core.") || name.endsWith(".pipe")
                                    || name.endsWith(".sock");
                        })
                        .forEach(path -> {
                            try {
                                File file = path.toFile();
                                long age = now - file.lastModified();
                                if (file.isDirectory()) {
                                    // 目录同样要看年龄，且浏览器存活时跳过 profile 目录
                                    if (age <= thresholdMs) {
                                        return;
                                    }
                                    if (browserAlive && file.getName().contains("-profile-")) {
                                        return;
                                    }
                                    long dirSize = deleteDirectory(file);
                                    deletedCount[0]++;
                                    deletedSize[0] += dirSize;
                                } else if (age > thresholdMs) {
                                    long fileSize = file.length();
                                    if (file.delete()) {
                                        deletedCount[0]++;
                                        deletedSize[0] += fileSize;
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        });
            }

            if (deletedCount[0] > 0) {
                log.info("清理Playwright临时文件: {}个文件, 释放空间: {}KB",
                        deletedCount[0], deletedSize[0] / 1024);
            }
        } catch (Exception e) {
            log.warn("清理Playwright临时文件失败", e);
        }
    }

    private long deleteDirectory(File directory) {
        long totalSize = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    totalSize += deleteDirectory(file);
                } else {
                    totalSize += file.length();
                    file.delete();
                }
            }
        }
        directory.delete();
        return totalSize;
    }
}
