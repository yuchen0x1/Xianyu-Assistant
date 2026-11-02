package com.feijimiao.xianyuassistant.config.rag;

import com.feijimiao.xianyuassistant.security.AiEndpointValidator;
import com.feijimiao.xianyuassistant.service.SysSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 动态AI ChatClient管理器
 * 从数据库读取API Key，动态创建/重建ChatClient，无需重启服务
 * 线程安全：使用ReadWriteLock保护ChatClient的读写
 *
 * @author IAMLZY
 * @date 2026/4/23
 */
@Slf4j
@Component
public class DynamicAIChatClientManager {

    private static final String AI_API_KEY_SETTING = "ai_api_key";
    private static final String AI_BASE_URL_SETTING = "ai_base_url";
    private static final String AI_MODEL_SETTING = "ai_model";

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";
    private static final String DEFAULT_MODEL = "deepseek-v3";
    private static final int DEFAULT_AI_CONNECT_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_AI_RESPONSE_TIMEOUT_SECONDS = 35;
    private static final int DELIVERY_POLISH_TIMEOUT_BUDGET_SECONDS = 40;
    /** 主链路超时的硬上限，防止配置放大成事实上的"永不超时"。 */
    private static final int MAX_AI_CONNECT_TIMEOUT_SECONDS = 30;
    private static final int MAX_AI_RESPONSE_TIMEOUT_SECONDS = 120;

    @Autowired
    @Lazy
    private SysSettingService sysSettingService;

    @Autowired
    private AiEndpointValidator aiEndpointValidator;

    @Value("${ai.enabled:false}")
    private boolean aiEnabled;

    @Value("${ai.http.connect-timeout-seconds:5}")
    private int aiConnectTimeoutSeconds;

    @Value("${ai.http.response-timeout-seconds:35}")
    private int aiResponseTimeoutSeconds;

    /** 当前缓存的API Key，用于判断是否需要重建 */
    private volatile String cachedApiKey;

    /** 当前缓存的Base URL */
    private volatile String cachedBaseUrl;

    /** 当前缓存的Model */
    private volatile String cachedModel;

    /** 当前ChatClient实例 */
    private volatile ChatClient chatClient;

    /** 发货文案润色使用单次调用，避免前端超时后模型请求仍在重试。 */
    private volatile ChatClient deliveryPolishChatClient;

    /** 读写锁，保护ChatClient的线程安全 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 获取ChatClient实例
     * 如果API Key未配置或为空，返回null
     * 如果API Key发生变化，自动重建ChatClient
     *
     * @return ChatClient实例，未配置API Key时返回null
     */
    public ChatClient getChatClient() {
        if (!aiEnabled) {
            log.debug("[DynamicAI] AI功能未启用(ai.enabled=false)");
            return null;
        }

        // 从数据库读取当前配置
        String currentApiKey = getSettingValue(AI_API_KEY_SETTING);
        String currentBaseUrl = getSettingValue(AI_BASE_URL_SETTING);
        String currentModel = getSettingValue(AI_MODEL_SETTING);

        if (currentApiKey == null || currentApiKey.trim().isEmpty()) {
            log.debug("[DynamicAI] API Key未配置，AI功能不可用");
            return null;
        }

        // 检查配置是否变化，需要重建
        boolean needRebuild = chatClient == null
                || deliveryPolishChatClient == null
                || !currentApiKey.equals(cachedApiKey)
                || !safeEquals(currentBaseUrl, cachedBaseUrl)
                || !safeEquals(currentModel, cachedModel);

        if (needRebuild) {
            lock.writeLock().lock();
            try {
                // 双重检查，防止并发重建
                boolean stillNeedRebuild = chatClient == null
                        || deliveryPolishChatClient == null
                        || !currentApiKey.equals(cachedApiKey)
                        || !safeEquals(currentBaseUrl, cachedBaseUrl)
                        || !safeEquals(currentModel, cachedModel);

                if (stillNeedRebuild) {
                    log.info("[DynamicAI] 检测到AI配置变化，重建ChatClient: baseUrlConfigured={}, model={}, apiKeyConfigured={}",
                            currentBaseUrl != null && !currentBaseUrl.isBlank(), currentModel,
                            !currentApiKey.isBlank());

                    ChatClients clients = buildChatClients(currentApiKey, currentBaseUrl, currentModel);
                    chatClient = clients.standard();
                    deliveryPolishChatClient = clients.deliveryPolish();
                    cachedApiKey = currentApiKey;
                    cachedBaseUrl = currentBaseUrl;
                    cachedModel = currentModel;

                    log.info("[DynamicAI] ChatClient重建完成");
                }
            } catch (Exception e) {
                // 按仓库安全基线：日志只记录错误类型，不输出异常消息（消息可能携带用户配置内容）
                log.error("[DynamicAI] ChatClient重建失败: errorType={}", e.getClass().getSimpleName());
                chatClient = null;
                deliveryPolishChatClient = null;
                cachedApiKey = null;
            } finally {
                lock.writeLock().unlock();
            }
        }

        lock.readLock().lock();
        try {
            return chatClient;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取发货文案润色专用客户端。该客户端禁用自动重试，确保请求在配置的HTTP超时内结束。
     */
    public ChatClient getDeliveryPolishChatClient() {
        if (getChatClient() == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            return deliveryPolishChatClient;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 检查AI是否可用（API Key已配置且ai.enabled=true）
     */
    public boolean isAvailable() {
        if (!aiEnabled) {
            return false;
        }
        String apiKey = getSettingValue(AI_API_KEY_SETTING);
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * 获取AI状态信息
     */
    public AIStatusInfo getStatusInfo() {
        AIStatusInfo info = new AIStatusInfo();
        info.setEnabled(aiEnabled);

        if (!aiEnabled) {
            info.setAvailable(false);
            info.setMessage("AI功能未启用(ai.enabled=false)");
            return info;
        }

        String apiKey = getSettingValue(AI_API_KEY_SETTING);
        String baseUrl = getSettingValue(AI_BASE_URL_SETTING);
        String model = getSettingValue(AI_MODEL_SETTING);

        info.setBaseUrl(baseUrl != null ? baseUrl : DEFAULT_BASE_URL);
        info.setModel(model != null ? model : DEFAULT_MODEL);

        if (apiKey == null || apiKey.trim().isEmpty()) {
            info.setAvailable(false);
            info.setMessage("API Key未配置，请在系统设置中配置AI API Key");
        } else {
            info.setAvailable(true);
            info.setApiKeyConfigured(true);
            info.setMessage("AI服务可用");
        }

        return info;
    }

    /**
     * 强制重建ChatClient（配置变更时调用）
     */
    public void forceRebuild() {
        log.info("[DynamicAI] 收到强制重建信号，清除缓存");
        lock.writeLock().lock();
        try {
            cachedApiKey = null;
            cachedBaseUrl = null;
            cachedModel = null;
            chatClient = null;
            deliveryPolishChatClient = null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 构建ChatClient实例
     */
    private ChatClients buildChatClients(String apiKey, String baseUrl, String model) {
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl.trim() : DEFAULT_BASE_URL;
        String effectiveModel = (model != null && !model.trim().isEmpty()) ? model.trim() : DEFAULT_MODEL;

        // 防线兜底：配置可能经备份导入/直接写库绕过保存校验，构建出站客户端前必须再次校验
        aiEndpointValidator.validate(effectiveBaseUrl);

        // 创建ChatModel
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(effectiveModel)
                .temperature(0.7)
                .build();

        String normalizedBaseUrl = OpenAiEndpointPaths.normalizeBaseUrl(effectiveBaseUrl);
        String completionsPath = OpenAiEndpointPaths.completionsPath(normalizedBaseUrl);

        HttpTimeouts polishTimeouts = normalizePolishTimeouts();
        SimpleClientHttpRequestFactory polishRequestFactory = new SimpleClientHttpRequestFactory();
        polishRequestFactory.setConnectTimeout(Duration.ofSeconds(polishTimeouts.connectSeconds()));
        polishRequestFactory.setReadTimeout(Duration.ofSeconds(polishTimeouts.responseSeconds()));
        OpenAiApi deliveryPolishApi = OpenAiApi.builder()
                .apiKey(apiKey.trim())
                .baseUrl(normalizedBaseUrl)
                .completionsPath(completionsPath)
                .restClientBuilder(RestClient.builder().requestFactory(polishRequestFactory))
                .build();

        // 主客户端同样必须有超时与重试上限：自动回复与发货共用 JobEngine 的 4 个
        // worker，上游一旦半开连接（不返回也不断开），未设超时的调用会永久占用
        // worker，队列 32 满后所有后台任务一起停摆，且日志里看不到任何异常。
        HttpTimeouts standardTimeouts = normalizeStandardTimeouts();
        SimpleClientHttpRequestFactory standardRequestFactory = new SimpleClientHttpRequestFactory();
        standardRequestFactory.setConnectTimeout(Duration.ofSeconds(standardTimeouts.connectSeconds()));
        standardRequestFactory.setReadTimeout(Duration.ofSeconds(standardTimeouts.responseSeconds()));
        OpenAiApi standardApi = OpenAiApi.builder()
                .apiKey(apiKey.trim())
                .baseUrl(normalizedBaseUrl)
                .completionsPath(completionsPath)
                .restClientBuilder(RestClient.builder().requestFactory(standardRequestFactory))
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(standardApi)
                .defaultOptions(chatOptions)
                // Spring AI 默认 RetryTemplate 会重试 10 次并指数退避，
                // 叠加无超时的读请求等于把阻塞时间放大一个数量级，这里只发一次。
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build())
                .build();

        OpenAiChatModel deliveryPolishModel = OpenAiChatModel.builder()
                .openAiApi(deliveryPolishApi)
                .defaultOptions(chatOptions)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build())
                .build();

        return new ChatClients(
                ChatClient.builder(chatModel)
                        .defaultSystem("你是一个闲鱼智能客服助手")
                        .build(),
                ChatClient.builder(deliveryPolishModel).build());
    }

    private HttpTimeouts normalizePolishTimeouts() {        int configuredConnect = aiConnectTimeoutSeconds > 0
                ? aiConnectTimeoutSeconds
                : DEFAULT_AI_CONNECT_TIMEOUT_SECONDS;
        int connect = Math.min(configuredConnect, DELIVERY_POLISH_TIMEOUT_BUDGET_SECONDS - 1);

        int configuredResponse = aiResponseTimeoutSeconds > 0
                ? aiResponseTimeoutSeconds
                : DEFAULT_AI_RESPONSE_TIMEOUT_SECONDS;
        int response = Math.min(configuredResponse, DELIVERY_POLISH_TIMEOUT_BUDGET_SECONDS - connect);

        if (connect != aiConnectTimeoutSeconds || response != aiResponseTimeoutSeconds) {
            log.warn("[DynamicAI] 润色请求超时配置超出安全范围，已自动调整");
        }
        return new HttpTimeouts(connect, response);
    }

    /**
     * 主链路（自动回复 / AI 客服）的超时。与润色不同，这里允许按配置放大，
     * 但仍强制一个有界区间，避免配置成 0 或超大值后退化成"永不超时"。
     */
    private HttpTimeouts normalizeStandardTimeouts() {
        int connectSeconds = aiConnectTimeoutSeconds > 0 ? aiConnectTimeoutSeconds : DEFAULT_AI_CONNECT_TIMEOUT_SECONDS;
        connectSeconds = Math.max(1, Math.min(connectSeconds, MAX_AI_CONNECT_TIMEOUT_SECONDS));

        int readSeconds = aiResponseTimeoutSeconds > 0 ? aiResponseTimeoutSeconds : DEFAULT_AI_RESPONSE_TIMEOUT_SECONDS;
        readSeconds = Math.max(1, Math.min(readSeconds, MAX_AI_RESPONSE_TIMEOUT_SECONDS));

        if (connectSeconds != aiConnectTimeoutSeconds || readSeconds != aiResponseTimeoutSeconds) {
            log.warn("[DynamicAI] AI 请求超时配置超出安全范围，已收敛为 connect={}s, read={}s", connectSeconds, readSeconds);
        }
        return new HttpTimeouts(connectSeconds, readSeconds);
    }

    private String getSettingValue(String key) {
        try {
            return sysSettingService.getSettingValue(key);
        } catch (Exception e) {
            log.warn("[DynamicAI] 读取配置失败: key={}", key, e);
            return null;
        }
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private record ChatClients(ChatClient standard, ChatClient deliveryPolish) {}

    private record HttpTimeouts(int connectSeconds, int responseSeconds) {}

    /**
     * AI状态信息
     */
    public static class AIStatusInfo {
        private boolean enabled;
        private boolean available;
        private boolean apiKeyConfigured;
        private String message;
        private String baseUrl;
        private String model;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
        public boolean isApiKeyConfigured() { return apiKeyConfigured; }
        public void setApiKeyConfigured(boolean apiKeyConfigured) { this.apiKeyConfigured = apiKeyConfigured; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}
