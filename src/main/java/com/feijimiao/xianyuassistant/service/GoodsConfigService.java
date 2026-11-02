package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;

/**
 * 商品配置服务
 * 提供 Controller 层对 XianyuGoodsConfig 的访问入口，避免 Controller 直接注入 Mapper
 */
public interface GoodsConfigService {

    /**
     * 根据账号ID和商品ID获取商品配置
     */
    XianyuGoodsConfig selectByAccountAndGoodsId(Long accountId, String xyGoodsId);

    /**
     * 更新商品的固定资料
     */
    int updateFixedMaterial(Long accountId, String xyGoodsId, String fixedMaterial);
}
