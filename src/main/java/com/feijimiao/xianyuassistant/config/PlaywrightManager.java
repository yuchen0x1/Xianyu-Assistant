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
        // 启动自检：只探测"能不能找到可执行文件"，不真正启动浏览器（启动成本高且会污染启动日志）。
        // 找不到时明确告警，避免运行期到 Cookie 兜底刷新时才静默失败。
        Path detected = findSystemChromium();
        if (detected != null) {
            log.info("Playwright浏览器可执行文件已定位: {}", detected);
        } else {
            log.warn("未找到可用的 Chromium，浏览器兜底能力不可用（扫码登录/Cookie 兜底刷新将降级）。"
                    + "请执行 `npx playwright install chromium`，或安装 Chrome，"
                    + "或通过 PLAYWRIGHT_BROWSERS_PATH 指向已有的浏览器缓存目录。"
                    + "已搜索目录: {} + {}", BROWSER_CACHE_DIR, defaultPlaywrightCacheDir());
        }
    }

    /** 浏览器兜底能力是否可用（供健康检查/前端提示使用）。 */
    public boolean isBrowserAvailable() {
        return findSystemChromium() != null;
    }

    /** 已定位到的浏览器可执行文件路径，未找到返回 null。 */
    public Path getDetectedBrowserPath() {
        return findSystemChromium();
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

    /**
     * 按平台探测可用的 Chromium 可执行文件。
     *
     * <p>历史实现只认 Linux 的 {@code /usr/bin/*}，且缓存目录的回退路径硬编码
     * {@code chrome-linux/chrome}，导致 Windows/macOS 上即使浏览器已安装也永远找不到，
     * Cookie 兜底刷新静默失效（只能重新扫码）。这里按平台枚举目录与可执行文件名。</p>
     */
    private static Path findSystemChromium() {
        // 1) 系统安装的浏览器
        for (String candidate : systemBrowserCandidates()) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path;
            }
        }

        // 2) Playwright 浏览器缓存（可能是自定义目录，也可能是 Playwright 默认用户缓存）
        for (Path browsersPath : browserCacheCandidates()) {
            Path chromium = findChromiumInCache(browsersPath);
            if (chromium != null) {
                return chromium;
            }
        }
        return null;
    }

    private static List<String> systemBrowserCandidates() {
        if (isWindows()) {
            return List.of(
                    System.getenv().getOrDefault("PROGRAMFILES", "C:\\Program Files")
                            + "\\Google\\Chrome\\Application\\chrome.exe",
                    System.getenv().getOrDefault("PROGRAMFILES(X86)", "C:\\Program Files (x86)")
                            + "\\Google\\Chrome\\Application\\chrome.exe",
                    System.getenv().getOrDefault("LOCALAPPDATA", "")
                            + "\\Google\\Chrome\\Application\\chrome.exe",
                    System.getenv().getOrDefault("PROGRAMFILES", "C:\\Program Files")
                            + "\\Microsoft\\Edge\\Application\\msedge.exe");
        }
        if (isMac()) {
            return List.of(
                    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                    "/Applications/Chromium.app/Contents/MacOS/Chromium");
        }
        return List.of(
                "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium", "/usr/bin/chromium-browser");
    }

    /** 待搜索的浏览器缓存目录：显式配置优先，其次 Playwright 默认位置。 */
    private static List<Path> browserCacheCandidates() {
        List<Path> candidates = new java.util.ArrayList<>();
        candidates.add(Paths.get(BROWSER_CACHE_DIR));
        Path defaultCache = defaultPlaywrightCacheDir();
        if (defaultCache != null && !candidates.contains(defaultCache)) {
            candidates.add(defaultCache);
        }
        return candidates;
    }

    /**
     * Playwright 的默认浏览器缓存目录（用户级）。
     *
     * <p>本项目默认把 {@code PLAYWRIGHT_BROWSERS_PATH} 指向 JAR 同级目录，但用户很可能用
     * {@code npx playwright install chromium} 装到了默认位置；两者都要能找到。</p>
     */
    static Path defaultPlaywrightCacheDir() {
        if (isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            return localAppData == null || localAppData.isBlank()
                    ? null
                    : Paths.get(localAppData, "ms-playwright");
        }
        if (isMac()) {
            return Paths.get(System.getProperty("user.home"), "Library", "Caches", "ms-playwright");
        }
        String xdgCache = System.getenv("XDG_CACHE_HOME");
        return xdgCache == null || xdgCache.isBlank()
                ? Paths.get(System.getProperty("user.home"), ".cache", "ms-playwright")
                : Paths.get(xdgCache, "ms-playwright");
    }

    /** 平台对应的 Chromium 相对路径（Playwright 各版本目录名不同，逐个探测）。 */
    static List<String> chromiumRelativePaths() {
        if (isWindows()) {
            return List.of("chrome-win64/chrome.exe", "chrome-win/chrome.exe");
        }
        if (isMac()) {
            return List.of(
                    "chrome-mac/Chromium.app/Contents/MacOS/Chromium",
                    "chrome-mac-arm64/Chromium.app/Contents/MacOS/Chromium",
                    "chrome-mac-x64/Chromium.app/Contents/MacOS/Chromium");
        }
        return List.of("chrome-linux/chrome", "chrome-linux64/chrome");
    }

    private static Path findChromiumInCache(Path browsersPath) {
        if (browsersPath == null || !Files.isDirectory(browsersPath)) {
            return null;
        }
        try (var browserDirs = Files.list(browsersPath)) {
            // 新版 Playwright 可能只装 chromium_headless_shell，两种前缀都要认
            return browserDirs
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("chromium-") || name.startsWith("chromium_headless_shell-");
                    })
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(dir -> {
                        for (String relative : chromiumRelativePaths()) {
                            Path executable = dir.resolve(relative);
                            if (Files.isRegularFile(executable)) {
                                return executable;
                            }
                        }
                        return null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.debug("查找Playwright缓存Chromium失败: {}", browsersPath, e);
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
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
