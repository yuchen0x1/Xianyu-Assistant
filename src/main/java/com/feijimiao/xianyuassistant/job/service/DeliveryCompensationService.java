package com.feijimiao.xianyuassistant.job.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.job.entity.DeliveryCompensation;
import com.feijimiao.xianyuassistant.job.entity.JobDefinition;
import com.feijimiao.xianyuassistant.job.mapper.DeliveryCompensationMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 发货补偿服务：处理结果未知的订单，生成核对任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryCompensationService {

    private final DeliveryCompensationMapper compensationMapper;
    private final JobEngine jobEngine;
    private final XianyuGoodsOrderMapper orderMapper;

    @PostConstruct
    public void registerWorker() {
        jobEngine.registerWorker("DELIVERY_REVIEW", this::handleReview);
        log.info("DELIVERY_REVIEW Worker 已注册：按订单实际状态自动确认或保留人工核对");
    }

    /**
     * DELIVERY_REVIEW Worker：核对发货补偿记录对应的订单状态。
     * state==1 视为已发货成功，自动确认并归档；其余状态（结果未知/风控拦截/未查到订单）保持 UNKNOWN，
     * 留给人工在待发货页面接管；任务本身返回成功，避免无意义重试进死信。
     */
    private Boolean handleReview(JobDefinition job, Map<String, Object> params) {
        try {
            Object compensationIdObj = params.get("compensationId");
            if (compensationIdObj == null) {
                log.info("[DELIVERY_REVIEW] 缺少 compensationId 参数，视为已处理: jobId={}", job.getId());
                return true;
            }
            Long compensationId = Long.valueOf(String.valueOf(compensationIdObj));
            DeliveryCompensation dc = compensationMapper.selectById(compensationId);
            if (dc == null) {
                log.info("[DELIVERY_REVIEW] 补偿记录不存在，视为已处理: compensationId={}", compensationId);
                return true;
            }
            XianyuGoodsOrder order = orderMapper.selectByAccountIdAndOrderId(dc.getXianyuAccountId(), dc.getOrderId());
            if (order != null && Integer.valueOf(1).equals(order.getState())) {
                resolve(dc.getId(), "AUTO_CONFIRMED");
                log.info("[DELIVERY_REVIEW] 订单已确认发货，补偿记录归档: orderId={}", dc.getOrderId());
                return true;
            }
            // 结果未知/风控拦截/未查到订单：保持 UNKNOWN，等待人工接管；任务标记成功不再重试
            log.info("[DELIVERY_REVIEW] 订单仍待人工核对，保持 UNKNOWN: orderId={}, state={}",
                    dc.getOrderId(), order == null ? null : order.getState());
            return true;
        } catch (Exception e) {
            log.warn("[DELIVERY_REVIEW] 核对异常，任务将重试: {}", e.getMessage());
            return false;
        }
    }

    /** 记录一个结果未知的发货 */
    @Transactional
    public DeliveryCompensation recordUnknown(String orderId, Long accountId, String xyGoodsId,
                                               String deliveryType, String deliveryContent,
                                               String idempotencyKey, String externalRequestId) {
        DeliveryCompensation dc = new DeliveryCompensation();
        dc.setOrderId(orderId);
        dc.setXianyuAccountId(accountId);
        dc.setXyGoodsId(xyGoodsId);
        dc.setDeliveryType(deliveryType);
        dc.setDeliveryContent(deliveryContent);
        dc.setPhase("UNKNOWN");
        dc.setIdempotencyKey(idempotencyKey);
        dc.setExternalRequestId(externalRequestId);
        dc.setUnknownSince(LocalDateTime.now());
        compensationMapper.insert(dc);

        // 创建核对任务（无重试间隔，立即执行）
        jobEngine.createJob("DELIVERY_REVIEW", "review:" + idempotencyKey,
                java.util.Map.of("compensationId", dc.getId(), "orderId", orderId),
                accountId, LocalDateTime.now(), 3, 0);
        log.info("发货补偿已记录，生成核对任务: orderId={}, key={}", orderId, idempotencyKey);
        return dc;
    }

    /** 查询未处理的补偿记录 */
    public List<DeliveryCompensation> listUnresolved(int limit) {
        return compensationMapper.selectList(
                new LambdaQueryWrapper<DeliveryCompensation>()
                        .eq(DeliveryCompensation::getPhase, "UNKNOWN")
                        .orderByAsc(DeliveryCompensation::getUnknownSince)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    public List<DeliveryCompensation> listAll(int limit) {
        return compensationMapper.selectList(
                new LambdaQueryWrapper<DeliveryCompensation>()
                        .orderByDesc(DeliveryCompensation::getCreatedTime)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    @Transactional
    public void resolve(Long id, String resolvedBy) {
        DeliveryCompensation dc = compensationMapper.selectById(id);
        if (dc == null) return;
        dc.setPhase("RESOLVED");
        dc.setResolvedBy(resolvedBy);
        dc.setResolvedAt(LocalDateTime.now());
        compensationMapper.updateById(dc);
    }
}
