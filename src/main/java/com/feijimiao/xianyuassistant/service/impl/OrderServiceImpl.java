package com.feijimiao.xianyuassistant.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.ApiDeliveryResult;
import com.feijimiao.xianyuassistant.service.DeliveryBlockDecision;
import com.feijimiao.xianyuassistant.service.DeliveryBlockRuleService;
import com.feijimiao.xianyuassistant.service.OrderService;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryContext;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryOrderLease;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryOrderLeaseManager;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryReservation;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryStrategyResolver;
import com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils;
import com.feijimiao.xianyuassistant.adapter.xianyu.XianyuAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 订单服务实现
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    private static final int MAX_TRADE_TEXT_LENGTH = 200;
    
    @Autowired
    private AccountService accountService;
    
    @Autowired
    private XianyuAdapter xianyuAdapter;

    @Autowired
    private XianyuGoodsOrderMapper orderMapper;

    @Autowired
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;

    @Autowired
    private DeliveryStrategyResolver deliveryStrategyResolver;

    @Autowired
    private DeliveryBlockRuleService deliveryBlockRuleService;

    @Autowired
    private DeliveryOrderLeaseManager deliveryOrderLeaseManager;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String confirmShipment(Long accountId, String orderId) {
        return confirmShipmentToXianyu(accountId, orderId);
    }

    @Override
    public ApiDeliveryResult consignDummyDelivery(
            Long accountId, String orderId, String tradeText, List<String> imageUrls) {
        try {
            log.info("【账号{}】开始调用闲鱼新发货API(虚拟发货): orderId={}", accountId, orderId);

            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("【账号{}】未找到Cookie", accountId);
                return ApiDeliveryResult.FAILED;
            }

            if (tradeText != null && tradeText.length() > MAX_TRADE_TEXT_LENGTH) {
                log.error("【账号{}】发货内容超过{}字，拒绝调用虚拟发货API: orderId={}, length={}",
                        accountId, MAX_TRADE_TEXT_LENGTH, orderId, tradeText.length());
                return ApiDeliveryResult.FAILED;
            }
            String limitedText = tradeText;

            List<String> limitedImages = new ArrayList<>();
            if (imageUrls != null && !imageUrls.isEmpty()) {
                int limit = Math.min(imageUrls.size(), 3);
                limitedImages = imageUrls.subList(0, limit);
                if (imageUrls.size() > 3) {
                    log.info("【账号{}】发货图片超过3张，已截断", accountId);
                }
            }

            String picListJson;
            if (limitedImages.isEmpty()) {
                picListJson = "[]";
            } else {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < limitedImages.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(limitedImages.get(i)).append("\"");
                }
                sb.append("]");
                picListJson = sb.toString();
            }

            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("orderId", orderId);
            dataMap.put("tradeText", limitedText != null ? limitedText : "");
            dataMap.put("picList", picListJson);
            dataMap.put("newUnconsign", true);

            Map<String, String> extraHeaders = new HashMap<>();
            extraHeaders.put("idle_site_biz_code", "COMMONPRO");
            extraHeaders.put("Origin", "https://seller.goofish.com");
            extraHeaders.put("Referer", "https://seller.goofish.com/?site=COMMONPRO");

            XianyuApiCallUtils.ApiCallResult result = xianyuAdapter.callApi(
                    accountId,
                    "mtop.taobao.idle.logistics.merchant.consign.dummy",
                    dataMap,
                    cookieStr,
                    extraHeaders
            );

            if (!result.isSuccess()) {
                String errorMsg = result.getErrorMessage();
                log.error("【账号{}】❌ 闲鱼新发货API失败: errorLength={}",
                        accountId, errorMsg != null ? errorMsg.length() : 0);

                if (result.isTokenExpired()) {
                    return ApiDeliveryResult.FAILED;
                }

                if (errorMsg != null && errorMsg.contains("ORDER_ALREADY_DELIVERY")) {
                    log.info("【账号{}】订单已发货(ORDER_ALREADY_DELIVERY)，视为成功: orderId={}", accountId, orderId);
                    return ApiDeliveryResult.SUCCESS;
                }

                return result.isOutcomeUnknown()
                        ? ApiDeliveryResult.UNKNOWN
                        : ApiDeliveryResult.FAILED;
            }

            log.info("【账号{}】✅ 闲鱼新发货API成功: orderId={}", accountId, orderId);
            return ApiDeliveryResult.SUCCESS;

        } catch (Exception e) {
            // 请求可能已经写出（例如序列化/签名之后、等待响应期间抛异常），此时无法判定
            // 闲鱼侧是否已落发货文本。判 FAILED 会触发卡密回滚并导致同一张卡二次售卖，
            // 因此一律按"结果未知"处理，交由人工/补偿核对。
            log.error("【账号{}】调用闲鱼新发货API异常，结果未知: orderId={}, errorType={}",
                    accountId, orderId, e.getClass().getSimpleName());
            return ApiDeliveryResult.UNKNOWN;
        }
    }
    
    @Override
    public String confirmShipmentToXianyu(Long accountId, String orderId) {
        try {
            log.info("【账号{}】开始调用闲鱼API确认发货: orderId={}", accountId, orderId);
            
            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("【账号{}】未找到Cookie", accountId);
                return null;
            }
            
            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("orderId", orderId);
            dataMap.put("tradeText", "");
            dataMap.put("picList", new String[0]);
            dataMap.put("newUnconsign", true);
            
            log.info("【账号{}】确认发货参数已构建: fieldCount={}", accountId, dataMap.size());
            
            XianyuApiCallUtils.ApiCallResult result = xianyuAdapter.callApi(
                    accountId, 
                    "mtop.taobao.idle.logistic.consign.dummy", 
                    dataMap, 
                    cookieStr
            );
            
            if (!result.isSuccess()) {
                String errorMsg = result.getErrorMessage();
                log.error("【账号{}】❌ 闲鱼API确认发货失败: errorLength={}",
                        accountId, errorMsg != null ? errorMsg.length() : 0);
                
                if (result.isTokenExpired()) {
                    return "令牌过期，请稍后重试或手动更新Cookie";
                }

                if (errorMsg != null && errorMsg.contains("ORDER_ALREADY_DELIVERY")) {
                    log.info("【账号{}】订单已发货(ORDER_ALREADY_DELIVERY)，视为确认成功: orderId={}", accountId, orderId);
                    return "确认发货成功(已发货)";
                }
                
                return null;
            }
            
            Map<String, Object> responseData = result.extractData();
            if (responseData != null) {
                log.info("【账号{}】✅ 闲鱼API确认发货成功: orderId={}", accountId, orderId);
                return "确认发货成功";
            } else {
                log.error("【账号{}】响应数据格式错误", accountId);
                return null;
            }
            
        } catch (Exception e) {
            log.error("【账号{}】调用闲鱼API确认发货异常: orderId={}, errorType={}",
                    accountId, orderId, e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public String getOrderDetail(Long accountId, String orderId) {
        try {
            log.info("【账号{}】开始获取订单详情: orderId={}", accountId, orderId);

            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("【账号{}】未找到Cookie", accountId);
                return null;
            }

            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("tid", orderId);

            XianyuApiCallUtils.ApiCallResult result = xianyuAdapter.callApi(
                    accountId,
                    "mtop.taobao.idle.trade.merchant.full.info",
                    dataMap,
                    cookieStr
            );

            if (!result.isSuccess()) {
                String errorMessage = result.getErrorMessage();
                log.warn("【账号{}】获取订单详情失败: orderId={}, errorLength={}",
                        accountId, orderId, errorMessage != null ? errorMessage.length() : 0);
                return null;
            }

            Map<String, Object> responseData = result.extractData();
            if (responseData == null) {
                log.warn("【账号{}】订单详情响应数据为空: orderId={}", accountId, orderId);
                return null;
            }

            String json = objectMapper.writeValueAsString(responseData);
            log.info("【账号{}】获取订单详情成功: orderId={}", accountId, orderId);

            updateOrderDetailFromApi(accountId, orderId, responseData);

            return json;
        } catch (Exception e) {
            log.error("【账号{}】获取订单详情异常: orderId={}, errorType={}",
                    accountId, orderId, e.getClass().getSimpleName());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void updateOrderDetailFromApi(Long accountId, String orderId, Map<String, Object> responseData) {
        try {
            XianyuGoodsOrder order = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
            if (order == null) {
                log.debug("【账号{}】本地无此订单记录，跳过更新: orderId={}", accountId, orderId);
                return;
            }

            Object moduleObj = responseData.get("module");
            if (!(moduleObj instanceof Map)) return;
            Map<String, Object> module = (Map<String, Object>) moduleObj;

            String buyerUserName = null;
            Object merchantBuyerVO = module.get("merchantBuyerVO");
            if (merchantBuyerVO instanceof Map) {
                Map<String, Object> buyer = (Map<String, Object>) merchantBuyerVO;
                Object userNick = buyer.get("userNick");
                if (userNick instanceof String) buyerUserName = (String) userNick;
            }

            String orderCreateTime = null;
            String paySuccessTime = null;
            String consignTime = null;
            Object merchantCommonData = module.get("merchantCommonData");
            if (merchantCommonData instanceof Map) {
                Map<String, Object> commonData = (Map<String, Object>) merchantCommonData;
                Object ct = commonData.get("createTime");
                if (ct instanceof String) orderCreateTime = (String) ct;
                Object pt = commonData.get("paySuccessTime");
                if (pt instanceof String) paySuccessTime = (String) pt;
                Object ct2 = commonData.get("consignTime");
                if (ct2 instanceof String) consignTime = (String) ct2;
            }

            String goodsTitle = null;
            Object merchantItemVO = module.get("merchantItemVO");
            if (merchantItemVO instanceof Map) {
                Map<String, Object> merchantItem = (Map<String, Object>) merchantItemVO;
                Object title = merchantItem.get("title");
                if (title instanceof String) goodsTitle = (String) title;
            }

            String totalPrice = null;
            Integer buyNum = null;
            Object merchantPriceVO = module.get("merchantPriceVO");
            if (merchantPriceVO instanceof Map) {
                Map<String, Object> priceVO = (Map<String, Object>) merchantPriceVO;
                Object tp = priceVO.get("totalPrice");
                if (tp instanceof String) totalPrice = (String) tp;
                Object bn = priceVO.get("buyNum");
                if (bn instanceof String) {
                    try { buyNum = Integer.parseInt((String) bn); } catch (Exception e) { buyNum = 1; }
                } else if (bn instanceof Number) {
                    buyNum = ((Number) bn).intValue();
                }
            }

            orderMapper.updateOrderDetail(order.getId(), buyerUserName, orderCreateTime, paySuccessTime, consignTime, null, goodsTitle, totalPrice, buyNum);
            log.info("【账号{}】从API更新订单详情成功: orderId={}", accountId, orderId);
        } catch (Exception e) {
            log.warn("【账号{}】更新订单详情失败: orderId={}, errorType={}",
                    accountId, orderId, e.getClass().getSimpleName());
        }
    }

    @Override
    public String getOrderDetailFromLocal(Long accountId, String orderId) {
        try {
            XianyuGoodsOrder order = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
            if (order == null) {
                log.warn("【账号{}】本地未找到订单: orderId={}", accountId, orderId);
                return null;
            }
            Map<String, Object> result = new HashMap<>();
            result.put("orderId", order.getOrderId());
            result.put("xyGoodsId", order.getXyGoodsId());
            result.put("goodsTitle", order.getGoodsTitle());
            result.put("buyerUserName", order.getBuyerUserName());
            result.put("content", order.getContent());
            result.put("state", order.getState());
            result.put("failReason", order.getFailReason());
            result.put("confirmState", order.getConfirmState());
            result.put("createTime", order.getCreateTime());
            result.put("skuName", order.getSkuName());
            result.put("orderCreateTime", order.getOrderCreateTime());
            result.put("paySuccessTime", order.getPaySuccessTime());
            result.put("consignTime", order.getConsignTime());
            result.put("totalPrice", order.getTotalPrice());
            result.put("buyNum", order.getBuyNum());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("【账号{}】获取本地订单详情异常: orderId={}, errorType={}",
                    accountId, orderId, e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryPendingOrders(Long accountId) {
        try {
            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("【账号{}】未找到Cookie", accountId);
                return List.of();
            }

            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("pageNumber", 1);
            dataMap.put("rowsPerPage", 20);
            dataMap.put("orderIds", "");
            dataMap.put("queryCode", "NOT_SHIP");
            dataMap.put("orderSearchParam", "{}");

            Map<String, String> extraHeaders = new HashMap<>();
            extraHeaders.put("idle_site_biz_code", "COMMONPRO");
            extraHeaders.put("Origin", "https://seller.goofish.com");
            extraHeaders.put("Referer", "https://seller.goofish.com/?site=COMMONPRO");

            Map<String, String> extraQueryParams = new HashMap<>();
            extraQueryParams.put("type", "json");
            extraQueryParams.put("valueType", "string");

            XianyuApiCallUtils.ApiCallResult result = xianyuAdapter.callApi(
                    accountId,
                    "mtop.taobao.idle.trade.merchant.sold.get",
                    dataMap,
                    cookieStr,
                    extraHeaders,
                    extraQueryParams
            );

            if (!result.isSuccess()) {
                String errorMessage = result.getErrorMessage();
                log.warn("【账号{}】查询待发货订单失败: errorLength={}",
                        accountId, errorMessage != null ? errorMessage.length() : 0);
                return List.of();
            }

            Map<String, Object> responseData = result.extractData();
            if (responseData == null) {
                return List.of();
            }

            Object moduleObj = responseData.get("module");
            if (!(moduleObj instanceof Map)) return List.of();
            Map<String, Object> module = (Map<String, Object>) moduleObj;

            Object itemsObj = module.get("items");
            if (!(itemsObj instanceof List)) return List.of();
            return (List<Map<String, Object>>) itemsObj;
        } catch (Exception e) {
            log.error("【账号{}】查询待发货订单异常: errorType={}",
                    accountId, e.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> getOrderDetailMap(Long accountId, String orderId) {
        try {
            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                return null;
            }

            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("tid", orderId);

            XianyuApiCallUtils.ApiCallResult result = xianyuAdapter.callApi(
                    accountId,
                    "mtop.taobao.idle.trade.merchant.full.info",
                    dataMap,
                    cookieStr
            );

            if (!result.isSuccess()) {
                String errorMessage = result.getErrorMessage();
                log.warn("【账号{}】获取订单详情失败: orderId={}, errorLength={}",
                        accountId, orderId, errorMessage != null ? errorMessage.length() : 0);
                return null;
            }

            return result.extractData();
        } catch (Exception e) {
            log.warn("【账号{}】获取订单详情异常: orderId={}, errorType={}",
                    accountId, orderId, e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public ApiDeliveryResult consignDummyDeliveryWithConfig(Long accountId, String xyGoodsId, String orderId) {
        Optional<DeliveryOrderLease> leaseHolder = deliveryOrderLeaseManager.tryAcquire(accountId, orderId);
        if (leaseHolder.isEmpty()) {
            // 同一订单已有人在发货：这里绝不能继续取卡，否则会与对方各扣一张，
            // 或在失败时把对方已经发出的卡回滚回库存。
            log.warn("【账号{}】该订单已有发货流程在执行，本次凭证发货跳过: orderId={}", accountId, orderId);
            return ApiDeliveryResult.SKIPPED;
        }
        try (DeliveryOrderLease lease = leaseHolder.get()) {
            return doConsignDummyDeliveryWithConfig(accountId, xyGoodsId, orderId);
        }
    }

    private ApiDeliveryResult doConsignDummyDeliveryWithConfig(Long accountId, String xyGoodsId, String orderId) {
        log.info("【账号{}】带配置凭证发货: xyGoodsId={}, orderId={}", accountId, xyGoodsId, orderId);

        XianyuGoodsAutoDeliveryConfig deliveryConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdAndSkuId(accountId, xyGoodsId, null);
        if (deliveryConfig == null) {
            deliveryConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
        }

        if (deliveryConfig == null) {
            log.warn("【账号{}】商品无发货配置，请先配置自动发货: xyGoodsId={}", accountId, xyGoodsId);
            throw new BusinessException(89282,"商品无发货配置，请先配置自动发货");
        }

        int deliveryMode = deliveryConfig.getDeliveryMode() != null ? deliveryConfig.getDeliveryMode() : 1;

        // 从订单记录中获取 sId 和 buyerUserName，供卡密发货策略使用
        XianyuGoodsOrder existingOrder = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
        String sId = null;
        String buyerUserName = null;
        if (existingOrder != null) {
            sId = existingOrder.getSid();
            buyerUserName = existingOrder.getBuyerUserName();
        }
        if (sId == null || sId.isEmpty()) {
            sId = orderId; // fallback
        }

        DeliveryBlockDecision blockDecision = deliveryBlockRuleService.evaluate(
                accountId, xyGoodsId, orderId, existingOrder != null ? existingOrder.getBuyerUserId() : null);
        if (blockDecision.blocked()) {
            String failReason = "发货风控拦截: " + blockDecision.reason();
            if (existingOrder != null) {
                orderMapper.updateStateContentAndFailReason(existingOrder.getId(), -1, null, failReason);
            }
            log.warn("【账号{}】凭证发货已被风控规则阻止: orderId={}, ruleCode={}",
                    accountId, orderId, blockDecision.ruleCode());
            return ApiDeliveryResult.FAILED;
        }

        int deliveryUnitCount = 1;
        if (deliveryMode == 2) {
            Integer buyNum = existingOrder != null ? existingOrder.getBuyNum() : null;
            if (buyNum == null || buyNum <= 0) {
                log.error("【账号{}】卡密凭证发货缺少有效购买数量，拒绝按单件发货: orderId={}", accountId, orderId);
                return ApiDeliveryResult.FAILED;
            }
            deliveryUnitCount = buyNum;
        }

        DeliveryContext ctx = DeliveryContext.builder()
                .accountId(accountId)
                .xyGoodsId(xyGoodsId)
                .orderId(orderId)
                .sId(sId)
                .buyerUserName(buyerUserName)
                .deliveryConfig(deliveryConfig)
                .reservation(DeliveryReservation.none())
                .build();

        List<String> contents = new ArrayList<>();
        List<DeliveryReservation> reservations = new ArrayList<>();
        try {
            for (int i = 0; i < deliveryUnitCount; i++) {
                ctx.setActionId(orderId + "#delivery-" + (i + 1));
                ctx.setReservation(DeliveryReservation.none());
                String unitContent = deliveryStrategyResolver.resolve(deliveryMode, ctx);
                DeliveryReservation reservation = currentReservation(ctx);
                if (unitContent == null) {
                    reservation.rollback();
                    rollbackReservations(reservations);
                    String failMsg = deliveryMode == 1
                            ? "未配置发货内容"
                            : (deliveryMode == 2 ? "卡密库存不足，无可用卡密" : "未知的发货模式: " + deliveryMode);
                    log.warn("【账号{}】发货内容解析失败: {}", accountId, failMsg);
                    return ApiDeliveryResult.FAILED;
                }
                contents.add(unitContent);
                reservations.add(reservation);
            }
        } catch (Exception e) {
            try {
                currentReservation(ctx).rollback();
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            try {
                rollbackReservations(reservations);
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }

        String content = String.join("\n", contents);
        if (content.length() > MAX_TRADE_TEXT_LENGTH) {
            rollbackReservations(reservations);
            log.error("【账号{}】带配置凭证发货内容超过{}字，已释放全部预占: orderId={}, length={}",
                    accountId, MAX_TRADE_TEXT_LENGTH, orderId, content.length());
            return ApiDeliveryResult.FAILED;
        }

        List<String> imageUrls = new ArrayList<>();
        String imageUrlStr = deliveryConfig.getAutoDeliveryImageUrl();
        if (imageUrlStr != null && !imageUrlStr.trim().isEmpty()) {
            for (String url : imageUrlStr.split(",")) {
                String trimmed = url.trim();
                if (!trimmed.isEmpty()) imageUrls.add(trimmed);
            }
        }

        log.info("【账号{}】带配置凭证发货: orderId={}, deliveryMode={}, contentLen={}, imageCount={}", accountId, orderId, deliveryMode, content.length(), imageUrls.size());
        ApiDeliveryResult result;
        try {
            result = consignDummyDelivery(accountId, orderId, content, imageUrls);
        } catch (Exception e) {
            rollbackReservations(reservations);
            throw e;
        }

        if (result == ApiDeliveryResult.SUCCESS) {
            reservations.forEach(DeliveryReservation::commit);
            if (existingOrder != null) {
                orderMapper.updateStateAndContent(existingOrder.getId(), 1, content);
                orderMapper.updateConfirmState(accountId, orderId);
                log.info("【账号{}】凭证发货成功，已更新订单状态: orderId={}", accountId, orderId);
            } else {
                XianyuGoodsOrder record = new XianyuGoodsOrder();
                record.setXianyuAccountId(accountId);
                record.setXyGoodsId(xyGoodsId);
                record.setOrderId(orderId);
                record.setPnmId("api_" + orderId);
                record.setContent(content);
                record.setState(1);
                record.setConfirmState(1);
                orderMapper.insert(record);
                log.info("【账号{}】凭证发货成功，已新建订单记录: orderId={}", accountId, orderId);
            }
        } else if (result == ApiDeliveryResult.UNKNOWN) {
            String unknownReason = deliveryMode == 2
                    ? "虚拟发货结果未知，请人工核对；卡密已保留预占"
                    : "虚拟发货结果未知，请人工核对";
            if (existingOrder != null) {
                orderMapper.updateStateContentAndFailReason(existingOrder.getId(), 0, content, unknownReason);
            } else {
                XianyuGoodsOrder record = new XianyuGoodsOrder();
                record.setXianyuAccountId(accountId);
                record.setXyGoodsId(xyGoodsId);
                record.setOrderId(orderId);
                record.setPnmId("api_" + orderId);
                record.setContent(content);
                record.setState(0);
                record.setConfirmState(0);
                record.setFailReason(unknownReason);
                orderMapper.insert(record);
            }
            log.warn("【账号{}】凭证发货结果未知，已保留资源并等待人工核对: orderId={}", accountId, orderId);
        } else {
            rollbackReservations(reservations);
        }

        return result;
    }

    private DeliveryReservation currentReservation(DeliveryContext context) {
        return context.getReservation() != null ? context.getReservation() : DeliveryReservation.none();
    }

    private void rollbackReservations(List<DeliveryReservation> reservations) {
        RuntimeException firstFailure = null;
        for (int i = reservations.size() - 1; i >= 0; i--) {
            try {
                reservations.get(i).rollback();
            } catch (RuntimeException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
