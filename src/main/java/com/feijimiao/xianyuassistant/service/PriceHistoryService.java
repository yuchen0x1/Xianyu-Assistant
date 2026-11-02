package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.controller.vo.PriceHistoryTrendVO;

import java.util.List;

/**
 * 商品价格历史服务（F6.1）
 */
public interface PriceHistoryService {

    /**
     * 记录价格快照（同一商品同一天内最多一条，已有则更新价格）
     *
     * @param xyGoodId 闲鱼商品ID
     * @param accountId 关联账号ID
     * @param priceStr 价格字符串（无法解析时忽略）
     */
    void recordPriceSnapshot(String xyGoodId, Long accountId, String priceStr);

    /**
     * 查询商品价格趋势（按日期升序）
     *
     * @param xyGoodId 闲鱼商品ID
     * @param days     最近天数
     * @return 价格序列
     */
    List<PriceHistoryTrendVO> queryTrend(String xyGoodId, int days);
}
