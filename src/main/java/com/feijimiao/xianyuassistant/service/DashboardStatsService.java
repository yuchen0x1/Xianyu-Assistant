package com.feijimiao.xianyuassistant.service;

/**
 * 首页 Dashboard 统计服务
 */
public interface DashboardStatsService {

    /**
     * 账号总数
     */
    long countAccounts();

    long countAccounts(DataPanelAccountScope scope);

    /**
     * 商品总数
     */
    long countItems();

    long countItems(DataPanelAccountScope scope);

    /**
     * 在售商品数 (status = 0)
     */
    long countSellingItems();

    long countSellingItems(DataPanelAccountScope scope);

    /**
     * 已下架商品数 (status = 1)
     */
    long countOffShelfItems();

    long countOffShelfItems(DataPanelAccountScope scope);

    /**
     * 已售出商品数 (status = 2)
     */
    long countSoldItems();

    long countSoldItems(DataPanelAccountScope scope);
}
