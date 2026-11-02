package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import com.feijimiao.xianyuassistant.service.OrderQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrderQueryServiceImpl implements OrderQueryService {

    @Autowired
    private XianyuGoodsOrderMapper orderMapper;

    @Override
    public long countByCondition(Long xianyuAccountId, String xyGoodsId, Integer orderStatus, String keyword) {
        return orderMapper.countByCondition(xianyuAccountId, xyGoodsId, orderStatus, keyword);
    }

    @Override
    public long countByCondition(Long xianyuAccountId, String xyGoodsId, Integer orderStatus, String keyword,
                                 com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return orderMapper.countByConditionScoped(xianyuAccountId, xyGoodsId, orderStatus, keyword, scope);
    }

    @Override
    public List<XianyuGoodsOrder> selectByConditionWithPage(Long xianyuAccountId, String xyGoodsId, Integer orderStatus, String keyword, int pageSize, int offset) {
        return orderMapper.selectByConditionWithPage(xianyuAccountId, xyGoodsId, orderStatus, keyword, pageSize, offset);
    }

    @Override
    public List<XianyuGoodsOrder> selectByConditionWithPage(Long xianyuAccountId, String xyGoodsId, Integer orderStatus,
                                                             String keyword, int pageSize, int offset,
                                                             com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return orderMapper.selectByConditionWithPageScoped(xianyuAccountId, xyGoodsId, orderStatus, keyword, pageSize, offset, scope);
    }

    @Override
    public int updateConfirmState(Long xianyuAccountId, String orderId) {
        if (xianyuAccountId == null || orderId == null || orderId.isEmpty()) {
            return 0;
        }
        return orderMapper.updateConfirmState(xianyuAccountId, orderId);
    }

    @Override
    public int countOrdersByDate(String date) {
        return orderMapper.countOrdersByDate(date);
    }

    @Override
    public int countOrdersByDate(String date, com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return orderMapper.countOrdersByDateScoped(date, scope);
    }

    @Override
    public int countDeliverySuccessByDate(String date) {
        return orderMapper.countDeliverySuccessByDate(date);
    }

    @Override
    public int countDeliverySuccessByDate(String date, com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return orderMapper.countDeliverySuccessByDateScoped(date, scope);
    }

    @Override
    public int countDeliveryFailByDate(String date) {
        return orderMapper.countDeliveryFailByDate(date);
    }

    @Override
    public int countDeliveryFailByDate(String date, com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return orderMapper.countDeliveryFailByDateScoped(date, scope);
    }

    @Override
    public int countAllOrders() {
        return orderMapper.countAllOrders();
    }

    @Override
    public int countAllOrders(com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return orderMapper.countAllOrdersScoped(scope);
    }

    @Override
    public double sumDeliverySuccessAmount() {
        return orderMapper.sumDeliverySuccessAmount();
    }

    @Override
    public double sumDeliverySuccessAmount(com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return orderMapper.sumDeliverySuccessAmountScoped(scope);
    }

    @Override
    public double sumDeliverySuccessAmountByDate(String date) {
        return orderMapper.sumDeliverySuccessAmountByDate(date);
    }

    @Override
    public double sumDeliverySuccessAmountByDate(String date, com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return orderMapper.sumDeliverySuccessAmountByDateScoped(date, scope);
    }

    @Override
    public double sumDeliverySuccessAmountByDateRange(String startDate, String endDate, com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return orderMapper.sumDeliverySuccessAmountByDateRangeScoped(startDate, endDate, scope);
    }

    @Override
    public double sumDeliverySuccessAmountByDateRange(String startDate, String endDate) {
        return orderMapper.sumDeliverySuccessAmountByDateRange(startDate, endDate);
    }

    @Override
    public List<java.util.Map<String, Object>> getTopGoodsByOrders(int limit) {
        return orderMapper.selectTopGoodsByOrders(Math.max(1, Math.min(limit, 50)));
    }

    @Override
    public List<java.util.Map<String, Object>> getTopGoodsByOrders(int limit, com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return orderMapper.selectTopGoodsByOrdersScoped(Math.max(1, Math.min(limit, 50)), scope);
    }

    @Override
    public List<java.util.Map<String, Object>> getAccountCompareByDate(String date) {
        return orderMapper.selectAccountCompareByDate(date);
    }

    @Override
    public List<java.util.Map<String, Object>> getAccountCompareByDate(String date, com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return orderMapper.selectAccountCompareByDateScoped(date, scope);
    }

}
