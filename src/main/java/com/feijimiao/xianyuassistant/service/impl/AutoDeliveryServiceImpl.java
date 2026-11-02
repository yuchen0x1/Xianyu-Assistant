package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoReplyRecord;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.feijimiao.xianyuassistant.service.ApiDeliveryResult;
import com.feijimiao.xianyuassistant.service.AutoDeliveryService;
import com.feijimiao.xianyuassistant.service.DeliveryBlockDecision;
import com.feijimiao.xianyuassistant.service.DeliveryBlockRuleService;
import com.feijimiao.xianyuassistant.service.EmailNotifyService;
import com.feijimiao.xianyuassistant.service.MessageSendResult;
import com.feijimiao.xianyuassistant.service.OrderService;
import com.feijimiao.xianyuassistant.service.WebSocketService;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryContext;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryOrderLease;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryOrderLeaseManager;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryReservation;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryStrategyResolver;
import com.feijimiao.xianyuassistant.service.delivery.OrderDetailFetcher;
import com.feijimiao.xianyuassistant.utils.HumanLikeDelayUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * 自动发货服务实现类（编排层）
 *
 * <p>负责发货流程的编排，具体逻辑委托给 delivery 包下的组件：</p>
 * <ul>
 *   <li>{@link OrderDetailFetcher} - 订单详情获取与解析</li>
 *   <li>{@link DeliveryStrategyResolver} - 发货内容策略解析（文本/卡密/自定义）</li>
 * </ul>
 */
@Slf4j
@Service
public class AutoDeliveryServiceImpl implements AutoDeliveryService {

    private static final int MAX_VIRTUAL_TRADE_TEXT_LENGTH = 200;

    /** 连续发货失败告警阈值（次数） */
    private static final int DELIVERY_FAILURE_ALERT_THRESHOLD = 3;
    
    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;
    
    @Autowired
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    
    @Autowired
    private XianyuGoodsOrderMapper orderMapper;
    
    @Autowired
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;
    
    @Lazy
    @Autowired
    private WebSocketService webSocketService;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.SentMessageSaveService sentMessageSaveService;

    @Autowired
    private EmailNotifyService emailNotifyService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderDetailFetcher orderDetailFetcher;

    @Autowired
    private DeliveryStrategyResolver deliveryStrategyResolver;

    @Autowired
    private DeliveryBlockRuleService deliveryBlockRuleService;

    @Autowired
    private DeliveryOrderLeaseManager deliveryOrderLeaseManager;

    @Autowired
    private com.feijimiao.xianyuassistant.monitor.AlertService alertService;

    @Autowired
    private com.feijimiao.xianyuassistant.webhook.service.WebhookService webhookService;

    /**
     * 自动确认发货属于可控的后台工作，必须进入应用统一线程池。
     * 直接创建线程会绕过队列、并发上限以及优雅停机等待。
     */
    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;
    
    @Override
    public XianyuGoodsConfig getGoodsConfig(Long accountId, String xyGoodsId) {
        return goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
    }
    
    @Override
    public XianyuGoodsAutoDeliveryConfig getAutoDeliveryConfig(Long accountId, String xyGoodsId) {
        return autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
    }
    
    @Override
    public void saveOrUpdateGoodsConfig(XianyuGoodsConfig config) {
        XianyuGoodsConfig existing = goodsConfigMapper.selectByAccountAndGoodsId(
                config.getXianyuAccountId(), config.getXyGoodsId());

        if (existing == null) {
            try {
                goodsConfigMapper.insert(config);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发 check-then-insert 被 V3_24 唯一索引拦截：转为更新已存在行
                existing = goodsConfigMapper.selectByAccountAndGoodsId(
                        config.getXianyuAccountId(), config.getXyGoodsId());
                if (existing == null) {
                    throw e;
                }
            }
        }
        config.setId(existing.getId());
        goodsConfigMapper.update(config);
    }
    
    @Override
    public void saveOrUpdateAutoDeliveryConfig(XianyuGoodsAutoDeliveryConfig config) {
        String skuId = config.getSkuId();
        if (skuId != null && skuId.isEmpty()) {
            skuId = null;
            config.setSkuId(null);
        }
        XianyuGoodsAutoDeliveryConfig existingConfig;
        if (skuId != null) {
            existingConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdAndSkuId(
                    config.getXianyuAccountId(), config.getXyGoodsId(), skuId);
        } else {
            existingConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(
                    config.getXianyuAccountId(), config.getXyGoodsId());
        }
        
        if (existingConfig == null) {
            autoDeliveryConfigMapper.insert(config);
        } else {
            config.setId(existingConfig.getId());
            autoDeliveryConfigMapper.updateById(config);
        }
    }
    
    @Override
    public void recordAutoDelivery(Long accountId, String xyGoodsId, String buyerUserId, String buyerUserName, String content, Integer state) {
        recordAutoDelivery(accountId, xyGoodsId, buyerUserId, buyerUserName, content, state, null, null);
    }
    
    public void recordAutoDelivery(Long accountId, String xyGoodsId, String buyerUserId, String buyerUserName, 
                                   String content, Integer state, String pnmId, String orderId) {
        recordAutoDelivery(accountId, xyGoodsId, buyerUserId, buyerUserName,
                content, state, pnmId, orderId, null);
    }

    private void recordAutoDelivery(Long accountId, String xyGoodsId, String buyerUserId, String buyerUserName,
                                    String content, Integer state, String pnmId, String orderId, String failReason) {
        XianyuGoodsOrder record = new XianyuGoodsOrder();
        record.setXianyuAccountId(accountId);
        record.setXyGoodsId(xyGoodsId);
        record.setBuyerUserId(buyerUserId);
        record.setBuyerUserName(buyerUserName);
        record.setContent(content);
        record.setState(state);
        record.setFailReason(failReason);
        // pnm_id 参与 (account_id, pnm_id) 唯一索引：真实消息路径携带消息 pnmId；
        // 无消息上下文时用订单号派生确定性键（同订单重复记录被索引拦下=幂等），
        // 两者皆缺才用随机键，绝不能恒写空串——否则同账号第二笔发货记录必撞唯一索引丢失。
        if (pnmId != null && !pnmId.isBlank()) {
            record.setPnmId(pnmId);
        } else if (orderId != null && !orderId.isBlank()) {
            record.setPnmId("order:" + orderId);
        } else {
            record.setPnmId(java.util.UUID.randomUUID().toString());
        }
        record.setOrderId(orderId != null ? orderId : "");
        record.setConfirmState(0);

        orderMapper.insert(record);
    }
    
    @Override
    public void handleAutoDelivery(Long accountId, String xyGoodsId, String sId, String buyerUserId, String buyerUserName) {
        handleAutoDelivery(accountId, xyGoodsId, sId, buyerUserId, buyerUserName, null);
    }
    

    /**
     * 自动发货统一入口：先按商品配置解析内容；若 WebSocket 已连接则走实时消息发送，
     * 否则自动退化为闲鱼虚拟发货 API。注意：该 API 兜底由本次调用自动完成，
     * 不依赖 {@link ApiDeliveryScheduler}。
     */
    public void handleAutoDelivery(Long accountId, String xyGoodsId, String sId, String buyerUserId, String buyerUserName, String orderId) {
        try {
            log.info("【账号{}】处理自动发货: xyGoodsId={}, sessionPresent={}, buyerPresent={}, orderId={}",
                    accountId, xyGoodsId, sId != null, buyerUserId != null || buyerUserName != null, orderId);
            
            XianyuGoodsConfig goodsConfig = getGoodsConfig(accountId, xyGoodsId);
            if (goodsConfig == null || goodsConfig.getXianyuAutoDeliveryOn() == null || goodsConfig.getXianyuAutoDeliveryOn() != 1) {
                log.info("【账号{}】商品未开启自动发货: xyGoodsId={}", accountId, xyGoodsId);
                return;
            }
            
            XianyuGoodsAutoDeliveryConfig deliveryConfig = getAutoDeliveryConfig(accountId, xyGoodsId);
            if (deliveryConfig == null || deliveryConfig.getAutoDeliveryContent() == null || 
                    deliveryConfig.getAutoDeliveryContent().isEmpty()) {
                log.warn("【账号{}】商品未配置自动发货内容: xyGoodsId={}", accountId, xyGoodsId);
                recordAutoDelivery(accountId, xyGoodsId, buyerUserId, buyerUserName, null, 0, null, orderId);
                return;
            }
            
            String content = deliveryConfig.getAutoDeliveryContent();
            log.info("【账号{}】准备发送自动发货消息: contentLength={}", accountId, content.length());

            HumanLikeDelayUtils.mediumDelay();
            HumanLikeDelayUtils.thinkingDelay();
            HumanLikeDelayUtils.typingDelay(content.length());

            if (sId == null || sId.isBlank()) {
                // 无法定位会话则明确记为发货失败，避免 NPE 被 catch 后记成含义模糊的异常记录
                log.warn("【账号{}】缺少会话ID，无法发送自动发货消息: xyGoodsId={}, orderId={}",
                        accountId, xyGoodsId, orderId);
                recordAutoDelivery(accountId, xyGoodsId, buyerUserId, buyerUserName,
                        null, -1, null, orderId, "会话ID缺失，无法发送");
                return;
            }

            String cid = sId.replace("@goofish", "");
            String toId = cid;
            
            MessageSendResult sendResult = webSocketService.sendMessageWithOutcome(accountId, cid, toId, content);
            int state = sendResult == MessageSendResult.SUCCESS
                    ? 1
                    : (sendResult == MessageSendResult.UNKNOWN ? 0 : -1);
            String failReason = sendResult == MessageSendResult.UNKNOWN
                    ? "消息发送结果未知，请人工核对"
                    : (sendResult == MessageSendResult.FAILED ? "消息发送失败" : null);

            recordAutoDelivery(accountId, xyGoodsId, buyerUserId, buyerUserName,
                    content, state, null, orderId, failReason);

            if (sendResult == MessageSendResult.SUCCESS) {
                log.info("【账号{}】自动发货成功: xyGoodsId={}, contentLength={}",
                        accountId, xyGoodsId, content.length());
                sentMessageSaveService.saveAiAssistantReply(accountId, cid, toId, content, xyGoodsId);
            } else if (sendResult == MessageSendResult.UNKNOWN) {
                log.warn("【账号{}】自动发货结果未知，需人工核对: xyGoodsId={}", accountId, xyGoodsId);
            } else {
                log.error("【账号{}】自动发货失败: xyGoodsId={}", accountId, xyGoodsId);
            }
            
        } catch (Exception e) {
            log.error("【账号{}】自动发货异常: xyGoodsId={}", accountId, xyGoodsId, e);
            recordAutoDelivery(accountId, xyGoodsId, buyerUserId, buyerUserName, null, 0, null, orderId);
        }
    }
    
    @Override
    public void handleAutoReply(Long accountId, String xyGoodsId, String sId, String buyerMessage) {
        log.info("【账号{}】自动回复功能已移除: xyGoodsId={}", accountId, xyGoodsId);
    }
    
    private void recordAutoReply(Long accountId, String xyGoodsId, String buyerMessage, 
                                  String replyContent, String matchedKeyword, Integer state) {
        try {
            XianyuGoodsAutoReplyRecord record = new XianyuGoodsAutoReplyRecord();
            record.setXianyuAccountId(accountId);
            record.setXyGoodsId(xyGoodsId);
            record.setBuyerMessage(buyerMessage);
            record.setReplyContent(replyContent);
            record.setMatchedKeyword(matchedKeyword);
            record.setState(state);
            
            autoReplyRecordMapper.insert(record);
        } catch (Exception e) {
            log.error("【账号{}】记录自动回复失败", accountId, e);
        }
    }
    
    @Override
    public com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordRespDTO getAutoDeliveryRecords(
            com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordReqDTO reqDTO) {
        
        Long accountId = reqDTO.getXianyuAccountId();
        String xyGoodsId = reqDTO.getXyGoodsId();
        Integer state = reqDTO.getState();
        String keyword = reqDTO.getKeyword();
        int pageNum = reqDTO.getPageNum() != null ? reqDTO.getPageNum() : 1;
        int pageSize = reqDTO.getPageSize() != null ? reqDTO.getPageSize() : 20;
        
        int offset = (pageNum - 1) * pageSize;
        
        List<XianyuGoodsOrder> records = orderMapper.selectByAccountIdWithPage(
                accountId, xyGoodsId, state, keyword, pageSize, offset);
        
        long total = orderMapper.countByAccountId(accountId, xyGoodsId, state, keyword);
        
        List<com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordDTO> recordDTOs = new ArrayList<>();
        for (XianyuGoodsOrder record : records) {
            com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordDTO dto = 
                    new com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordDTO();
            dto.setId(record.getId());
            dto.setXianyuAccountId(record.getXianyuAccountId());
            dto.setXyGoodsId(record.getXyGoodsId());
            dto.setGoodsTitle(record.getGoodsTitle());
            dto.setBuyerUserName(record.getBuyerUserName());
            dto.setContent(record.getContent());
            dto.setState(record.getState());
            dto.setConfirmState(record.getConfirmState());
            dto.setOrderId(record.getOrderId());
            dto.setSkuName(record.getSkuName());
            dto.setOrderCreateTime(record.getOrderCreateTime() != null ? record.getOrderCreateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
            dto.setPaySuccessTime(record.getPaySuccessTime());
            dto.setConsignTime(record.getConsignTime());
            dto.setTotalPrice(record.getTotalPrice());
            dto.setBuyNum(record.getBuyNum());
            dto.setCreateTime(record.getCreateTime() != null ? record.getCreateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
            recordDTOs.add(dto);
        }
        
        com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordRespDTO respDTO = 
                new com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordRespDTO();
        respDTO.setRecords(recordDTOs);
        respDTO.setTotal(total);
        respDTO.setPageNum(pageNum);
        respDTO.setPageSize(pageSize);
        
        return respDTO;
    }

    @Override
    public com.feijimiao.xianyuassistant.common.ResultObject<String> triggerAutoDelivery(
            com.feijimiao.xianyuassistant.controller.dto.TriggerAutoDeliveryReqDTO reqDTO) {
        try {
            Long accountId = reqDTO.getXianyuAccountId();
            String xyGoodsId = reqDTO.getXyGoodsId();
            String orderId = reqDTO.getOrderId();
            Boolean needHumanLikeDelay = reqDTO.getNeedHumanLikeDelay() != null ? reqDTO.getNeedHumanLikeDelay() : false;

            log.info("【账号{}】触发自动发货: xyGoodsId={}, orderId={}, needHumanLikeDelay={}", 
                    accountId, xyGoodsId, orderId, needHumanLikeDelay);

            XianyuGoodsOrder record = orderMapper.selectByOrderId(accountId, xyGoodsId, orderId);
            if (record == null) {
                log.warn("【账号{}】发货记录不存在: orderId={}", accountId, orderId);
                return com.feijimiao.xianyuassistant.common.ResultObject.failed("发货记录不存在");
            }

            String pnmId = record.getPnmId();
            if (pnmId == null || pnmId.isEmpty()) {
                log.warn("【账号{}】发货记录没有pnmId: orderId={}", accountId, orderId);
                return com.feijimiao.xianyuassistant.common.ResultObject.failed("发货记录没有pnmId");
            }

            Long recordId = record.getId();
            String sId = record.getSid() != null ? record.getSid() : record.getBuyerUserId() + "@goofish";
            String buyerUserName = record.getBuyerUserName();

            // 先校验商品是否开启自动发货；若未开启直接记录失败并中断。
            // 先校验商品是否开启自动发货；若未开启直接记录失败并中断。
                                    XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
            if (goodsConfig == null || goodsConfig.getXianyuAutoDeliveryOn() == null || goodsConfig.getXianyuAutoDeliveryOn() != 1) {
                log.info("【账号{}】商品未开启自动发货: xyGoodsId={}", accountId, xyGoodsId);
                return com.feijimiao.xianyuassistant.common.ResultObject.failed("商品未开启自动发货");
            }

            executeDelivery(recordId, accountId, xyGoodsId, sId, orderId, buyerUserName, needHumanLikeDelay);

            XianyuGoodsOrder updatedRecord = orderMapper.selectByOrderId(accountId, xyGoodsId, orderId);
            if (updatedRecord != null && updatedRecord.getState() == 1) {
                return com.feijimiao.xianyuassistant.common.ResultObject.success("触发自动发货成功");
            } else if (deliveryOrderLeaseManager.isHeld(accountId, orderId)) {
                // 本次触发被并发发货流程挡下，订单状态由持有者写入，不能报成"发货失败"
                return com.feijimiao.xianyuassistant.common.ResultObject.failed("该订单正在发货中，请稍后刷新订单状态");
            } else {
                String failReason = updatedRecord != null ? updatedRecord.getFailReason() : "未知错误";
                return com.feijimiao.xianyuassistant.common.ResultObject.failed(failReason != null ? failReason : "发货失败");
            }

        } catch (Exception e) {
            log.error("【账号{}】触发自动发货失败: xyGoodsId={}, orderId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getOrderId(), e);
            return com.feijimiao.xianyuassistant.common.ResultObject.failed("触发自动发货失败: " + e.getMessage());
        }
    }

    @Override
    public void executeDelivery(Long recordId, Long accountId, String xyGoodsId, String sId, String orderId, String buyerUserName, boolean needHumanLikeDelay) {
        Optional<DeliveryOrderLease> leaseHolder = deliveryOrderLeaseManager.tryAcquire(accountId, orderId);
        if (leaseHolder.isEmpty()) {
            // 同一订单已有发货流程在跑（实时事件 / API 兜底 / 手动触发）。这里直接放弃，
            // 由持有者写最终状态；重复执行会重复扣卡，失败方还可能回滚成功方已交付的卡。
            log.warn("【账号{}】该订单已有发货流程在执行，跳过本次重复触发: recordId={}, orderId={}",
                    accountId, recordId, orderId);
            return;
        }
        try (DeliveryOrderLease lease = leaseHolder.get()) {
            if (alreadyDeliveredByAnotherRecord(recordId, accountId, orderId)) {
                return;
            }
            doExecuteDelivery(recordId, accountId, xyGoodsId, sId, orderId, buyerUserName, needHumanLikeDelay);
        }
    }

    /**
     * 发货前置校验：同一订单若已由另一条记录完成发货，直接跳过。
     *
     * <p>事件去重只覆盖 pnmId 相同的重复推送；若平台对同一订单换了 pnmId 再推一次，
     * 会生成第二条待发货记录并二次发货。这里以订单终态兜底。</p>
     */
    private boolean alreadyDeliveredByAnotherRecord(Long recordId, Long accountId, String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return false;
        }
        try {
            if (orderMapper.countDeliveredByOtherRecord(accountId, orderId, recordId) > 0) {
                log.warn("【账号{}】订单已由其他记录完成发货，跳过重复触发: recordId={}, orderId={}",
                        accountId, recordId, orderId);
                return true;
            }
        } catch (RuntimeException e) {
            // 校验失败不阻断发货：动作幂等标识仍然能防止重复取卡
            log.warn("【账号{}】发货前订单状态校验失败，继续执行: orderId={}, errorType={}",
                    accountId, orderId, e.getClass().getSimpleName());
        }
        return false;
    }

    private void doExecuteDelivery(Long recordId, Long accountId, String xyGoodsId, String sId, String orderId, String buyerUserName, boolean needHumanLikeDelay) {
        // 一旦置 1 就再也不能被本次执行的异常路径改回失败
        boolean delivered = false;
        try {
            log.info("【账号{}】开始执行自动发货: recordId={}, xyGoodsId={}, orderId={}", accountId, recordId, xyGoodsId, orderId);

            XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
            if (goodsConfig == null || goodsConfig.getXianyuAutoDeliveryOn() == null || goodsConfig.getXianyuAutoDeliveryOn() != 1) {
                log.warn("【账号{}】商品未开启自动发货: xyGoodsId={}", accountId, xyGoodsId);
                updateRecordState(recordId, -1, null, "商品未开启自动发货");
                return;
            }

            OrderDetailFetcher.OrderDetailInfo orderDetail = orderDetailFetcher.fetch(accountId, xyGoodsId, orderId);
            if (orderDetail == null && orderId != null && !orderId.isEmpty()) {
                log.warn("【账号{}】获取订单详情失败(可能Cookie过期或API异常)，中断发货: orderId={}", accountId, orderId);
                String failReason = "获取订单详情失败(可能Cookie过期)，请检查Cookie状态";
                updateRecordState(recordId, -1, null, failReason);
                emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failReason);
                notifyDeliveryFailure(accountId, xyGoodsId, orderId, failReason);
                return;
            }
            String orderSkuId = orderDetail != null ? orderDetail.skuId : null;
            int buyNum = (orderDetail != null && orderDetail.buyNum != null && orderDetail.buyNum > 0)
                    ? orderDetail.buyNum
                    : 1;
            if (orderDetail != null && (orderDetail.buyNum == null || orderDetail.buyNum <= 0)) {
                // 数量缺失时只能按 1 件发，但必须留痕：买家拍多件时会少发，
                // 需要人工核对而不是静默按 1 件处理。
                log.warn("【账号{}】订单数量缺失，按 1 件发货，请核对是否需要补发: orderId={}",
                        accountId, orderId);
            }
            log.info("【账号{}】订单SKU: orderId={}, skuId={}, buyNum={}", accountId, orderId, orderSkuId, buyNum);

            if (orderDetail != null) {
                orderMapper.updateOrderDetail(recordId, orderDetail.buyerUserName, orderDetail.orderCreateTime, orderDetail.paySuccessTime, orderDetail.consignTime, orderDetail.skuName, orderDetail.goodsTitle, orderDetail.totalPrice, orderDetail.buyNum);
            }

            XianyuGoodsOrder currentOrder = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
            String buyerUserId = currentOrder != null ? currentOrder.getBuyerUserId() : null;
            DeliveryBlockDecision blockDecision = deliveryBlockRuleService.evaluate(
                    accountId, xyGoodsId, orderId, buyerUserId);
            if (blockDecision.blocked()) {
                String failReason = "发货风控拦截: " + blockDecision.reason();
                updateRecordState(recordId, -1, null, failReason);
                log.warn("【账号{}】自动发货已被风控规则阻止: orderId={}, ruleCode={}",
                        accountId, orderId, blockDecision.ruleCode());
                return;
            }

            XianyuGoodsAutoDeliveryConfig deliveryConfig = null;
            if (orderSkuId != null && !orderSkuId.isEmpty()) {
                deliveryConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdAndSkuId(accountId, xyGoodsId, orderSkuId);
            }
            if (deliveryConfig == null) {
                deliveryConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
            }

            if (deliveryConfig == null) {
                log.warn("【账号{}】商品无匹配的发货配置: xyGoodsId={}, skuId={}", accountId, xyGoodsId, orderSkuId);
                updateRecordState(recordId, -1, null, "无匹配的发货配置");
                return;
            }

            int deliveryMode = deliveryConfig.getDeliveryMode() != null ? deliveryConfig.getDeliveryMode() : 1;
            String cid = sId.replace("@goofish", "");
            String toId = cid;
            boolean wsConnected = webSocketService.isConnected(accountId);
            // 实时连接可用时走 WebSocket；断线时自动退回虚拟发货 API，并保留/回滚卡密资源。
            StringBuilder allContent = new StringBuilder();

            DeliveryContext ctx = DeliveryContext.builder()
                    .recordId(recordId)
                    .accountId(accountId)
                    .xyGoodsId(xyGoodsId)
                    .sId(sId)
                    .orderId(orderId)
                    .buyerUserName(buyerUserName)
                    .deliveryConfig(deliveryConfig)
                    .build();

            if (!wsConnected) {
                log.info("【账号{}】WebSocket未连接，使用虚拟发货API: orderId={}", accountId, orderId);
                // 自动发货 API 兜底：无论订单来自 WebSocket 事件还是手动触发，只要当前离线都会走这里。
                List<DeliveryReservation> reservations = new ArrayList<>();
                List<String> contents = new ArrayList<>();
                int virtualUnitCount = deliveryMode == 2 ? buyNum : 1;
                try {
                    for (int i = 0; i < virtualUnitCount; i++) {
                        ctx.setActionId(buildDeliveryActionId(recordId, orderId, i));
                        ctx.setReservation(DeliveryReservation.none());
                        String unitContent = deliveryStrategyResolver.resolve(deliveryMode, ctx);
                        if (unitContent == null) {
                            currentReservation(ctx).rollback();
                            rollbackReservations(reservations);
                            String failMsg = buildResolveFailureMessage(deliveryMode);
                            log.warn("【账号{}】发货内容解析失败: {}", accountId, failMsg);
                            updateRecordState(recordId, -1, joinContents(contents), failMsg);
                            emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failMsg);
                            return;
                        }
                        contents.add(unitContent);
                        reservations.add(currentReservation(ctx));
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
                String content = joinContents(contents);
                if (content.length() > MAX_VIRTUAL_TRADE_TEXT_LENGTH) {
                    rollbackReservations(reservations);
                    String failMsg = "虚拟发货内容超过200字";
                    log.error("【账号{}】{}，拒绝调用API: orderId={}, length={}",
                            accountId, failMsg, orderId, content.length());
                    updateRecordState(recordId, -1, content, failMsg);
                    emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failMsg);
                    return;
                }

                List<String> imageUrls = new ArrayList<>();
                String imageUrlStr = deliveryConfig.getAutoDeliveryImageUrl();
                if (imageUrlStr != null && !imageUrlStr.trim().isEmpty()) {
                    for (String url : imageUrlStr.split(",")) {
                        String trimmed = url.trim();
                        if (!trimmed.isEmpty()) imageUrls.add(trimmed);
                    }
                }

                ApiDeliveryResult deliveryResult;
                try {
                    deliveryResult = orderService.consignDummyDelivery(accountId, orderId, content, imageUrls);
                } catch (Exception e) {
                    rollbackReservations(reservations);
                    throw e;
                }
                if (deliveryResult == ApiDeliveryResult.SUCCESS) {
                    reservations.forEach(DeliveryReservation::commit);
                    allContent.append(content);
                    log.info("【账号{}】✅ 虚拟发货API成功: recordId={}", accountId, recordId);
                    sentMessageSaveService.saveAiAssistantReply(accountId, cid, toId, content, xyGoodsId);
                } else if (deliveryResult == ApiDeliveryResult.UNKNOWN) {
                    String unknownReason = deliveryMode == 2
                            ? "虚拟发货结果未知，请人工核对；卡密已保留预占"
                            : "虚拟发货结果未知，请人工核对";
                    log.warn("【账号{}】虚拟发货结果未知，保留资源等待人工核对: recordId={}", accountId, recordId);
                    updateRecordState(recordId, 0, content, unknownReason);
                    emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, unknownReason);
                    notifyDeliveryFailure(accountId, xyGoodsId, orderId, unknownReason);
                    return;
                } else {
                    rollbackReservations(reservations);
                    log.error("【账号{}】❌ 虚拟发货API失败: recordId={}", accountId, recordId);
                    updateRecordState(recordId, -1, content, "虚拟发货API失败");
                    emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, "虚拟发货API失败");
                    notifyDeliveryFailure(accountId, xyGoodsId, orderId, "虚拟发货API失败");
                    return;
                }
            } else {

                for (int i = 0; i < buyNum; i++) {
                    log.info("【账号{}】发货第{}/{}次: orderId={}", accountId, i + 1, buyNum, orderId);

                    ctx.setActionId(buildDeliveryActionId(recordId, orderId, i));
                    ctx.setReservation(DeliveryReservation.none());
                    String content = deliveryStrategyResolver.resolve(deliveryMode, ctx);
                    DeliveryReservation reservation = currentReservation(ctx);

                    if (content == null) {
                        String failMsg = buildResolveFailureMessage(deliveryMode);
                        log.warn("【账号{}】发货内容解析失败: {}", accountId, failMsg);
                        updateRecordState(recordId, -1, allContent.toString(), failMsg);
                        emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failMsg);
                        return;
                    }

                    if (needHumanLikeDelay) {
                        if (i > 0) {
                            HumanLikeDelayUtils.thinkingDelay();
                        }
                        HumanLikeDelayUtils.mediumDelay();
                        HumanLikeDelayUtils.thinkingDelay();
                        HumanLikeDelayUtils.typingDelay(content.length());
                    }

                    log.info("【账号{}】准备发送发货文本[{}/{}]: content长度={}, deliveryMode={}", accountId, i + 1, buyNum, content.length(), deliveryMode);
                    MessageSendResult textResult;
                    try {
                        textResult = webSocketService.sendMessageWithOutcome(accountId, cid, toId, content);
                    } catch (Exception e) {
                        // 发送方法抛出异常时，消息可能已经写出到连接。判 FAILED 会立即回滚卡密，
                        // 而买家可能已收到内容，因此按 UNKNOWN 处理（保留预占，走人工核对）。
                        log.error("【账号{}】发货消息本地发送异常[{}/{}]，结果未知: errorType={}",
                                accountId, i + 1, buyNum, e.getClass().getSimpleName());
                        textResult = MessageSendResult.UNKNOWN;
                    }

                    if (textResult == MessageSendResult.FAILED) {
                        reservation.rollback();
                        String failMsg = String.format("第%d/%d件消息发送失败", i + 1, buyNum);
                        log.error("【账号{}】❌ 发货失败[{}/{}]: recordId={}", accountId, i + 1, buyNum, recordId);
                        updateRecordState(recordId, -1, allContent.toString(), failMsg);
                        emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failMsg);
                        notifyDeliveryFailure(accountId, xyGoodsId, orderId, failMsg);
                        return;
                    }

                    if (textResult == MessageSendResult.UNKNOWN) {
                        // The socket write may have reached the buyer. Releasing the card
                        // here could sell the same credential twice, but we also cannot claim
                        // it was delivered. Freeze it as pending and let the sweeper resolve it.
                        reservation.hold();
                        appendContent(allContent, content);
                        String unknownMsg = buildUnknownSendMessage(i + 1, buyNum, "消息", deliveryMode);
                        log.warn("【账号{}】发货结果未知[{}/{}]，停止后续发送: recordId={}",
                                accountId, i + 1, buyNum, recordId);
                        updateRecordState(recordId, 0, allContent.toString(), unknownMsg);
                        emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, unknownMsg);
                        notifyDeliveryFailure(accountId, xyGoodsId, orderId, unknownMsg);
                        return;
                    }

                    // The server acknowledged delivery. From this point the card must not
                    // be returned to stock even if a later image or unit fails.
                    reservation.commit();
                    appendContent(allContent, content);
                    log.info("【账号{}】✅ 发货成功[{}/{}]: recordId={}, deliveryMode={}", accountId, i + 1, buyNum, recordId, deliveryMode);
                    sentMessageSaveService.saveAiAssistantReply(accountId, cid, toId, content, xyGoodsId);

                    if (needHumanLikeDelay) {
                        HumanLikeDelayUtils.thinkingDelay();
                    }

                    MessageSendResult imageResult = sendDeliveryImages(
                            accountId, xyGoodsId, cid, toId, deliveryConfig, needHumanLikeDelay);
                    if (imageResult != MessageSendResult.SUCCESS) {
                        boolean unknown = imageResult == MessageSendResult.UNKNOWN;
                        String outcomeMsg = unknown
                                ? buildUnknownSendMessage(i + 1, buyNum, "图片", deliveryMode)
                                : String.format("第%d/%d件图片发送失败", i + 1, buyNum);
                        updateRecordState(recordId, unknown ? 0 : -1, allContent.toString(), outcomeMsg);
                        emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, outcomeMsg);
                        notifyDeliveryFailure(accountId, xyGoodsId, orderId, outcomeMsg);
                        return;
                    }
                }

            } // end else (wsConnected)

            updateRecordState(recordId, 1, allContent.toString(), null);
            delivered = true;
            try {
                webhookService.dispatch("delivery.completed", java.util.Map.of(
                        "accountId", accountId,
                        "orderId", orderId != null ? orderId : "",
                        "goodsId", xyGoodsId));
            } catch (Exception e) {
                log.warn("Webhook 发货成功事件推送失败: accountId={}, orderId={}", accountId, orderId, e);
            }
            // 自动确认发货属于发货成功后的附加动作：失败只记日志，不能把已经成功的
            // 订单改写成失败，更不能触发失败邮件与 delivery.failed 事件。
            try {
                XianyuGoodsAutoDeliveryConfig baseConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
                boolean autoConfirm = (baseConfig != null && baseConfig.getAutoConfirmShipment() != null && baseConfig.getAutoConfirmShipment() == 1);
                if (autoConfirm) {
                    log.info("【账号{}】检测到自动确认发货开关已开启，准备自动确认发货: orderId={}", accountId, orderId);
                    executeAutoConfirmShipment(accountId, orderId);
                }
            } catch (Exception e) {
                log.warn("【账号{}】发货已成功，但自动确认发货失败（不影响发货状态）: orderId={}, errorType={}",
                        accountId, orderId, e.getClass().getSimpleName());
            }

} catch (Exception e) {
            if (delivered) {
                // 卡密与消息都已经送达，这里只可能是收尾动作异常。保持 state=1，
                // 避免"页面显示失败 + 兜底再发一次"。
                log.error("【账号{}】发货已完成，但收尾流程异常（订单状态保持已发货）: recordId={}, errorType={}",
                        accountId, recordId, e.getClass().getSimpleName(), e);
                return;
            }
            log.error("【账号{}】执行自动发货异常: recordId={}, xyGoodsId={}", accountId, recordId, xyGoodsId, e);
            String failReason = "发货异常: " + e.getMessage();
            updateRecordState(recordId, -1, null, failReason);
            emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failReason);
            notifyDeliveryFailure(accountId, xyGoodsId, orderId, failReason);
        }
    }

    /**
     * 发货失败 Webhook 事件（最小接线：只传 accountId/orderId/goodsId，不携带 Cookie/Token）。
     */
    private void notifyDeliveryFailure(Long accountId, String xyGoodsId, String orderId, String failReason) {
        try {
            webhookService.dispatch("delivery.failed", java.util.Map.of(
                    "accountId", accountId,
                    "orderId", orderId != null ? orderId : "",
                    "goodsId", xyGoodsId,
                    "reason", failReason != null ? failReason : ""));
            // 保留既有告警：按账号最近连续失败次数触发（当天 state=-1 记录数，阈值 3）。
            int recentFailures = orderMapper.countRecentFailures(accountId);
            alertService.checkDeliveryFailures(accountId, recentFailures, DELIVERY_FAILURE_ALERT_THRESHOLD);
        } catch (Exception e) {
            log.warn("发货失败告警/Webhook 触发失败: accountId={}, errorType={}", accountId, e.getClass().getSimpleName());
        }
    }

    private MessageSendResult sendDeliveryImages(Long accountId, String xyGoodsId, String cid, String toId,
                                                 XianyuGoodsAutoDeliveryConfig deliveryConfig,
                                                 boolean needHumanLikeDelay) {
        String imageUrlStr = deliveryConfig.getAutoDeliveryImageUrl();
        if (imageUrlStr == null || imageUrlStr.trim().isEmpty()) {
            return MessageSendResult.SUCCESS;
        }
        String[] imageUrls = imageUrlStr.split(",");
        for (int i = 0; i < imageUrls.length; i++) {
            try {
                String url = imageUrls[i].trim();
                if (url.isEmpty()) continue;
                if (i > 0) {
                    if (needHumanLikeDelay) {
                        HumanLikeDelayUtils.thinkingDelay();
                    } else {
                        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
                MessageSendResult imageResult = webSocketService.sendImageMessageWithOutcome(
                        accountId, cid, toId, url, 800, 800);
                if (imageResult == MessageSendResult.SUCCESS) {
                    log.info("【账号{}】自动发货图片[{}/{}]发送成功: xyGoodsId={}", accountId, i + 1, imageUrls.length, xyGoodsId);
                    sentMessageSaveService.saveManualImageReply(accountId, cid, toId, url, xyGoodsId);
                } else {
                    log.warn("【账号{}】自动发货图片[{}/{}]发送未确认: xyGoodsId={}, result={}",
                            accountId, i + 1, imageUrls.length, xyGoodsId, imageResult);
                    return imageResult;
                }
            } catch (Exception e) {
                log.error("【账号{}】自动发货图片[{}/{}]发送异常: xyGoodsId={}", accountId, i + 1, imageUrls.length, xyGoodsId, e);
                return MessageSendResult.FAILED;
            }
        }
        return MessageSendResult.SUCCESS;
    }

    private void appendContent(StringBuilder allContent, String content) {
        if (allContent.length() > 0) {
            allContent.append("\n");
        }
        allContent.append(content);
    }

    private String buildUnknownSendMessage(int unitNumber, int buyNum, String payloadType, int deliveryMode) {
        String message = String.format(
                "第%d/%d件%s发送结果未知（10秒未收到服务端响应），请人工核对",
                unitNumber, buyNum, payloadType);
        return deliveryMode == 2 ? message + "；卡密已保留预占" : message;
    }

    private String buildDeliveryActionId(Long recordId, String orderId, int unitIndex) {
        String deliveryId = orderId != null && !orderId.isBlank()
                ? orderId
                : "record-" + recordId;
        return deliveryId + "#delivery-" + (unitIndex + 1);
    }

    private String buildResolveFailureMessage(int deliveryMode) {
        return deliveryMode == 1
                ? "未配置发货内容"
                : (deliveryMode == 2 ? "卡密库存不足，无可用卡密" : "未知的发货模式: " + deliveryMode);
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

    private String joinContents(List<String> contents) {
        return String.join("\n", contents);
    }

    void executeAutoConfirmShipment(Long accountId, String orderId) {
        if (orderId == null || orderId.isEmpty()) {
            log.warn("【账号{}】订单ID为空，无法自动确认发货", accountId);
            return;
        }
        log.info("【账号{}】提交异步自动确认发货: orderId={}", accountId, orderId);
        taskExecutor.execute(() -> {
            try {
                HumanLikeDelayUtils.longDelay();
                String result = orderService.confirmShipment(accountId, orderId);
                if (result != null) {
                    log.info("【账号{}】✅ 自动确认发货成功: orderId={}", accountId, orderId);
                    orderMapper.updateConfirmState(accountId, orderId);
                } else {
                    log.error("【账号{}】❌ 自动确认发货失败: orderId={}", accountId, orderId);
                }
            } catch (Exception e) {
                log.error("【账号{}】自动确认发货异常: orderId={}", accountId, orderId, e);
            }
        });
    }

    private void updateRecordState(Long recordId, Integer state, String content, String failReason) {
        try {
            // 幂等守卫在 SQL 层：state=1 的记录不会被失败状态覆盖
            orderMapper.updateStateContentAndFailReason(recordId, state, content, failReason);
        } catch (Exception e) {
            log.error("更新订单状态失败: recordId={}, state={}", recordId, state, e);
        }
    }

    @Override
    public void updateAutoConfirmShipment(Long accountId, String xyGoodsId, Integer autoConfirmShipment) {
        XianyuGoodsAutoDeliveryConfig config = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
        if (config == null) {
            config = new XianyuGoodsAutoDeliveryConfig();
            config.setXianyuAccountId(accountId);
            config.setXyGoodsId(xyGoodsId);
            config.setAutoConfirmShipment(autoConfirmShipment);
            autoDeliveryConfigMapper.insert(config);
        } else {
            config.setAutoConfirmShipment(autoConfirmShipment);
            autoDeliveryConfigMapper.updateById(config);
        }
    }

    @Override
    public com.feijimiao.xianyuassistant.common.ResultObject<String> manualDelivery(Long xianyuAccountId, String orderId, String content) {
        Optional<DeliveryOrderLease> leaseHolder = deliveryOrderLeaseManager.tryAcquire(xianyuAccountId, orderId);
        if (leaseHolder.isEmpty()) {
            // 自动发货正在跑：再插一条手工消息，买家会收到两份内容，订单状态也会被后写者覆盖
            log.warn("【账号{}】该订单正在自动发货，拒绝并发的手工发货: orderId={}", xianyuAccountId, orderId);
            return com.feijimiao.xianyuassistant.common.ResultObject.failed("该订单正在自动发货中，请稍后再试");
        }
        try (DeliveryOrderLease lease = leaseHolder.get()) {
            return doManualDelivery(xianyuAccountId, orderId, content);
        }
    }

    private com.feijimiao.xianyuassistant.common.ResultObject<String> doManualDelivery(Long xianyuAccountId, String orderId, String content) {
        try {
            if (orderId == null || orderId.isEmpty()) {
                return com.feijimiao.xianyuassistant.common.ResultObject.failed("订单ID不能为空");
            }
            if (content == null || content.trim().isEmpty()) {
                return com.feijimiao.xianyuassistant.common.ResultObject.failed("发货内容不能为空");
            }

            XianyuGoodsOrder record = orderMapper.selectByAccountIdAndOrderId(xianyuAccountId, orderId);
            if (record == null) {
                return com.feijimiao.xianyuassistant.common.ResultObject.failed("订单记录不存在");
            }

            String sId = record.getSid() != null ? record.getSid() : record.getBuyerUserId() + "@goofish";
            String cid = sId.replace("@goofish", "");
            String toId = cid;

            MessageSendResult sendResult = webSocketService.sendMessageWithOutcome(
                    xianyuAccountId, cid, toId, content);
            if (sendResult == MessageSendResult.SUCCESS) {
                updateRecordState(record.getId(), 1, content, null);
                sentMessageSaveService.saveAiAssistantReply(xianyuAccountId, cid, toId, content, record.getXyGoodsId());
                log.info("【账号{}】自定义发货成功: orderId={}", xianyuAccountId, orderId);
                return com.feijimiao.xianyuassistant.common.ResultObject.success("自定义发货成功");
            } else if (sendResult == MessageSendResult.UNKNOWN) {
                String unknownReason = "消息发送结果未知（10秒未收到服务端响应），请人工核对";
                updateRecordState(record.getId(), 0, content, unknownReason);
                log.warn("【账号{}】自定义发货结果未知，需人工核对: orderId={}", xianyuAccountId, orderId);
                return com.feijimiao.xianyuassistant.common.ResultObject.failed(unknownReason);
            } else {
                updateRecordState(record.getId(), -1, content, "消息发送失败");
                log.error("【账号{}】自定义发货失败: orderId={}", xianyuAccountId, orderId);
                return com.feijimiao.xianyuassistant.common.ResultObject.failed("消息发送失败");
            }
        } catch (Exception e) {
            log.error("【账号{}】自定义发货异常: orderId={}", xianyuAccountId, orderId, e);
            return com.feijimiao.xianyuassistant.common.ResultObject.failed("自定义发货异常: " + e.getMessage());
        }
    }
}

