package com.feijimiao.xianyuassistant.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.controller.dto.DeliveryBlockRuleDTO;
import com.feijimiao.xianyuassistant.entity.XianyuDeliveryBlockRule;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.mapper.XianyuDeliveryBlockRuleMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.DeliveryBlockDecision;
import com.feijimiao.xianyuassistant.service.DeliveryBlockRuleService;
import com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils;
import com.feijimiao.xianyuassistant.adapter.xianyu.XianyuAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class DeliveryBlockRuleServiceImpl implements DeliveryBlockRuleService {

    static final String BUYER_CREDIT_ZERO = "buyer_credit_zero";
    static final String BUYER_HAS_ORDER = "buyer_has_order";
    static final String BUYER_UNCONFIRMED = "buyer_unconfirmed";

    private static final int MAX_PRIORITY = 999;
    private static final int MAX_THRESHOLD = 100;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_EXCLUDED_ITEMS = 200;

    private static final List<RuleMetadata> METADATA = List.of(
            new RuleMetadata(BUYER_CREDIT_ZERO, "买家信用度检查",
                    "买家被评价数小于等于阈值时禁止发货；接口异常时放行", 10, Map.of("threshold", 0)),
            new RuleMetadata(BUYER_HAS_ORDER, "买家已有订单",
                    "买家在当前账号下已有其他本地订单时禁止发货", 20, Map.of("same_item_only", false)),
            new RuleMetadata(BUYER_UNCONFIRMED, "买家存在未确认收货订单",
                    "平台订单列表中已发货但未完成的订单达到阈值时禁止发货；接口异常时放行",
                    30, Map.of("min_count", 1, "same_item_only", false))
    );

    @Autowired
    private XianyuDeliveryBlockRuleMapper ruleMapper;

    @Autowired
    private XianyuGoodsOrderMapper orderMapper;

    @Autowired
    private AccountService accountService;

    @Autowired
    private XianyuAdapter xianyuAdapter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<DeliveryBlockRuleDTO> getRules(Long accountId) {
        Map<String, XianyuDeliveryBlockRule> stored = new HashMap<>();
        if (accountId != null) {
            for (XianyuDeliveryBlockRule rule : ruleMapper.selectByAccountId(accountId)) {
                stored.put(rule.getRuleCode(), rule);
            }
        }

        List<DeliveryBlockRuleDTO> result = new ArrayList<>();
        for (RuleMetadata metadata : METADATA) {
            result.add(toDto(metadata, stored.get(metadata.code())));
        }
        result.sort(Comparator.comparingInt(DeliveryBlockRuleDTO::getPriority));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<DeliveryBlockRuleDTO> saveRules(Long accountId, List<DeliveryBlockRuleDTO> rules) {
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException("账号ID无效");
        }
        Map<String, DeliveryBlockRuleDTO> requested = new LinkedHashMap<>();
        if (rules != null) {
            for (DeliveryBlockRuleDTO rule : rules) {
                if (rule != null && metadata(rule.getRuleCode()) != null) {
                    requested.put(rule.getRuleCode(), rule);
                }
            }
        }

        ruleMapper.deleteByAccountId(accountId);
        for (RuleMetadata metadata : METADATA) {
            DeliveryBlockRuleDTO normalized = normalize(metadata, requested.get(metadata.code()));
            XianyuDeliveryBlockRule entity = new XianyuDeliveryBlockRule();
            entity.setXianyuAccountId(accountId);
            entity.setRuleCode(metadata.code());
            entity.setEnabled(normalized.getEnabled());
            entity.setPriority(normalized.getPriority());
            entity.setBlockReason(normalized.getBlockReason());
            entity.setExcludedItemIds(writeJson(normalized.getExcludedItemIds()));
            entity.setConfigJson(writeJson(normalized.getConfig()));
            ruleMapper.insert(entity);
        }
        return getRules(accountId);
    }

    @Override
    public DeliveryBlockDecision evaluate(Long accountId, String xyGoodsId, String orderId, String buyerUserId) {
        try {
            if (accountId == null) {
                return DeliveryBlockDecision.allow();
            }
            String resolvedBuyerId = trimToNull(buyerUserId);
            if (resolvedBuyerId == null && trimToNull(orderId) != null) {
                XianyuGoodsOrder currentOrder = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
                resolvedBuyerId = currentOrder != null ? trimToNull(currentOrder.getBuyerUserId()) : null;
            }
            if (resolvedBuyerId == null) {
                log.warn("【账号{}】发货风控缺少买家ID，按放行处理: orderId={}", accountId, orderId);
                return DeliveryBlockDecision.allow();
            }

            for (DeliveryBlockRuleDTO rule : getRules(accountId)) {
                if (!Integer.valueOf(1).equals(rule.getEnabled())) {
                    continue;
                }
                if (xyGoodsId != null && rule.getExcludedItemIds().contains(xyGoodsId)) {
                    continue;
                }

                DeliveryBlockDecision decision = switch (rule.getRuleCode()) {
                    case BUYER_CREDIT_ZERO -> evaluateCredit(rule, accountId, resolvedBuyerId);
                    case BUYER_HAS_ORDER -> evaluateExistingOrder(
                            rule, accountId, xyGoodsId, orderId, resolvedBuyerId);
                    case BUYER_UNCONFIRMED -> evaluateUnconfirmed(
                            rule, accountId, xyGoodsId, orderId, resolvedBuyerId);
                    default -> DeliveryBlockDecision.allow();
                };
                if (decision.blocked()) {
                    log.warn("【账号{}】发货风控命中: ruleCode={}, orderId={}, reason={}",
                            accountId, decision.ruleCode(), orderId, decision.reason());
                    return decision;
                }
            }
        } catch (Exception e) {
            log.error("【账号{}】发货风控执行异常，按放行处理: orderId={}, errorType={}",
                    accountId, orderId, e.getClass().getSimpleName());
        }
        return DeliveryBlockDecision.allow();
    }

    private DeliveryBlockDecision evaluateCredit(DeliveryBlockRuleDTO rule, Long accountId, String buyerUserId) {
        int threshold = intConfig(rule, "threshold", 0, 0, MAX_THRESHOLD);
        String cookie = accountService.getCookieByAccountId(accountId);
        if (trimToNull(cookie) == null) {
            log.warn("【账号{}】信用度规则缺少Cookie，跳过本规则", accountId);
            return DeliveryBlockDecision.allow();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rateType", 0);
        data.put("ratedUid", buyerUserId);
        data.put("raterType", 0);
        data.put("rowsPerPage", 20);
        data.put("pageNumber", 1);
        data.put("foldFlag", 0);
        data.put("fishAdCode", "330110");
        data.put("extraTag", "");

        XianyuApiCallUtils.ApiCallResult result = xianyuAdapter.callApi(
                accountId, "mtop.idle.web.trade.rate.list", data, cookie);
        if (!result.isSuccess()) {
            log.warn("【账号{}】信用度接口失败，跳过本规则", accountId);
            return DeliveryBlockDecision.allow();
        }
        Map<String, Object> responseData = result.extractData();
        Integer totalCount = parseInteger(responseData != null ? responseData.get("totalCount") : null);
        if (totalCount == null) {
            log.warn("【账号{}】信用度接口缺少totalCount，跳过本规则", accountId);
            return DeliveryBlockDecision.allow();
        }
        if (totalCount <= threshold) {
            return block(rule, "买家评价数为" + totalCount + "（阈值" + threshold + "）");
        }
        return DeliveryBlockDecision.allow();
    }

    private DeliveryBlockDecision evaluateExistingOrder(DeliveryBlockRuleDTO rule, Long accountId,
                                                        String xyGoodsId, String orderId, String buyerUserId) {
        boolean sameItemOnly = booleanConfig(rule, "same_item_only", false);
        int count = orderMapper.countOtherOrdersByBuyer(
                accountId, buyerUserId, orderId, sameItemOnly ? xyGoodsId : null);
        if (count > 0) {
            return block(rule, "买家已有" + count + "笔其他订单");
        }
        return DeliveryBlockDecision.allow();
    }

    private DeliveryBlockDecision evaluateUnconfirmed(DeliveryBlockRuleDTO rule, Long accountId,
                                                      String xyGoodsId, String orderId, String buyerUserId) {
        int minCount = intConfig(rule, "min_count", 1, 1, MAX_THRESHOLD);
        boolean sameItemOnly = booleanConfig(rule, "same_item_only", false);
        int count = countPlatformUnconfirmedOrders(
                accountId, buyerUserId, orderId, sameItemOnly ? xyGoodsId : null, minCount);
        if (count < 0) {
            return DeliveryBlockDecision.allow();
        }
        if (count >= minCount) {
            return block(rule, "买家有" + count + "笔已发货未确认收货订单");
        }
        return DeliveryBlockDecision.allow();
    }

    @SuppressWarnings("unchecked")
    private int countPlatformUnconfirmedOrders(Long accountId, String buyerUserId, String currentOrderId,
                                               String itemId, int stopAt) {
        String cookie = accountService.getCookieByAccountId(accountId);
        if (trimToNull(cookie) == null) {
            return -1;
        }
        int count = 0;
        for (int page = 1; page <= 5; page++) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pageNumber", page);
            data.put("rowsPerPage", 30);
            data.put("orderIds", "");
            data.put("queryCode", "ALL");
            data.put("orderSearchParam", "{}");

            Map<String, String> headers = Map.of(
                    "idle_site_biz_code", "COMMONPRO",
                    "Origin", "https://seller.goofish.com",
                    "Referer", "https://seller.goofish.com/");
            Map<String, String> query = Map.of("type", "json", "valueType", "string");
            XianyuApiCallUtils.ApiCallResult result = xianyuAdapter.callApi(
                    accountId, "mtop.taobao.idle.trade.merchant.sold.get", data, cookie, headers, query);
            if (!result.isSuccess()) {
                log.warn("【账号{}】未确认收货规则查询平台订单失败，跳过本规则", accountId);
                return -1;
            }
            Map<String, Object> responseData = result.extractData();
            Object moduleObject = responseData != null ? responseData.get("module") : null;
            if (!(moduleObject instanceof Map<?, ?> module)) {
                return -1;
            }
            Object itemsObject = module.get("items");
            if (!(itemsObject instanceof List<?> items)) {
                return -1;
            }
            for (Object itemObject : items) {
                if (!(itemObject instanceof Map<?, ?> item)) continue;
                Map<String, Object> common = mapValue(item.get("commonData"));
                Map<String, Object> buyer = mapValue(item.get("buyerInfoVO"));
                String candidateOrderId = stringValue(common.get("orderId"));
                String candidateItemId = stringValue(common.get("itemId"));
                String candidateBuyerId = firstNonBlank(
                        stringValue(buyer.get("buyerId")), stringValue(buyer.get("userId")));
                if (!"已发货".equals(stringValue(common.get("orderStatus")))) continue;
                if (currentOrderId != null && currentOrderId.equals(candidateOrderId)) continue;
                if (!buyerUserId.equals(candidateBuyerId)) continue;
                if (itemId != null && !itemId.equals(candidateItemId)) continue;
                count++;
                if (count >= stopAt) return count;
            }
            Object nextPage = module.get("nextPage");
            if (!Boolean.parseBoolean(String.valueOf(nextPage))) {
                break;
            }
        }
        return count;
    }

    private DeliveryBlockDecision block(DeliveryBlockRuleDTO rule, String detail) {
        String customReason = trimToNull(rule.getBlockReason());
        String reason = rule.getRuleName() + "：" + detail
                + (customReason != null ? "；备注：" + customReason : "");
        return DeliveryBlockDecision.block(rule.getRuleCode(), reason, customReason);
    }

    private DeliveryBlockRuleDTO toDto(RuleMetadata metadata, XianyuDeliveryBlockRule stored) {
        DeliveryBlockRuleDTO dto = new DeliveryBlockRuleDTO();
        dto.setRuleCode(metadata.code());
        dto.setRuleName(metadata.name());
        dto.setRuleDescription(metadata.description());
        dto.setEnabled(stored != null && Integer.valueOf(1).equals(stored.getEnabled()) ? 1 : 0);
        dto.setPriority(stored != null ? clamp(stored.getPriority(), 0, MAX_PRIORITY, metadata.priority()) : metadata.priority());
        dto.setBlockReason(stored != null ? truncate(stored.getBlockReason(), MAX_REASON_LENGTH) : "");
        dto.setExcludedItemIds(stored != null ? readStringList(stored.getExcludedItemIds()) : new ArrayList<>());
        dto.setConfig(normalizeConfig(metadata, stored != null ? readMap(stored.getConfigJson()) : metadata.defaultConfig()));
        return dto;
    }

    private DeliveryBlockRuleDTO normalize(RuleMetadata metadata, DeliveryBlockRuleDTO requested) {
        DeliveryBlockRuleDTO dto = new DeliveryBlockRuleDTO();
        dto.setRuleCode(metadata.code());
        dto.setRuleName(metadata.name());
        dto.setRuleDescription(metadata.description());
        dto.setEnabled(requested != null && Integer.valueOf(1).equals(requested.getEnabled()) ? 1 : 0);
        dto.setPriority(clamp(requested != null ? requested.getPriority() : null,
                0, MAX_PRIORITY, metadata.priority()));
        dto.setBlockReason(truncate(requested != null ? requested.getBlockReason() : null, MAX_REASON_LENGTH));
        dto.setExcludedItemIds(normalizeItemIds(requested != null ? requested.getExcludedItemIds() : null));
        dto.setConfig(normalizeConfig(metadata, requested != null ? requested.getConfig() : null));
        return dto;
    }

    private Map<String, Object> normalizeConfig(RuleMetadata metadata, Map<String, Object> source) {
        Map<String, Object> config = source != null ? source : metadata.defaultConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        switch (metadata.code()) {
            case BUYER_CREDIT_ZERO -> result.put("threshold",
                    clamp(parseInteger(config.get("threshold")), 0, MAX_THRESHOLD, 0));
            case BUYER_HAS_ORDER -> result.put("same_item_only",
                    parseBoolean(config.get("same_item_only"), false));
            case BUYER_UNCONFIRMED -> {
                result.put("min_count", clamp(parseInteger(config.get("min_count")), 1, MAX_THRESHOLD, 1));
                result.put("same_item_only", parseBoolean(config.get("same_item_only"), false));
            }
            default -> { }
        }
        return result;
    }

    private List<String> normalizeItemIds(List<String> itemIds) {
        if (itemIds == null) return new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String itemId : itemIds) {
            String normalized = trimToNull(itemId);
            if (normalized != null) unique.add(truncate(normalized, 100));
            if (unique.size() >= MAX_EXCLUDED_ITEMS) break;
        }
        return new ArrayList<>(unique);
    }

    private int intConfig(DeliveryBlockRuleDTO rule, String key, int fallback, int min, int max) {
        return clamp(parseInteger(rule.getConfig().get(key)), min, max, fallback);
    }

    private boolean booleanConfig(DeliveryBlockRuleDTO rule, String key, boolean fallback) {
        return parseBoolean(rule.getConfig().get(key), fallback);
    }

    private RuleMetadata metadata(String code) {
        if (code == null) return null;
        return METADATA.stream().filter(item -> item.code().equals(code)).findFirst().orElse(null);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("规则配置无法序列化", e);
        }
    }

    private List<String> readStringList(String json) {
        if (trimToNull(json) == null) return new ArrayList<>();
        try {
            return normalizeItemIds(objectMapper.readValue(json, new TypeReference<List<String>>() { }));
        } catch (Exception e) {
            log.warn("发货风控排除商品配置损坏，已使用空列表");
            return new ArrayList<>();
        }
    }

    private Map<String, Object> readMap(String json) {
        if (trimToNull(json) == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("发货风控规则参数损坏，已使用默认配置");
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private Integer parseInteger(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private boolean parseBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        }
        return fallback;
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        int resolved = value != null ? value : fallback;
        return Math.max(min, Math.min(max, resolved));
    }

    private String truncate(String value, int length) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= length ? trimmed : trimmed.substring(0, length);
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private String firstNonBlank(String first, String second) {
        return trimToNull(first) != null ? trimToNull(first) : trimToNull(second);
    }

    private record RuleMetadata(String code, String name, String description,
                                int priority, Map<String, Object> defaultConfig) { }
}
