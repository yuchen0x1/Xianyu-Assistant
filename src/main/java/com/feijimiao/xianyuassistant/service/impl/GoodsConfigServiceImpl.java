package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.service.GoodsConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GoodsConfigServiceImpl implements GoodsConfigService {

    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;

    @Override
    public XianyuGoodsConfig selectByAccountAndGoodsId(Long accountId, String xyGoodsId) {
        if (accountId == null || xyGoodsId == null) {
            return null;
        }
        return goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
    }

    @Override
    public int updateFixedMaterial(Long accountId, String xyGoodsId, String fixedMaterial) {
        if (accountId == null || xyGoodsId == null) {
            return 0;
        }
        return goodsConfigMapper.updateFixedMaterial(accountId, xyGoodsId, fixedMaterial);
    }
}
