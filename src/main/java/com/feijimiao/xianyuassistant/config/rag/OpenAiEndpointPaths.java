package com.feijimiao.xianyuassistant.config.rag;

import java.util.regex.Pattern;

/**
 * 根据用户配置的 OpenAI 兼容 Base URL 推导各端点路径。
 * <p>
 * Spring AI 的 OpenAiApi 默认固定拼接 /v1/chat/completions，但部分厂商的版本号
 * 不是 v1（如智谱 https://open.bigmodel.cn/api/paas/v4），此时必须拼接 /chat/completions。
 * 规则：Base URL 末段是版本号（v1/v2/v3/...）时直接拼资源路径，否则保持 /v1 前缀。
 * 同时兼容用户直接粘贴完整端点（以 /chat/completions 结尾）的写法。
 *
 * @author IAMLZY
 * @date 2026/9/7
 */
public final class OpenAiEndpointPaths {

    private static final String V1_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String BARE_COMPLETIONS_PATH = "/chat/completions";
    private static final String V1_EMBEDDINGS_PATH = "/v1/embeddings";
    private static final String BARE_EMBEDDINGS_PATH = "/embeddings";
    private static final String V1_MODELS_PATH = "/v1/models";
    private static final String BARE_MODELS_PATH = "/models";

    private static final String COMPLETIONS_SUFFIX = "/chat/completions";
    private static final Pattern VERSION_SEGMENT = Pattern.compile("(?i)^v\\d+$");

    private OpenAiEndpointPaths() {
    }

    /**
     * 规范化 Base URL：去空白、去尾部斜杠，并剥离用户多填的 /chat/completions 后缀。
     */
    public static String normalizeBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null) {
            return null;
        }
        String url = rawBaseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.toLowerCase().endsWith(COMPLETIONS_SUFFIX)) {
            url = url.substring(0, url.length() - COMPLETIONS_SUFFIX.length());
        }
        return url;
    }

    public static String completionsPath(String normalizedBaseUrl) {
        return endsWithVersionSegment(normalizedBaseUrl) ? BARE_COMPLETIONS_PATH : V1_COMPLETIONS_PATH;
    }

    public static String embeddingsPath(String normalizedBaseUrl) {
        return endsWithVersionSegment(normalizedBaseUrl) ? BARE_EMBEDDINGS_PATH : V1_EMBEDDINGS_PATH;
    }

    public static String modelsPath(String normalizedBaseUrl) {
        return endsWithVersionSegment(normalizedBaseUrl) ? BARE_MODELS_PATH : V1_MODELS_PATH;
    }

    private static boolean endsWithVersionSegment(String normalizedBaseUrl) {
        if (normalizedBaseUrl == null || normalizedBaseUrl.isEmpty()) {
            return false;
        }
        int slash = normalizedBaseUrl.lastIndexOf('/');
        String lastSegment = slash >= 0 ? normalizedBaseUrl.substring(slash + 1) : normalizedBaseUrl;
        return VERSION_SEGMENT.matcher(lastSegment).matches();
    }
}
