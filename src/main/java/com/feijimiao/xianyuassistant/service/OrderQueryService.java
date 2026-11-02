package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;

import java.util.List;

/**
 * 订单查询服务
 * 提供 Controller 层对 XianyuGoodsOrder 的查询与状态更新
 */
public interface OrderQueryService {

    /**
     * 按条件统计订单数
     */
    long countByCondition(Long xianyuAccountId, String xyGoodsId, Integer orderStatus, String keyword);

    long countByCondition(Long xianyuAccountId, String xyGoodsId, Integer orderStatus, String keyword, DataPanelAccountScope scope);

    /**
     * 按条件分页查询订单
     */
    List<XianyuGoodsOrder> selectByConditionWithPage(Long xianyuAccountId, String xyGoodsId, Integer orderStatus, String keyword, int pageSize, int offset);

    List<XianyuGoodsOrder> selectByConditionWithPage(Long xianyuAccountId, String xyGoodsId, Integer orderStatus, String keyword, int pageSize, int offset, DataPanelAccountScope scope);

    /**
     * 更新订单的确认发货状态
     */
    int updateConfirmState(Long xianyuAccountId, String orderId);

    /**
     * 按日期统计订单数
     */
    int countOrdersByDate(String date);

    int countOrdersByDate(String date, DataPanelAccountScope scope);

    /**
     * 按日期统计发货成功数
     */
    int countDeliverySuccessByDate(String date);

    int countDeliverySuccessByDate(String date, DataPanelAccountScope scope);

    /**
     * 按日期统计发货失败数
     */
    int countDeliveryFailByDate(String date);

    int countDeliveryFailByDate(String date, DataPanelAccountScope scope);

    /**
     * 统计所有订单数
     */
    int countAllOrders();

    int countAllOrders(DataPanelAccountScope scope);

    /**
     * 累计发货成功总金额
     */
    double sumDeliverySuccessAmount();

    double sumDeliverySuccessAmount(DataPanelAccountScope scope);

    /**
     * 按日期累计发货成功金额
     */
    double sumDeliverySuccessAmountByDate(String date);

    double sumDeliverySuccessAmountByDate(String date, DataPanelAccountScope scope);

    /**
     * 按日期范围累计发货成功金额
     */
    double sumDeliverySuccessAmountByDateRange(String startDate, String endDate);

    double sumDeliverySuccessAmountByDateRange(String startDate, String endDate, DataPanelAccountScope scope);

    /**
     * 成交商品排行（按订单数降序）
     */
    List<java.util.Map<String, Object>> getTopGoodsByOrders(int limit);

    List<java.util.Map<String, Object>> getTopGoodsByOrders(int limit, DataPanelAccountScope scope);

    /**
     * 按账号分组的订单统计（指定日期）
     */
    List<java.util.Map<String, Object>> getAccountCompareByDate(String date);

    List<java.util.Map<String, Object>> getAccountCompareByDate(String date, DataPanelAccountScope scope);
}
