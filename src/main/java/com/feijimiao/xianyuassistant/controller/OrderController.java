package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.service.OrderQueryService;
import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.service.DataPanelAccountScope;
import jakarta.validation.Valid;
import com.feijimiao.xianyuassistant.controller.dto.ConfirmShipmentReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.BatchConfirmShipmentRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.OrderDetailReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.OrderListReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.OrderListRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.OrderDTO;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.service.ApiDeliveryResult;
import com.feijimiao.xianyuassistant.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;


import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * 订单控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/order")
@RequirePermission("order:read")
public class OrderController {

    private static String fmt(LocalDateTime dt) {
        return dt != null ? dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null;
    }

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderQueryService orderQueryService;

    @Autowired
    private RbacService rbacService;

    
    @Autowired
    private com.feijimiao.xianyuassistant.service.PendingOrderPollService pendingOrderPollService;

    /**
     * 查询订单列表（第三方调用）
     */
    @PostMapping("/list")
    public ResultObject<OrderListRespDTO> listOrders(@Valid @RequestBody OrderListReqDTO reqDTO) {
        try {
            int pageSize = reqDTO.getPageSize() != null ? reqDTO.getPageSize() : 20;
            int pageNum = reqDTO.getPageNum() != null ? reqDTO.getPageNum() : 1;
            if (pageNum < 1) pageNum = 1;

            int offset = (pageNum - 1) * pageSize;
            Long userId = UserContext.getUserId();
            rbacService.assertAccountVisible(userId, reqDTO.getXianyuAccountId());
            List<Long> visibleAccountIds = rbacService.getCommandVisibleAccountIds(userId);
            DataPanelAccountScope scope = visibleAccountIds == null
                    ? DataPanelAccountScope.all(userId)
                    : DataPanelAccountScope.restricted(userId, visibleAccountIds);
            long total = orderQueryService.countByCondition(
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getOrderStatus(), reqDTO.getKeyword(), scope);

            List<XianyuGoodsOrder> orders = orderQueryService.selectByConditionWithPage(
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getOrderStatus(), reqDTO.getKeyword(),
                    pageSize, offset, scope);

            OrderListRespDTO respDTO = new OrderListRespDTO();
            respDTO.setTotal(total);
            respDTO.setPageNum(pageNum);
            respDTO.setPageSize(pageSize);

            List<OrderDTO> orderDTOs = new ArrayList<>();
            for (XianyuGoodsOrder order : orders) {
                OrderDTO dto = new OrderDTO();
                dto.setId(order.getId());
                dto.setXianyuAccountId(order.getXianyuAccountId());
                dto.setXyGoodsId(order.getXyGoodsId());
                dto.setOrderId(order.getOrderId());
                dto.setBuyerUserName(order.getBuyerUserName());
                dto.setSid(order.getSid());
                dto.setContent(order.getContent());
                dto.setState(order.getState());
                dto.setFailReason(order.getFailReason());
                dto.setConfirmState(order.getConfirmState());
                dto.setGoodsTitle(order.getGoodsTitle());
                dto.setSkuName(order.getSkuName());
                dto.setOrderCreateTime(order.getOrderCreateTime() != null ? order.getOrderCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
                dto.setPaySuccessTime(order.getPaySuccessTime());
                dto.setConsignTime(order.getConsignTime());
                dto.setTotalPrice(order.getTotalPrice());
                dto.setBuyNum(order.getBuyNum());
                dto.setCreateTime(order.getCreateTime() != null ? order.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
                orderDTOs.add(dto);
            }
            respDTO.setRecords(orderDTOs);

            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("查询订单列表失败", e);
            return ResultObject.failed("查询订单列表失败: " + e.getMessage());
        }
    }

    /**
     * 确认发货
     */
    @PostMapping("/confirmShipment")
    @RequirePermission("order:write")
    public ResultObject<String> confirmShipment(@Valid @RequestBody ConfirmShipmentReqDTO reqDTO) {
        try {
            log.info("确认发货请求: xianyuAccountId={}, orderId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getOrderId());

            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            if (reqDTO.getOrderId() == null || reqDTO.getOrderId().isEmpty()) {
                return ResultObject.failed("订单ID不能为空");
            }

            String result = orderService.confirmShipment(
                    reqDTO.getXianyuAccountId(),
                    reqDTO.getOrderId()
            );

            if (result != null) {
                orderQueryService.updateConfirmState(reqDTO.getXianyuAccountId(), reqDTO.getOrderId());
                return ResultObject.success(result);
            } else {
                return ResultObject.failed("确认发货失败");
            }

        } catch (Exception e) {
            log.error("确认发货失败", e);
            return ResultObject.failed("确认发货失败: " + e.getMessage());
        }
    }

    /**
     * 批量确认发货
     */
    @PostMapping("/confirmShipmentBatch")
    @RequirePermission("order:write")
    public ResultObject<BatchConfirmShipmentRespDTO> confirmShipmentBatch(
            @RequestBody(required = false) List<ConfirmShipmentReqDTO> items) {
        try {
            if (items == null || items.isEmpty()) {
                return ResultObject.failed("请选择要确认的订单");
            }
            if (items.size() > 100) {
                return ResultObject.failed("单次最多确认100条记录");
            }

            log.info("批量确认发货请求: 共{}条", items.size());

            BatchConfirmShipmentRespDTO respDTO = new BatchConfirmShipmentRespDTO();
            respDTO.setTotal(items.size());

            for (ConfirmShipmentReqDTO item : items) {
                if (item.getXianyuAccountId() == null || item.getOrderId() == null || item.getOrderId().isEmpty()) {
                    addFailItem(respDTO, item.getOrderId(), "账号ID或订单ID为空");
                    continue;
                }
                try {
                    String result = orderService.confirmShipment(item.getXianyuAccountId(), item.getOrderId());
                    if (result != null) {
                        orderQueryService.updateConfirmState(item.getXianyuAccountId(), item.getOrderId());
                        respDTO.setSuccessCount(respDTO.getSuccessCount() + 1);
                    } else {
                        addFailItem(respDTO, item.getOrderId(), "确认发货失败");
                    }
                } catch (Exception e) {
                    log.error("批量确认发货失败: accountId={}, orderId={}", 
                            item.getXianyuAccountId(), item.getOrderId(), e);
                    addFailItem(respDTO, item.getOrderId(), e.getMessage());
                }
            }

            respDTO.setFailCount(respDTO.getFailures().size());
            log.info("批量确认发货完成: 成功{}条, 失败{}条", respDTO.getSuccessCount(), respDTO.getFailCount());
            return ResultObject.success(respDTO);

        } catch (Exception e) {
            log.error("批量确认发货失败", e);
            return ResultObject.failed("批量确认发货失败: " + e.getMessage());
        }
    }

    private void addFailItem(BatchConfirmShipmentRespDTO respDTO, String orderId, String reason) {
        BatchConfirmShipmentRespDTO.FailItem fail = new BatchConfirmShipmentRespDTO.FailItem();
        fail.setOrderId(orderId);
        fail.setReason(reason != null ? reason : "未知错误");
        respDTO.getFailures().add(fail);
    }

    @PostMapping("/detail")
    public ResultObject<String> getOrderDetail(@Valid @RequestBody OrderDetailReqDTO reqDTO) {
        try {
            log.info("获取订单详情请求: xianyuAccountId={}, orderId={}, fromServer={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getOrderId(), reqDTO.getFromServer());

            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            if (reqDTO.getOrderId() == null || reqDTO.getOrderId().isEmpty()) {
                return ResultObject.failed("订单ID不能为空");
            }

            boolean fromServer = reqDTO.getFromServer() != null && reqDTO.getFromServer();
            String detail;
            if (fromServer) {
                detail = orderService.getOrderDetail(reqDTO.getXianyuAccountId(), reqDTO.getOrderId());
            } else {
                detail = orderService.getOrderDetailFromLocal(reqDTO.getXianyuAccountId(), reqDTO.getOrderId());
            }
            if (detail != null) {
                return ResultObject.success(detail);
            } else {
                return ResultObject.failed("获取订单详情失败");
            }
        } catch (Exception e) {
            log.error("获取订单详情失败", e);
            return ResultObject.failed("获取订单详情失败: " + e.getMessage());
        }
    }

    @PostMapping("/pendingOrders")
    public ResultObject<List<Map<String, Object>>> getPendingOrders(@RequestBody PendingOrdersReqDTO reqDTO) {
        try {
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            List<Map<String, Object>> orders = orderService.queryPendingOrders(reqDTO.getXianyuAccountId());
            pendingOrderPollService.syncOrdersToDb(reqDTO.getXianyuAccountId(), orders);
            return ResultObject.success(orders);
        } catch (Exception e) {
            log.error("查询待发货订单失败", e);
            return ResultObject.failed("查询待发货订单失败: " + e.getMessage());
        }
    }

    @lombok.Data
    public static class PendingOrdersReqDTO {
        private Long xianyuAccountId;
    }

    @PostMapping("/deliverPendingOrders")
    @RequirePermission("order:write")
    public ResultObject<Integer> deliverPendingOrders(@RequestBody PendingOrdersReqDTO reqDTO) {
        try {
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            int count = pendingOrderPollService.deliverPendingOrders(reqDTO.getXianyuAccountId());
            return ResultObject.success(count);
        } catch (Exception e) {
            log.error("自动发货失败", e);
            return ResultObject.failed("自动发货失败: " + e.getMessage());
        }
    }

    @PostMapping("/consignDummyDelivery")
    @RequirePermission("order:write")
    public ResultObject<String> consignDummyDelivery(@RequestBody ConsignDummyReqDTO reqDTO) {
        try {
            log.info("凭证发货请求: xianyuAccountId={}, xyGoodsId={}, orderId={}", reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getOrderId());
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            if (reqDTO.getOrderId() == null || reqDTO.getOrderId().isEmpty()) {
                return ResultObject.failed("订单ID不能为空");
            }
            if (reqDTO.getXyGoodsId() == null || reqDTO.getXyGoodsId().isEmpty()) {
                return ResultObject.failed("商品ID不能为空");
            }
            ApiDeliveryResult result = orderService.consignDummyDeliveryWithConfig(
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getOrderId());
            if (result == ApiDeliveryResult.SUCCESS) {
                log.info("凭证发货成功: xianyuAccountId={}, orderId={}", reqDTO.getXianyuAccountId(), reqDTO.getOrderId());
                return ResultObject.success("凭证发货成功");
            } else if (result == ApiDeliveryResult.UNKNOWN) {
                log.warn("凭证发货结果未知: xianyuAccountId={}, orderId={}", reqDTO.getXianyuAccountId(), reqDTO.getOrderId());
                return ResultObject.failed("凭证发货结果未知，请人工核对订单，系统不会自动重试");
            } else if (result == ApiDeliveryResult.SKIPPED) {
                log.info("凭证发货被并发流程占用: xianyuAccountId={}, orderId={}", reqDTO.getXianyuAccountId(), reqDTO.getOrderId());
                return ResultObject.failed("该订单正在发货中，请稍后刷新订单状态，勿重复提交");
            } else {
                log.error("凭证发货失败: xianyuAccountId={}, orderId={}", reqDTO.getXianyuAccountId(), reqDTO.getOrderId());
                return ResultObject.failed("凭证发货失败");
            }
        } catch (Exception e) {
            log.error("凭证发货异常: xianyuAccountId={}, orderId={}", reqDTO.getXianyuAccountId(), reqDTO.getOrderId(), e);
            return ResultObject.failed("凭证发货失败: " + e.getMessage());
        }
    }

    @lombok.Data
    public static class ConsignDummyReqDTO {
        private Long xianyuAccountId;
        private String xyGoodsId;
        private String orderId;
    }
}

