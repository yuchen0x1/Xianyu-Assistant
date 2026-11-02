package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.config.rag.DynamicAIChatClientManager;
import com.feijimiao.xianyuassistant.config.rag.OpenAiEndpointPaths;
import com.feijimiao.xianyuassistant.controller.dto.ChatWithAIReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.DeleteRAGDataReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.PutNewDataToRAGReqDTO;
import com.feijimiao.xianyuassistant.service.AIService;
import com.feijimiao.xianyuassistant.service.GoodsInfoService;
import com.feijimiao.xianyuassistant.service.ItemDetailSyncService;
import com.feijimiao.xianyuassistant.service.GoodsConfigService;
import com.feijimiao.xianyuassistant.service.SysSettingService;
import com.feijimiao.xianyuassistant.service.bo.RAGDataRespBO;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI对话控制器
 * 始终加载，AI功能未配置时自动降级
 *
 * @author IAMLZY
 * @date 2026/4/12 00:16
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@RequirePermission("settings:read")
public class AIChatController {
    private static final int MAX_DELIVERY_CONTENT_LENGTH = 200;
    private static final String PROTECTED_DELIVERY_TOKEN_PREFIX = "__XY_DELIVERY_LITERAL_";
    private static final Pattern PROTECTED_DELIVERY_FRAGMENT = Pattern.compile(
            "(?:https?://|www\\.)[^\\s，。；！？]+"
                    + "|\\{[^{}\\r\\n]+}"
                    + "|(?:卡密|提取码|账号|密码|订单号|兑换码)(?:\\s*[：:]\\s*|\\s+)[^\\r\\n，。；！？;]+?(?=[,.!?](?:\\s|$)|\\r?\\n|$|\\s+(?:卡密|提取码|账号|密码|订单号|兑换码)(?:\\s*[：:]|\\s+))"
                    + "|[A-Za-z0-9][A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%+\\-]*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROTECTED_DELIVERY_TOKEN = Pattern.compile(
            Pattern.quote(PROTECTED_DELIVERY_TOKEN_PREFIX) + "(\\d+)__");
    private static final Pattern SENSITIVE_DELIVERY_FRAGMENT = Pattern.compile(
            "(?:https?://|www\\.)[^\\s，。；！？]+"
                    + "|\\{[^{}\\r\\n]+}"
                    + "|(?:卡密|提取码|账号|密码|订单号|兑换码)(?:\\s*[：:]\\s*|\\s+)[^\\r\\n，。；！？;]+?(?=[,.!?](?:\\s|$)|\\r?\\n|$|\\s+(?:卡密|提取码|账号|密码|订单号|兑换码)(?:\\s*[：:]|\\s+))"
                    + "|(?<![A-Za-z0-9])[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+[.,!?;]?(?![A-Za-z0-9])"
                    + "|(?<![A-Za-z0-9])(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?:/[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%+\\-]*)?[.,!?;]?(?![A-Za-z0-9])"
                    + "|(?<![A-Za-z0-9])[A-Za-z0-9]*\\d[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%+\\-]*(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final String UNSAFE_PROTECTED_NEIGHBORS = "._~/:?#[]@!$&'()*+,;=%+\\-";
    private final Semaphore deliveryPolishSlots = new Semaphore(1);

    @Autowired
    private AIService aiService;

    @Autowired
    private DynamicAIChatClientManager dynamicAIChatClientManager;

    @Autowired
    private SysSettingService sysSettingService;
    
    @Autowired
    private GoodsInfoService goodsInfoService;

    @Autowired
    private ItemDetailSyncService itemDetailSyncService;
    
    
    @Autowired
    private GoodsConfigService goodsConfigService;

    /**
     * AI对话（流式返回）
     * 未配置API Key时返回降级提示
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatWithAi(@Valid @RequestBody ChatWithAIReqDTO chatWithAIReqDTO) {
        return aiService.chatByRAG(chatWithAIReqDTO.getMsg(), chatWithAIReqDTO.getGoodsId());
    }

    /**
     * AI对话测试接口（流式）- 与自动回复流程一致
     * 携带固定资料和商品详情，用于测试提示词与资料效果
     */
    @PostMapping(path = "/chatTest", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatTestWithAi(@Valid @RequestBody ChatTestReqDTO req) {
        String fixedMaterial = null;
        String goodsDetail = null;
        
        if (req.getAccountId() != null && req.getGoodsId() != null) {
            XianyuGoodsConfig config = goodsConfigService.selectByAccountAndGoodsId(req.getAccountId(), req.getGoodsId());
            if (config != null) {
                fixedMaterial = config.getFixedMaterial();
            }
            
            String detailInfo = goodsInfoService.getDetailInfoByGoodsId(req.getGoodsId());
            if (detailInfo != null && !detailInfo.isEmpty()) {
                goodsDetail = detailInfo;
            }
        }
        
        return aiService.chatByRAGWithFixedMaterialStream(req.getMsg(), req.getGoodsId(), fixedMaterial, goodsDetail);
    }

    /**
     * AI状态检测接口
     * 返回AI服务是否可用、配置状态等信息
     */
    @PostMapping("/status")
    public ResultObject<AIStatusRespDTO> getAIStatus() {
        DynamicAIChatClientManager.AIStatusInfo statusInfo = dynamicAIChatClientManager.getStatusInfo();

        AIStatusRespDTO respDTO = new AIStatusRespDTO();
        respDTO.setEnabled(statusInfo.isEnabled());
        respDTO.setAvailable(statusInfo.isAvailable());
        respDTO.setApiKeyConfigured(statusInfo.isApiKeyConfigured());
        respDTO.setMessage(statusInfo.getMessage());
        respDTO.setBaseUrl(statusInfo.getBaseUrl());
        respDTO.setModel(statusInfo.getModel());

        return ResultObject.success(respDTO);
    }

    /**
     * 获取可用模型列表（OpenAI 兼容 /models 端点）。
     * 读取当前已保存的 API Key 与 Base URL，供前端在配置模型名称时选择。
     */
    @PostMapping("/models")
    public ResultObject<List<AIModelItemRespDTO>> listModels() {
        String apiKey = sysSettingService.getSettingValue("ai_api_key");
        String baseUrl = sysSettingService.getSettingValue("ai_base_url");
        if (apiKey == null || apiKey.isBlank()) {
            return ResultObject.validateFailed("请先填写并保存 API Key");
        }
        String effectiveBaseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "https://dashscope.aliyuncs.com/compatible-mode"
                : baseUrl;
        effectiveBaseUrl = OpenAiEndpointPaths.normalizeBaseUrl(effectiveBaseUrl);

        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(5));
            requestFactory.setReadTimeout(Duration.ofSeconds(15));

            RestClient restClient = RestClient.builder()
                    .requestFactory(requestFactory)
                    .baseUrl(effectiveBaseUrl)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build();

            Map<?, ?> body = restClient.get()
                    .uri(OpenAiEndpointPaths.modelsPath(effectiveBaseUrl))
                    .retrieve()
                    .body(Map.class);

            List<AIModelItemRespDTO> models = new ArrayList<>();
            if (body != null && body.get("data") instanceof List<?> dataList) {
                for (Object item : dataList) {
                    if (item instanceof Map<?, ?> modelMap) {
                        Object id = modelMap.get("id");
                        if (id != null && !String.valueOf(id).isBlank()) {
                            AIModelItemRespDTO dto = new AIModelItemRespDTO();
                            dto.setId(String.valueOf(id));
                            Object ownedBy = modelMap.get("owned_by");
                            dto.setOwnedBy(ownedBy != null ? String.valueOf(ownedBy) : null);
                            models.add(dto);
                        }
                    }
                }
            }

            if (models.isEmpty()) {
                return ResultObject.failed("未获取到可用模型，请检查 Base URL 与 API Key 是否匹配");
            }
            return ResultObject.success(models);
        } catch (Exception e) {
            log.warn("获取模型列表失败: {}", e.getMessage());
            String message = e.getMessage() == null ? "未知错误" : e.getMessage();
            if (message.contains("401") || message.contains("403")) {
                return ResultObject.failed("API Key 无效或无权限，请检查后重试");
            }
            if (message.contains("404")) {
                return ResultObject.failed("当前服务不支持 /models 接口，请手动填写模型名称");
            }
            return ResultObject.failed("获取模型列表失败: " + message);
        }
    }

    /**
     * 润色自动发货文案。链接、卡密和占位符会在调用模型前被遮蔽，并在返回后原样恢复。
     */
    @PostMapping("/polishDeliveryContent")
    public ResultObject<PolishDeliveryContentRespDTO> polishDeliveryContent(
            @Valid @RequestBody PolishDeliveryContentReqDTO req) {
        if (req == null || req.getContent() == null || req.getContent().isBlank()) {
            return ResultObject.validateFailed("发货内容不能为空");
        }

        String originalContent = req.getContent().strip();
        if (originalContent.length() > MAX_DELIVERY_CONTENT_LENGTH) {
            return ResultObject.validateFailed("发货内容不能超过200字");
        }
        if (!dynamicAIChatClientManager.isAvailable()) {
            return ResultObject.failed(1001, "请先在系统设置中配置可用的 AI 服务");
        }

        if (!deliveryPolishSlots.tryAcquire()) {
            return ResultObject.failed("已有 AI 润色请求正在处理，请稍后重试");
        }

        String polished;
        ProtectedDeliveryContent protectedContent = protectDeliveryContent(originalContent);
        try {
            polished = aiService.polishDeliveryContent(protectedContent.maskedContent());
        } finally {
            deliveryPolishSlots.release();
        }
        if (polished == null || polished.isBlank()) {
            return ResultObject.failed("AI 润色失败或请求超时，请稍后重试");
        }

        String restored = restoreDeliveryContent(polished.strip(), protectedContent);
        if (restored == null) {
            return ResultObject.failed("AI 未能完整保留链接、卡密或占位符，已保留原文");
        }
        if (hasUnexpectedSensitiveFragments(restored, protectedContent)) {
            return ResultObject.failed("AI 新增或改动了链接、卡密或占位符，已保留原文");
        }
        if (restored.isBlank()) {
            return ResultObject.failed("AI 润色结果为空，已保留原文");
        }
        if (restored.length() > MAX_DELIVERY_CONTENT_LENGTH) {
            return ResultObject.failed("AI 润色结果超过200字，已保留原文");
        }

        PolishDeliveryContentRespDTO resp = new PolishDeliveryContentRespDTO();
        resp.setContent(restored);
        return ResultObject.success(resp, "AI 润色完成");
    }

    private ProtectedDeliveryContent protectDeliveryContent(String content) {
        Matcher matcher = PROTECTED_DELIVERY_FRAGMENT.matcher(content);
        StringBuilder masked = new StringBuilder();
        List<ProtectedFragment> fragments = new ArrayList<>();
        while (matcher.find()) {
            String token = protectedToken(fragments.size());
            Character previous = matcher.start() > 0 ? content.charAt(matcher.start() - 1) : null;
            Character next = matcher.end() < content.length() ? content.charAt(matcher.end()) : null;
            fragments.add(new ProtectedFragment(matcher.group(), previous, next));
            matcher.appendReplacement(masked, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(masked);
        return new ProtectedDeliveryContent(masked.toString(), fragments);
    }

    private String restoreDeliveryContent(String polished, ProtectedDeliveryContent protectedContent) {
        Matcher tokenMatcher = PROTECTED_DELIVERY_TOKEN.matcher(polished);
        StringBuilder restored = new StringBuilder(polished.length());
        int previousEnd = 0;
        int expectedIndex = 0;

        while (tokenMatcher.find()) {
            if (polished.substring(previousEnd, tokenMatcher.start())
                    .contains(PROTECTED_DELIVERY_TOKEN_PREFIX)) {
                return null;
            }
            if (expectedIndex >= protectedContent.fragments().size()
                    || !tokenMatcher.group().equals(protectedToken(expectedIndex))) {
                return null;
            }

            ProtectedFragment fragment = protectedContent.fragments().get(expectedIndex);
            Character previous = tokenMatcher.start() > 0 ? polished.charAt(tokenMatcher.start() - 1) : null;
            Character next = tokenMatcher.end() < polished.length() ? polished.charAt(tokenMatcher.end()) : null;
            if (hasUnsafeChangedNeighbor(previous, fragment.previous())
                    || hasUnsafeChangedNeighbor(next, fragment.next())) {
                return null;
            }

            restored.append(polished, previousEnd, tokenMatcher.start());
            restored.append(fragment.value());
            previousEnd = tokenMatcher.end();
            expectedIndex++;
        }

        if (expectedIndex != protectedContent.fragments().size()
                || polished.substring(previousEnd).contains(PROTECTED_DELIVERY_TOKEN_PREFIX)) {
            return null;
        }
        restored.append(polished, previousEnd, polished.length());
        return restored.toString();
    }

    private boolean hasUnexpectedSensitiveFragments(
            String restored, ProtectedDeliveryContent protectedContent) {
        Map<String, Integer> expected = new HashMap<>();
        for (ProtectedFragment fragment : protectedContent.fragments()) {
            if (SENSITIVE_DELIVERY_FRAGMENT.matcher(fragment.value()).matches()) {
                expected.merge(fragment.value(), 1, Integer::sum);
            }
        }

        Map<String, Integer> actual = new HashMap<>();
        Matcher matcher = SENSITIVE_DELIVERY_FRAGMENT.matcher(restored);
        while (matcher.find()) {
            actual.merge(matcher.group(), 1, Integer::sum);
        }
        return !expected.equals(actual);
    }

    private boolean hasUnsafeChangedNeighbor(Character actual, Character original) {
        boolean actualIsUnsafe = actual != null && isUnsafeProtectedNeighbor(actual);
        boolean originalIsUnsafe = original != null && isUnsafeProtectedNeighbor(original);
        if (!actualIsUnsafe && !originalIsUnsafe) {
            return false;
        }
        return actual == null ? original != null : !actual.equals(original);
    }

    private boolean isUnsafeProtectedNeighbor(char value) {
        return Character.isLetterOrDigit(value)
                || UNSAFE_PROTECTED_NEIGHBORS.indexOf(value) >= 0;
    }

    private String protectedToken(int index) {
        return PROTECTED_DELIVERY_TOKEN_PREFIX + index + "__";
    }

    @PostMapping("/putNewData")
    @RequirePermission("settings:write")
    public ResultObject<?> putNewData(@Valid @RequestBody PutNewDataToRAGReqDTO putNewDataToRAGReqDTO) {
        try {
            aiService.putDataToRAG(putNewDataToRAGReqDTO.getContent(), putNewDataToRAGReqDTO.getGoodsId());
            return ResultObject.success(null);
        } catch (RuntimeException e) {
            String failureMessage = collectExceptionMessage(e);
            if (failureMessage.contains("向量库未初始化")) {
                return ResultObject.failed(1001, "请完成AI配置再上传资料");
            }
            if (failureMessage.contains("404") || failureMessage.contains("not supported")
                    || failureMessage.contains("embeddings")) {
                return ResultObject.failed("当前 Embedding 配置不可用：请在系统设置中填写支持 Embedding 的 API Key、Base URL 和模型名称");
            }
            return ResultObject.failed("资料上传失败，请检查 AI/Embedding 配置后重试");
        }
    }

    /**
     * 提取异常链中的短错误文本用于分类，不把原始异常内容直接返回或写入日志。
     */
    private String collectExceptionMessage(Throwable failure) {
        StringBuilder message = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 5) {
            if (current.getMessage() != null) {
                message.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        return message.toString().toLowerCase(java.util.Locale.ROOT);
    }

    @PostMapping("/queryRAGData")
    public ResultObject<List<RAGDataRespBO>> queryRAGData(@Valid @RequestBody PutNewDataToRAGReqDTO req) {
        List<RAGDataRespBO> data = aiService.queryRAGDataBygoodsId(req.getGoodsId());
        return ResultObject.success(data);
    }

    @PostMapping("/deleteRAGData")
    @RequirePermission("settings:write")
    public ResultObject<?> deleteRAGData(@Valid @RequestBody DeleteRAGDataReqDTO req) {
        aiService.deleteRAGDataByDocumentId(req.getDocumentId());
        return ResultObject.success(null);
    }

    @PostMapping("/saveFixedMaterial")
    @RequirePermission("settings:write")
    public ResultObject<?> saveFixedMaterial(@Valid @RequestBody FixedMaterialReqDTO req) {
        goodsConfigService.updateFixedMaterial(req.getAccountId(), req.getGoodsId(), req.getFixedMaterial());
        return ResultObject.success(null);
    }

    @PostMapping("/getFixedMaterial")
    public ResultObject<FixedMaterialRespDTO> getFixedMaterial(@Valid @RequestBody FixedMaterialReqDTO req) {
        XianyuGoodsConfig config = goodsConfigService.selectByAccountAndGoodsId(req.getAccountId(), req.getGoodsId());
        FixedMaterialRespDTO resp = new FixedMaterialRespDTO();
        if (config != null) {
            resp.setFixedMaterial(config.getFixedMaterial());
        }
        return ResultObject.success(resp);
    }

    @PostMapping("/syncDetailToFixedMaterial")
    @RequirePermission("settings:write")
    public ResultObject<?> syncDetailToFixedMaterial(@Valid @RequestBody FixedMaterialReqDTO req) {
        String detailInfo = goodsInfoService.getDetailInfoByGoodsId(req.getGoodsId());

        // “同步商品详情”应当在本地没有缓存时先尝试从已登录账号获取，
        // 否则按钮实际上只是在复制数据库字段，用户会误以为平台商品没有简介。
        if ((detailInfo == null || detailInfo.isBlank())
                && req.getAccountId() != null && req.getGoodsId() != null
                && !req.getGoodsId().isBlank()) {
            itemDetailSyncService.syncSingleItem(req.getAccountId(), req.getGoodsId());
            detailInfo = goodsInfoService.getDetailInfoByGoodsId(req.getGoodsId());
        }

        if (detailInfo != null && !detailInfo.isEmpty()) {
            goodsConfigService.updateFixedMaterial(req.getAccountId(), req.getGoodsId(), detailInfo);
            return ResultObject.success(null);
        } else {
            return ResultObject.failed("暂时无法获取商品详情：闲鱼接口返回了风控验证或登录校验，请先在闲鱼网页完成验证后刷新账号，再重试");
        }
    }

    public static class FixedMaterialReqDTO {
        private Long accountId;
        private String goodsId;
        private String fixedMaterial;

        public Long getAccountId() { return accountId; }
        public void setAccountId(Long accountId) { this.accountId = accountId; }
        public String getGoodsId() { return goodsId; }
        public void setGoodsId(String goodsId) { this.goodsId = goodsId; }
        public String getFixedMaterial() { return fixedMaterial; }
        public void setFixedMaterial(String fixedMaterial) { this.fixedMaterial = fixedMaterial; }
    }

    public static class FixedMaterialRespDTO {
        private String fixedMaterial;

        public String getFixedMaterial() { return fixedMaterial; }
        public void setFixedMaterial(String fixedMaterial) { this.fixedMaterial = fixedMaterial; }
    }

    public static class ChatTestReqDTO {
        private Long accountId;
        private String goodsId;
        private String msg;

        public Long getAccountId() { return accountId; }
        public void setAccountId(Long accountId) { this.accountId = accountId; }
        public String getGoodsId() { return goodsId; }
        public void setGoodsId(String goodsId) { this.goodsId = goodsId; }
        public String getMsg() { return msg; }
        public void setMsg(String msg) { this.msg = msg; }
    }

    public static class PolishDeliveryContentReqDTO {
        private String content;

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class PolishDeliveryContentRespDTO {
        private String content;

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    private record ProtectedDeliveryContent(String maskedContent, List<ProtectedFragment> fragments) {}

    private record ProtectedFragment(String value, Character previous, Character next) {}

    /**
     * AI状态响应DTO
     */
    public static class AIStatusRespDTO {
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

    /**
     * 可用模型条目DTO
     */
    public static class AIModelItemRespDTO {
        private String id;
        private String ownedBy;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getOwnedBy() { return ownedBy; }
        public void setOwnedBy(String ownedBy) { this.ownedBy = ownedBy; }
    }
}
