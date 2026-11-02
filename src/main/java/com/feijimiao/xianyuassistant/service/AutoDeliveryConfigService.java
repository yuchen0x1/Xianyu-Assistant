package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryConfigReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryConfigRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryConfigQueryReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.BatchCopyConfigReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.BatchUpdateGoodsConfigRespDTO;

import java.util.List;

public interface AutoDeliveryConfigService {
    
    ResultObject<AutoDeliveryConfigRespDTO> saveOrUpdateConfig(AutoDeliveryConfigReqDTO reqDTO);
    
    ResultObject<AutoDeliveryConfigRespDTO> getConfig(AutoDeliveryConfigQueryReqDTO reqDTO);
    
    ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByGoodsId(Long xianyuAccountId, String xyGoodsId);
    
    ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId);
    
    ResultObject<Void> deleteConfig(Long xianyuAccountId, String xyGoodsId);

    /**
     * 批量复制配置：将参考商品的基础配置套用到多个目标商品
     */
    ResultObject<BatchUpdateGoodsConfigRespDTO> batchCopyConfig(BatchCopyConfigReqDTO reqDTO);
}
