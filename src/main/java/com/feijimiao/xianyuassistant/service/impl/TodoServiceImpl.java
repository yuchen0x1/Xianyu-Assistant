package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordDTO;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgSessionDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgSessionReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgSessionRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.TodoRespDTO;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.review.entity.ReviewRecord;
import com.feijimiao.xianyuassistant.review.service.ReviewService;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.AutoDeliveryService;
import com.feijimiao.xianyuassistant.service.ChatMessageService;
import com.feijimiao.xianyuassistant.service.OrderService;
import com.feijimiao.xianyuassistant.service.TodoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 待办中心服务实现。
 *
 * <p>按 RBAC 可见账号范围逐个账号聚合四类待办。为避免破坏各模块原有
 * 查询逻辑，这里直接复用各模块的 service 方法，再统一映射为待办项。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoServiceImpl implements TodoService {

    private static final String STATUS_WAITING_CONFIRM = "WAITING_CONFIRM";

    private final RbacService rbacService;
    private final AccountService accountService;
    private final OrderService orderService;
    private final ChatMessageService chatMessageService;
    private final AutoDeliveryService autoDeliveryService;
    private final ReviewService reviewService;

    @Override
    public TodoRespDTO overview(int limit) {
        int cap = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 200));
        TodoRespDTO resp = new TodoRespDTO();
        TodoRespDTO.Summary summary = resp.getSummary();

        List<Long> accountIds = resolveVisibleAccountIds();
        for (Long accountId : accountIds) {
            XianyuAccount account = accountService.getById(accountId);
            if (account == null) continue;
            String accountName = displayName(account);
            collectPendingShipment(resp, summary, accountId, accountName, cap);
            collectPendingReply(resp, summary, accountId, accountName, cap);
            collectPendingReview(resp, summary, accountId, accountName, cap);
            collectPendingEvaluation(resp, summary, accountId, accountName, cap);

            if (isFull(resp, cap)) break;
        }

        return resp;
    }

    private boolean isFull(TodoRespDTO resp, int cap) {
        return resp.getPendingShipment().size() >= cap
                && resp.getPendingReply().size() >= cap
                && resp.getPendingReview().size() >= cap
                && resp.getPendingEvaluation().size() >= cap;
    }

    private void collectPendingShipment(TodoRespDTO resp, TodoRespDTO.Summary summary,
                                        Long accountId, String accountName, int cap) {
        int remaining = cap - resp.getPendingShipment().size();
        int seen = 0;
        for (Map<String, Object> order : queryPendingOrders(accountId)) {
            if (seen >= remaining) break;
            TodoRespDTO.TodoItem item = mapPendingShipment(accountId, accountName, order);
            if (item != null) {
                resp.getPendingShipment().add(item);
                seen++;
            }
        }
        summary.setPendingShipmentCount(summary.getPendingShipmentCount() + seen);
    }

    private void collectPendingReply(TodoRespDTO resp, TodoRespDTO.Summary summary,
                                     Long accountId, String accountName, int cap) {
        int remaining = cap - resp.getPendingReply().size();
        int seen = 0;
        for (MsgSessionDTO session : queryPendingReplySessions(accountId)) {
            if (seen >= remaining) break;
            resp.getPendingReply().add(mapPendingReply(accountId, accountName, session));
            seen++;
        }
        summary.setPendingReplyCount(summary.getPendingReplyCount() + seen);
    }

    private void collectPendingReview(TodoRespDTO resp, TodoRespDTO.Summary summary,
                                      Long accountId, String accountName, int cap) {
        int remaining = cap - resp.getPendingReview().size();
        int seen = 0;
        for (AutoDeliveryRecordDTO record : queryPendingReviewRecords(accountId)) {
            if (seen >= remaining) break;
            resp.getPendingReview().add(mapPendingReview(accountId, accountName, record));
            seen++;
        }
        summary.setPendingReviewCount(summary.getPendingReviewCount() + seen);
    }

    private void collectPendingEvaluation(TodoRespDTO resp, TodoRespDTO.Summary summary,
                                          Long accountId, String accountName, int cap) {
        int remaining = cap - resp.getPendingEvaluation().size();
        int seen = 0;
        for (ReviewRecord record : queryPendingEvaluationRecords(accountId)) {
            if (seen >= remaining) break;
            resp.getPendingEvaluation().add(mapPendingEvaluation(accountId, accountName, record));
            seen++;
        }
        summary.setPendingEvaluationCount(summary.getPendingEvaluationCount() + seen);
    }

    /**
     * 解析当前用户可见账号范围。
     * 管理员（rbac 返回 null）查全部账号；非管理员查其被授权的账号。
     */
    private List<Long> resolveVisibleAccountIds() {
        Long userId = UserContext.getUserId();
        List<Long> visible = rbacService.getCommandVisibleAccountIds(userId);
        if (visible == null) {
            List<Long> all = new ArrayList<>();
            for (XianyuAccount account : accountService.listAll()) {
                all.add(account.getId());
            }
            return all;
        }
        return visible;
    }

    private String displayName(XianyuAccount account) {
        if (account.getAccountNote() != null && !account.getAccountNote().isBlank()) {
            return account.getAccountNote();
        }
        if (account.getNickname() != null && !account.getNickname().isBlank()) {
            return account.getNickname();
        }
        return "#" + account.getId();
    }

    private List<Map<String, Object>> queryPendingOrders(Long accountId) {
        try {
            List<Map<String, Object>> orders = orderService.queryPendingOrders(accountId);
            return orders == null ? List.of() : orders;
        } catch (Exception e) {
            log.warn("【待办中心】查询账号{}待发货订单失败: {}", accountId, e.getMessage());
            return List.of();
        }
    }

    private List<MsgSessionDTO> queryPendingReplySessions(Long accountId) {
        try {
            MsgSessionReqDTO req = new MsgSessionReqDTO();
            req.setXianyuAccountId(accountId);
            req.setPageNum(1);
            req.setPageSize(50);
            ResultObject<MsgSessionRespDTO> result = chatMessageService.getSessions(req);
            if (result == null || result.getData() == null || result.getData().getList() == null) {
                return List.of();
            }
            List<MsgSessionDTO> pending = new ArrayList<>();
            for (MsgSessionDTO s : result.getData().getList()) {
                if (Boolean.TRUE.equals(s.getPendingReply())) pending.add(s);
            }
            return pending;
        } catch (Exception e) {
            log.warn("【待办中心】查询账号{}待回复会话失败: {}", accountId, e.getMessage());
            return List.of();
        }
    }

    private List<AutoDeliveryRecordDTO> queryPendingReviewRecords(Long accountId) {
        try {
            AutoDeliveryRecordReqDTO req = new AutoDeliveryRecordReqDTO();
            req.setXianyuAccountId(accountId);
            req.setState(0);
            req.setPageNum(1);
            req.setPageSize(50);
            AutoDeliveryRecordRespDTO resp = autoDeliveryService.getAutoDeliveryRecords(req);
            if (resp == null || resp.getRecords() == null) return List.of();
            return resp.getRecords();
        } catch (Exception e) {
            log.warn("【待办中心】查询账号{}待复核发货记录失败: {}", accountId, e.getMessage());
            return List.of();
        }
    }

    private List<ReviewRecord> queryPendingEvaluationRecords(Long accountId) {
        try {
            List<ReviewRecord> records = reviewService.listRecords(accountId, STATUS_WAITING_CONFIRM, 50);
            return records == null ? List.of() : records;
        } catch (Exception e) {
            log.warn("【待办中心】查询账号{}待评价失败: {}", accountId, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private TodoRespDTO.TodoItem mapPendingShipment(Long accountId, String accountName, Map<String, Object> order) {
        try {
            Object commonDataObj = order.get("commonData");
            if (!(commonDataObj instanceof Map)) return null;
            Map<String, Object> commonData = (Map<String, Object>) commonDataObj;

            String orderId = asString(commonData.get("orderId"));
            String itemId = asString(commonData.get("itemId"));
            String orderStatus = asString(commonData.get("orderStatus"));
            String createTime = asString(commonData.get("createTime"));

            String buyer = null;
            Object buyerInfoObj = order.get("buyerInfoVO");
            if (buyerInfoObj instanceof Map) {
                Map<String, Object> buyerInfo = (Map<String, Object>) buyerInfoObj;
                buyer = asString(buyerInfo.get("userNick"));
            }

            String title = null;
            Object itemVOObj = order.get("itemVO");
            if (itemVOObj instanceof Map) {
                Map<String, Object> itemVO = (Map<String, Object>) itemVOObj;
                title = asString(itemVO.get("title"));
            }

            String totalPrice = null;
            Object priceVOObj = order.get("priceVO");
            if (priceVOObj instanceof Map) {
                Map<String, Object> priceVO = (Map<String, Object>) priceVOObj;
                Object tp = priceVO.get("totalPrice");
                totalPrice = tp == null ? null : String.valueOf(tp);
            }

            TodoRespDTO.TodoItem item = new TodoRespDTO.TodoItem();
            item.setAccountId(accountId);
            item.setAccountName(accountName);
            item.setType("pendingShipment");
            item.setId(orderId);
            item.setTitle(title != null ? title : orderId);
            item.setDescription(buyer);
            item.setGoodsId(itemId);
            item.setTime(createTime);
            item.setExtra(orderStatus);
            return item;
        } catch (Exception e) {
            log.warn("【待办中心】解析待发货订单失败: {}", e.getMessage());
            return null;
        }
    }

    private TodoRespDTO.TodoItem mapPendingReply(Long accountId, String accountName, MsgSessionDTO session) {
        TodoRespDTO.TodoItem item = new TodoRespDTO.TodoItem();
        item.setAccountId(accountId);
        item.setAccountName(accountName);
        item.setType("pendingReply");
        item.setId(session.getSid());
        item.setTitle(session.getBuyerName());
        item.setDescription(session.getLastContent());
        item.setGoodsId(session.getXyGoodsId());
        item.setTime(session.getLastTime() != null ? String.valueOf(session.getLastTime()) : null);
        return item;
    }

    private TodoRespDTO.TodoItem mapPendingReview(Long accountId, String accountName, AutoDeliveryRecordDTO record) {
        TodoRespDTO.TodoItem item = new TodoRespDTO.TodoItem();
        item.setAccountId(accountId);
        item.setAccountName(accountName);
        item.setType("pendingReview");
        item.setId(record.getId() != null ? String.valueOf(record.getId()) : record.getOrderId());
        item.setTitle(record.getGoodsTitle() != null ? record.getGoodsTitle() : record.getOrderId());
        item.setDescription(record.getBuyerUserName());
        item.setGoodsId(record.getXyGoodsId());
        item.setTime(record.getCreateTime());
        item.setExtra(record.getOrderId());
        return item;
    }

    private TodoRespDTO.TodoItem mapPendingEvaluation(Long accountId, String accountName, ReviewRecord record) {
        TodoRespDTO.TodoItem item = new TodoRespDTO.TodoItem();
        item.setAccountId(accountId);
        item.setAccountName(accountName);
        item.setType("pendingEvaluation");
        item.setId(String.valueOf(record.getId()));
        item.setTitle(record.getOrderId());
        item.setDescription(record.getBuyerUserId());
        item.setTime(record.getScheduledAt() != null ? record.getScheduledAt().toString() : null);
        item.setExtra(record.getReviewText());
        return item;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}