package com.feijimiao.xianyuassistant.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaywrightManagerTest {

    @Test
    void staticInitializationAlwaysConfiguresABrowserCacheDirectory() {
        new PlaywrightManager();

        assertThat(System.getProperty("PLAYWRIGHT_BROWSERS_PATH")).isNotBlank();
    }
}
