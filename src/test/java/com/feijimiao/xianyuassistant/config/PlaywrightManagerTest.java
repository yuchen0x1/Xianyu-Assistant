package com.feijimiao.xianyuassistant.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaywrightManagerTest {

    @Test
    void staticInitializationAlwaysConfiguresABrowserCacheDirectory() {
        new PlaywrightManager();

        assertThat(System.getProperty("PLAYWRIGHT_BROWSERS_PATH")).isNotBlank();
    }

    @Test
    void defaultCacheDirIsResolvedForCurrentPlatform() {
        Path defaultCache = PlaywrightManager.defaultPlaywrightCacheDir();

        assertThat(defaultCache)
                .as("默认浏览器缓存目录应能解析（Windows 依赖 LOCALAPPDATA）")
                .isNotNull();
        assertThat(defaultCache.getFileName().toString()).isEqualTo("ms-playwright");

        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            assertThat(defaultCache.toString()).containsIgnoringCase("ms-playwright");
        }
    }

    @Test
    void chromiumRelativePathsMatchCurrentPlatformLayout() {
        List<String> paths = PlaywrightManager.chromiumRelativePaths();

        assertThat(paths).isNotEmpty();
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            assertThat(paths)
                    .as("Windows 上的 Chromium 可执行文件是 chrome.exe，历史实现误用 chrome-linux/chrome")
                    .allSatisfy(path -> assertThat(path).endsWith("chrome.exe"));
        } else if (os.contains("mac")) {
            assertThat(paths).allSatisfy(path -> assertThat(path).contains("Chromium.app"));
        } else {
            assertThat(paths).allSatisfy(path -> assertThat(path).endsWith("/chrome"));
        }
    }

    @Test
    void browserAvailabilityProbeDoesNotThrowWhenBrowserIsMissing() {
        PlaywrightManager manager = new PlaywrightManager();

        // 只断言不抛异常：CI 与本机浏览器安装情况不同，此处验证探测逻辑健壮性
        assertThat(manager.isBrowserAvailable()).isEqualTo(manager.getDetectedBrowserPath() != null);
    }
}
