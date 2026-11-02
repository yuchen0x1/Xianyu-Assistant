package com.feijimiao.xianyuassistant.config.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiEndpointPathsTest {

    @Test
    void versionedBaseUrlUsesBareResourcePath() {
        String base = OpenAiEndpointPaths.normalizeBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        assertThat(base).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(OpenAiEndpointPaths.completionsPath(base)).isEqualTo("/chat/completions");
        assertThat(OpenAiEndpointPaths.embeddingsPath(base)).isEqualTo("/embeddings");
        assertThat(OpenAiEndpointPaths.modelsPath(base)).isEqualTo("/models");
    }

    @Test
    void plainBaseUrlKeepsDefaultV1Path() {
        String base = OpenAiEndpointPaths.normalizeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/");
        assertThat(base).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode");
        assertThat(OpenAiEndpointPaths.completionsPath(base)).isEqualTo("/v1/chat/completions");
        assertThat(OpenAiEndpointPaths.embeddingsPath(base)).isEqualTo("/v1/embeddings");
        assertThat(OpenAiEndpointPaths.modelsPath(base)).isEqualTo("/v1/models");
    }

    @Test
    void baseUrlEndingWithV1DoesNotDuplicateVersion() {
        String base = OpenAiEndpointPaths.normalizeBaseUrl("https://api.moonshot.cn/v1");
        assertThat(OpenAiEndpointPaths.completionsPath(base)).isEqualTo("/chat/completions");
        assertThat(OpenAiEndpointPaths.embeddingsPath(base)).isEqualTo("/embeddings");
    }

    @Test
    void fullEndpointSuffixIsStripped() {
        String base = OpenAiEndpointPaths.normalizeBaseUrl("https://open.bigmodel.cn/api/paas/v4/chat/completions");
        assertThat(base).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(OpenAiEndpointPaths.completionsPath(base)).isEqualTo("/chat/completions");
    }

    @Test
    void nullAndEmptyBaseUrlFallBackToV1Path() {
        assertThat(OpenAiEndpointPaths.normalizeBaseUrl(null)).isNull();
        assertThat(OpenAiEndpointPaths.completionsPath(null)).isEqualTo("/v1/chat/completions");
        assertThat(OpenAiEndpointPaths.embeddingsPath("")).isEqualTo("/v1/embeddings");
    }
}
