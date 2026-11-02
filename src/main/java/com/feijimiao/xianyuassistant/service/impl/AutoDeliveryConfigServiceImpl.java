package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryConfigReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryConfigRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryConfigQueryReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.BatchCopyConfigReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.BatchUpdateGoodsConfigRespDTO;
import com.feijimiao.xianyuassistant.service.AutoDeliveryConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AutoDeliveryConfigServiceImpl implements AutoDeliveryConfigService {
    
    @Autowired
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    
    @Override
    public ResultObject<AutoDeliveryConfigRespDTO> saveOrUpdateConfig(AutoDeliveryConfigReqDTO reqDTO) {
        try {
            String skuId = reqDTO.getSkuId();
            XianyuGoodsAutoDeliveryConfig existingConfig = null;
            if (skuId != null && !skuId.isEmpty()) {
                existingConfig = autoDeliveryConfigMapper
                        .findByAccountIdAndGoodsIdAndSkuId(reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), skuId);
            }
            if (existingConfig == null) {
                existingConfig = autoDeliveryConfigMapper
                        .findByAccountIdAndGoodsIdNoSku(reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
                if (existingConfig != null && skuId != null && !skuId.isEmpty()) {
                    existingConfig = null;
                }
            }
            
            XianyuGoodsAutoDeliveryConfig config;
            if (existingConfig != null) {
                config = existingConfig;
                config.setDeliveryMode(reqDTO.getDeliveryMode());
                config.setSkuId(reqDTO.getSkuId());
                config.setSkuName(reqDTO.getSkuName());
                config.setAutoDeliveryContent(reqDTO.getAutoDeliveryContent());
                config.setKamiConfigIds(reqDTO.getKamiConfigIds());
                config.setKamiDeliveryTemplate(reqDTO.getKamiDeliveryTemplate());
                config.setAutoDeliveryImageUrl(reqDTO.getAutoDeliveryImageUrl());
                config.setXianyuGoodsId(reqDTO.getXianyuGoodsId());
                if (reqDTO.getAutoConfirmShipment() != null) {
                    config.setAutoConfirmShipment(reqDTO.getAutoConfirmShipment());
                }
                
                autoDeliveryConfigMapper.updateById(config);
                log.info("更新自动发货配置成功，ID: {}", config.getId());
            } else {
                config = new XianyuGoodsAutoDeliveryConfig();
                BeanUtils.copyProperties(reqDTO, config);
                if (config.getSkuId() == null) {
                    config.setSkuId(null);
                }
                
                autoDeliveryConfigMapper.insert(config);
                log.info("创建自动发货配置成功，ID: {}", config.getId());
            }
            
            AutoDeliveryConfigRespDTO respDTO = new AutoDeliveryConfigRespDTO();
            BeanUtils.copyProperties(config, respDTO);
            
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("保存自动发货配置失败", e);
            return ResultObject.failed("保存自动发货配置失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<AutoDeliveryConfigRespDTO> getConfig(AutoDeliveryConfigQueryReqDTO reqDTO) {
        try {
            log.info("开始查询自动发货配置: xianyuAccountId={}, xyGoodsId={}, skuId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getSkuId());
            
            XianyuGoodsAutoDeliveryConfig config = null;
            
            if (reqDTO.getXyGoodsId() != null && !reqDTO.getXyGoodsId().trim().isEmpty()) {
                String skuId = reqDTO.getSkuId();
                if (skuId != null && !skuId.isEmpty()) {
                    config = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdAndSkuId(
                            reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), skuId);
                }
                if (config == null) {
                    config = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(
                            reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
                }
            } else {
                List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper
                        .findByAccountId(reqDTO.getXianyuAccountId());
                config = configs.isEmpty() ? null : configs.get(0);
            }
            
            if (config == null) {
                return ResultObject.success(null);
            }
            
            AutoDeliveryConfigRespDTO respDTO = new AutoDeliveryConfigRespDTO();
            BeanUtils.copyProperties(config, respDTO);
            
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("查询自动发货配置失败", e);
            return ResultObject.failed("查询自动发货配置失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByGoodsId(Long xianyuAccountId, String xyGoodsId) {
        try {
            List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper
                    .findByAccountIdAndGoodsId(xianyuAccountId, xyGoodsId);
            
            List<AutoDeliveryConfigRespDTO> respDTOs = configs.stream()
                    .map(config -> {
                        AutoDeliveryConfigRespDTO respDTO = new AutoDeliveryConfigRespDTO();
                        BeanUtils.copyProperties(config, respDTO);
                        return respDTO;
                    })
                    .collect(Collectors.toList());
            
            return ResultObject.success(respDTOs);
        } catch (Exception e) {
            log.error("查询商品自动发货配置列表失败", e);
            return ResultObject.failed("查询商品自动发货配置列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId) {
        try {
            List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper
                    .findByAccountId(xianyuAccountId);
            
            List<AutoDeliveryConfigRespDTO> respDTOs = configs.stream()
                    .map(config -> {
                        AutoDeliveryConfigRespDTO respDTO = new AutoDeliveryConfigRespDTO();
                        BeanUtils.copyProperties(config, respDTO);
                        return respDTO;
                    })
                    .collect(Collectors.toList());
            
            return ResultObject.success(respDTOs);
        } catch (Exception e) {
            log.error("查询账号自动发货配置列表失败", e);
            return ResultObject.failed("查询账号自动发货配置列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<Void> deleteConfig(Long xianyuAccountId, String xyGoodsId) {
        try {
            LambdaQueryWrapper<XianyuGoodsAutoDeliveryConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(XianyuGoodsAutoDeliveryConfig::getXianyuAccountId, xianyuAccountId)
                   .eq(XianyuGoodsAutoDeliveryConfig::getXyGoodsId, xyGoodsId);
            
            int deletedCount = autoDeliveryConfigMapper.delete(wrapper);
            
            if (deletedCount > 0) {
                log.info("删除自动发货配置成功，账号ID: {}, 商品ID: {}", xianyuAccountId, xyGoodsId);
                return ResultObject.success(null);
            } else {
                return ResultObject.failed("未找到对应的自动发货配置");
            }
        } catch (Exception e) {
            log.error("删除自动发货配置失败", e);
            return ResultObject.failed("删除自动发货配置失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<BatchUpdateGoodsConfigRespDTO> batchCopyConfig(BatchCopyConfigReqDTO reqDTO) {
        try {
            int maxItems = 200;
            if (reqDTO.getTargetXyGoodsIds().size() > maxItems) {
                return ResultObject.failed("单次最多操作" + maxItems + "个商品");
            }
            // 排除参考商品自身
            List<String> targets = reqDTO.getTargetXyGoodsIds().stream()
                    .filter(id -> id != null && !id.isEmpty() && !id.equals(reqDTO.getSourceXyGoodsId()))
                    .distinct()
                    .collect(Collectors.toList());
            if (targets.isEmpty()) {
                return ResultObject.failed("目标商品列表为空");
            }

            // 读取参考商品的基础配置（无SKU）
            XianyuGoodsAutoDeliveryConfig source = autoDeliveryConfigMapper
                    .findByAccountIdAndGoodsIdNoSku(reqDTO.getXianyuAccountId(), reqDTO.getSourceXyGoodsId());
            if (source == null) {
                // 回退：取该商品任意一条配置
                List<XianyuGoodsAutoDeliveryConfig> sourceConfigs = autoDeliveryConfigMapper
                        .findByAccountIdAndGoodsId(reqDTO.getXianyuAccountId(), reqDTO.getSourceXyGoodsId());
                source = sourceConfigs.isEmpty() ? null : sourceConfigs.get(0);
            }
            if (source == null) {
                return ResultObject.failed("参考商品未配置自动发货，请先在右侧面板配置后再套用");
            }

            log.info("批量复制配置: accountId={}, 源商品={}, 目标数={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getSourceXyGoodsId(), targets.size());

            BatchUpdateGoodsConfigRespDTO respDTO = new BatchUpdateGoodsConfigRespDTO();
            respDTO.setTotal(targets.size());

            for (String targetId : targets) {
                try {
                    XianyuGoodsAutoDeliveryConfig target = autoDeliveryConfigMapper
                            .findByAccountIdAndGoodsIdNoSku(reqDTO.getXianyuAccountId(), targetId);
                    if (target == null) {
                        target = new XianyuGoodsAutoDeliveryConfig();
                        target.setXianyuAccountId(reqDTO.getXianyuAccountId());
                        target.setXyGoodsId(targetId);
                        target.setXianyuGoodsId(null);
                    }
                    // 复制源配置字段（保留目标商品的 id/账号/商品标识，清空 SKU 维度）
                    target.setDeliveryMode(source.getDeliveryMode());
                    target.setSkuId(null);
                    target.setSkuName(null);
                    target.setAutoDeliveryContent(source.getAutoDeliveryContent());
                    target.setKamiConfigIds(source.getKamiConfigIds());
                    target.setKamiDeliveryTemplate(source.getKamiDeliveryTemplate());
                    target.setAutoDeliveryImageUrl(source.getAutoDeliveryImageUrl());
                    target.setAutoConfirmShipment(source.getAutoConfirmShipment());
                    target.setRagDelaySeconds(source.getRagDelaySeconds());
                    target.setUpdateTime(java.time.LocalDateTime.now());
                    if (target.getId() == null) {
                        autoDeliveryConfigMapper.insert(target);
                    } else {
                        autoDeliveryConfigMapper.updateById(target);
                    }
                    respDTO.setSuccessCount(respDTO.getSuccessCount() + 1);
                } catch (Exception e) {
                    log.error("复制配置失败: accountId={}, xyGoodsId={}", reqDTO.getXianyuAccountId(), targetId, e);
                    BatchUpdateGoodsConfigRespDTO.FailItem fail = new BatchUpdateGoodsConfigRespDTO.FailItem();
                    fail.setXyGoodsId(targetId);
                    fail.setReason(e.getMessage() != null ? e.getMessage() : "未知错误");
                    respDTO.getFailures().add(fail);
                }
            }

            respDTO.setFailCount(respDTO.getFailures().size());
            log.info("批量复制配置完成: 成功{}条, 失败{}条", respDTO.getSuccessCount(), respDTO.getFailCount());
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("批量复制配置失败", e);
            return ResultObject.failed("批量复制配置失败: " + e.getMessage());
        }
    }
}
