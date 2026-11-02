package com.feijimiao.xianyuassistant.backup.handler;

import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 商品订单备份：导出以账号 UNB 关联的订单记录，导入时按 (accountId, orderId) 幂等。
 */
@Slf4j
@Component
public class OrdersBackupHandler implements DataBackupHandler {

    @Autowired
    private XianyuGoodsOrderMapper goodsOrderMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Override
    public String getModuleKey() {
        return "orders";
    }

    @Override
    public String getModuleName() {
        return "商品订单";
    }

    @Override
    public Set<String> getRequiredImportListKeys() {
        return Set.of("orders");
    }

    @Override
    public Map<String, Object> exportData() {
        List<XianyuGoodsOrder> orders = new ArrayList<>();
        for (XianyuAccount account : accountMapper.selectList(null)) {
            List<XianyuGoodsOrder> accountOrders = goodsOrderMapper.selectByAccountId(account.getId());
            if (accountOrders != null) {
                orders.addAll(accountOrders);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (XianyuGoodsOrder order : orders) {
            String unb = findAccountUnb(order.getXianyuAccountId());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", unb);
            map.put("orderId", order.getOrderId());
            map.put("xyGoodsId", order.getXyGoodsId());
            map.put("pnmId", order.getPnmId());
            map.put("buyerUserId", order.getBuyerUserId());
            map.put("buyerUserName", order.getBuyerUserName());
            map.put("sid", order.getSid());
            map.put("content", order.getContent());
            map.put("state", order.getState());
            map.put("failReason", order.getFailReason());
            map.put("confirmState", order.getConfirmState());
            map.put("goodsTitle", order.getGoodsTitle());
            map.put("skuName", order.getSkuName());
            map.put("orderCreateTime", order.getOrderCreateTime());
            map.put("paySuccessTime", order.getPaySuccessTime());
            map.put("consignTime", order.getConsignTime());
            map.put("totalPrice", order.getTotalPrice());
            map.put("buyNum", order.getBuyNum());
            result.add(map);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orders", result);
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Long> unbToAccountId = context.get("unbToAccountId") != null
                ? (Map<String, Long>) context.get("unbToAccountId")
                : Collections.emptyMap();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orderMaps = (List<Map<String, Object>>) data.get("orders");
        if (orderMaps == null) return;

        for (Map<String, Object> map : orderMaps) {
            try {
                String unb = (String) map.get("unb");
                if (unb == null || unb.isBlank()) {
                    throw new IllegalArgumentException("订单数据缺少账号标识");
                }
                Long accountId = unbToAccountId.get(unb);
                if (accountId == null) {
                    throw new IllegalStateException("找不到订单关联账号: " + unb);
                }

                XianyuGoodsOrder order = new XianyuGoodsOrder();
                order.setXianyuAccountId(accountId);
                order.setXyGoodsId((String) map.get("xyGoodsId"));
                order.setPnmId((String) map.get("pnmId"));
                order.setOrderId((String) map.get("orderId"));
                order.setBuyerUserId((String) map.get("buyerUserId"));
                order.setBuyerUserName((String) map.get("buyerUserName"));
                order.setSid((String) map.get("sid"));
                order.setContent((String) map.get("content"));
                order.setState(integerValue(map.get("state")));
                order.setFailReason((String) map.get("failReason"));
                order.setConfirmState(integerValue(map.get("confirmState")));
                order.setGoodsTitle((String) map.get("goodsTitle"));
                order.setSkuName((String) map.get("skuName"));
                order.setCreateTime(LocalDateTime.now());
                order.setOrderCreateTime(timeValue(map.get("orderCreateTime")));
                order.setPaySuccessTime((String) map.get("paySuccessTime"));
                order.setConsignTime((String) map.get("consignTime"));
                order.setTotalPrice((String) map.get("totalPrice"));
                order.setBuyNum(integerValue(map.get("buyNum")));

                String orderId = order.getOrderId();
                XianyuGoodsOrder existing = goodsOrderMapper.selectByAccountIdAndOrderId(accountId, orderId);
                if (existing == null) {
                    goodsOrderMapper.insert(order);
                } else {
                    goodsOrderMapper.updateStateContentAndFailReason(
                            existing.getId(), order.getState(), order.getContent(), order.getFailReason());
                }
            } catch (Exception e) {
                throw new IllegalStateException("导入单条订单数据失败", e);
            }
        }
    }

    private String findAccountUnb(Long accountId) {
        if (accountId == null) {
            throw new IllegalStateException("订单数据缺少账号ID");
        }
        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null || account.getUnb() == null || account.getUnb().isBlank()) {
            throw new IllegalStateException("订单数据找不到关联账号: " + accountId);
        }
        return account.getUnb();
    }

    private Integer integerValue(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private java.time.LocalDateTime timeValue(Object value) {
        if (value == null) return null;
        if (value instanceof java.time.LocalDateTime ldt) return ldt;
        String str = value.toString();
        if (str.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(str);
        } catch (Exception e) {
            return null;
        }
    }
}