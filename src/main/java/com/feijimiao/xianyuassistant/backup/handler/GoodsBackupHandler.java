package com.feijimiao.xianyuassistant.backup.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsSku;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsSkuProperty;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsInfoMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsSkuMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsSkuPropertyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class GoodsBackupHandler implements DataBackupHandler {

    @Autowired
    private XianyuGoodsInfoMapper goodsInfoMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private XianyuGoodsSkuMapper goodsSkuMapper;

    @Autowired
    private XianyuGoodsSkuPropertyMapper goodsSkuPropertyMapper;

    @Override
    public String getModuleKey() {
        return "goods";
    }

    @Override
    public String getModuleName() {
        return "商品管理";
    }

    @Override
    public Set<String> getRequiredImportListKeys() {
        return Set.of("goodsList");
    }

    @Override
    public Set<String> getAdditionalRequiredImportListKeys(String backupVersion) {
        return "2.0".equals(backupVersion)
                ? Set.of("goodsSkus", "goodsSkuProperties") : Set.of();
    }

    @Override
    public Map<String, Object> exportData() {
        List<XianyuGoodsInfo> goodsList = goodsInfoMapper.selectList(null);

        List<Map<String, Object>> result = new ArrayList<>();
        for (XianyuGoodsInfo goods : goodsList) {
            XianyuAccount account = accountMapper.selectById(goods.getXianyuAccountId());
            if (account == null || account.getUnb() == null || account.getUnb().isBlank()) {
                throw new IllegalStateException(
                        "商品数据找不到关联账号: " + goods.getXianyuAccountId());
            }

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", account.getUnb());
            map.put("xyGoodId", goods.getXyGoodId());
            map.put("title", goods.getTitle());
            map.put("coverPic", goods.getCoverPic());
            map.put("infoPic", goods.getInfoPic());
            map.put("detailInfo", goods.getDetailInfo());
            map.put("detailUrl", goods.getDetailUrl());
            map.put("soldPrice", goods.getSoldPrice());
            map.put("status", goods.getStatus());
            result.add(map);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goodsList", result);
        data.put("goodsSkus", exportSkus());
        data.put("goodsSkuProperties", exportSkuProperties());
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
        List<Map<String, Object>> goodsMaps = (List<Map<String, Object>>) data.get("goodsList");
        if (goodsMaps == null) return;

        for (Map<String, Object> map : goodsMaps) {
            try {
                String unb = (String) map.get("unb");
                String xyGoodId = (String) map.get("xyGoodId");
                if (unb == null || xyGoodId == null) {
                    throw new IllegalArgumentException("商品数据缺少账号或商品标识");
                }

                Long accountId = unbToAccountId.get(unb);
                if (accountId == null) {
                    throw new IllegalStateException("找不到商品数据关联账号: " + unb);
                }

                LambdaQueryWrapper<XianyuGoodsInfo> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(XianyuGoodsInfo::getXyGoodId, xyGoodId);
                XianyuGoodsInfo existing = goodsInfoMapper.selectOne(wrapper);

                XianyuGoodsInfo goods = new XianyuGoodsInfo();
                goods.setXianyuAccountId(accountId);
                goods.setXyGoodId(xyGoodId);
                goods.setTitle((String) map.get("title"));
                goods.setCoverPic((String) map.get("coverPic"));
                goods.setInfoPic((String) map.get("infoPic"));
                goods.setDetailInfo((String) map.get("detailInfo"));
                goods.setDetailUrl((String) map.get("detailUrl"));
                goods.setSoldPrice((String) map.get("soldPrice"));
                goods.setStatus(map.get("status") != null ? ((Number) map.get("status")).intValue() : null);

                if (existing == null) {
                    goodsInfoMapper.insert(goods);
                } else {
                    goods.setId(existing.getId());
                    goodsInfoMapper.updateById(goods);
                }
            } catch (Exception e) {
                throw new IllegalStateException("导入单条商品数据失败", e);
            }
        }
        importSkus(data, unbToAccountId);
        importSkuProperties(data, unbToAccountId);
    }

    private List<Map<String, Object>> exportSkus() {
        List<XianyuGoodsSku> skus = goodsSkuMapper.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>();
        if (skus == null) {
            return result;
        }
        for (XianyuGoodsSku sku : skus) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", findAccountUnb(
                    sku.getXianyuAccountId(), sku.getXyGoodsId(), "商品SKU"));
            map.put("xyGoodsId", sku.getXyGoodsId());
            map.put("skuId", sku.getSkuId());
            map.put("price", sku.getPrice());
            map.put("quantity", sku.getQuantity());
            map.put("propertyText", sku.getPropertyText());
            map.put("propertyId", sku.getPropertyId());
            map.put("valueId", sku.getValueId());
            map.put("valueText", sku.getValueText());
            map.put("propertySortOrder", sku.getPropertySortOrder());
            map.put("valueSortOrder", sku.getValueSortOrder());
            map.put("features", sku.getFeatures());
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> exportSkuProperties() {
        List<XianyuGoodsSkuProperty> properties = goodsSkuPropertyMapper.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>();
        if (properties == null) {
            return result;
        }
        for (XianyuGoodsSkuProperty property : properties) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", findAccountUnb(
                    property.getXianyuAccountId(), property.getXyGoodsId(), "商品SKU属性"));
            map.put("xyGoodsId", property.getXyGoodsId());
            map.put("propertyId", property.getPropertyId());
            map.put("propertyText", property.getPropertyText());
            map.put("propertySortOrder", property.getPropertySortOrder());
            map.put("valueId", property.getValueId());
            map.put("valueText", property.getValueText());
            map.put("valueSortOrder", property.getValueSortOrder());
            result.add(map);
        }
        return result;
    }

    private String findAccountUnb(Long accountId, String xyGoodsId, String dataType) {
        Long resolvedAccountId = accountId;
        if (resolvedAccountId == null && xyGoodsId != null && !xyGoodsId.isBlank()) {
            LambdaQueryWrapper<XianyuGoodsInfo> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(XianyuGoodsInfo::getXyGoodId, xyGoodsId);
            XianyuGoodsInfo goods = goodsInfoMapper.selectOne(wrapper);
            if (goods != null) {
                resolvedAccountId = goods.getXianyuAccountId();
            }
        }
        if (resolvedAccountId == null) {
            throw new IllegalStateException(dataType + "无法通过商品确定关联账号: " + xyGoodsId);
        }
        XianyuAccount account = accountMapper.selectById(resolvedAccountId);
        if (account == null || account.getUnb() == null || account.getUnb().isBlank()) {
            throw new IllegalStateException(dataType + "找不到关联账号: " + resolvedAccountId);
        }
        return account.getUnb();
    }

    private void importSkus(Map<String, Object> data, Map<String, Long> unbToAccountId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skuMaps = (List<Map<String, Object>>) data.get("goodsSkus");
        if (skuMaps == null) {
            return;
        }
        for (Map<String, Object> map : skuMaps) {
            try {
                Long accountId = resolveAccountId(map, unbToAccountId, "商品SKU");
                String xyGoodsId = requireString(map, "xyGoodsId", "商品SKU");
                String skuId = (String) map.get("skuId");
                LambdaQueryWrapper<XianyuGoodsSku> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(XianyuGoodsSku::getXianyuAccountId, accountId)
                        .eq(XianyuGoodsSku::getXyGoodsId, xyGoodsId);
                if (skuId == null) {
                    wrapper.isNull(XianyuGoodsSku::getSkuId);
                } else {
                    wrapper.eq(XianyuGoodsSku::getSkuId, skuId);
                }
                XianyuGoodsSku existing = goodsSkuMapper.selectOne(wrapper);
                ensureAccountOwnership(existing != null ? existing.getXianyuAccountId() : null,
                        accountId, "商品SKU");

                XianyuGoodsSku sku = new XianyuGoodsSku();
                sku.setXyGoodsId(xyGoodsId);
                sku.setSkuId(skuId);
                sku.setPrice(integerValue(map.get("price")));
                sku.setQuantity(integerValue(map.get("quantity")));
                sku.setPropertyText((String) map.get("propertyText"));
                sku.setPropertyId(integerValue(map.get("propertyId")));
                sku.setValueId(integerValue(map.get("valueId")));
                sku.setValueText((String) map.get("valueText"));
                sku.setPropertySortOrder(integerValue(map.get("propertySortOrder")));
                sku.setValueSortOrder(integerValue(map.get("valueSortOrder")));
                sku.setFeatures((String) map.get("features"));
                sku.setXianyuAccountId(accountId);
                if (existing == null) {
                    goodsSkuMapper.insert(sku);
                } else {
                    sku.setId(existing.getId());
                    sku.setCreatedTime(existing.getCreatedTime());
                    goodsSkuMapper.updateById(sku);
                }
            } catch (Exception e) {
                throw new IllegalStateException("导入单条商品SKU失败", e);
            }
        }
    }

    private void importSkuProperties(Map<String, Object> data, Map<String, Long> unbToAccountId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> propertyMaps =
                (List<Map<String, Object>>) data.get("goodsSkuProperties");
        if (propertyMaps == null) {
            return;
        }
        for (Map<String, Object> map : propertyMaps) {
            try {
                Long accountId = resolveAccountId(map, unbToAccountId, "商品SKU属性");
                String xyGoodsId = requireString(map, "xyGoodsId", "商品SKU属性");
                Integer propertyId = integerValue(map.get("propertyId"));
                Integer valueId = integerValue(map.get("valueId"));
                if (propertyId == null || valueId == null) {
                    throw new IllegalArgumentException("商品SKU属性缺少属性ID或属性值ID");
                }
                LambdaQueryWrapper<XianyuGoodsSkuProperty> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(XianyuGoodsSkuProperty::getXianyuAccountId, accountId)
                        .eq(XianyuGoodsSkuProperty::getXyGoodsId, xyGoodsId)
                        .eq(XianyuGoodsSkuProperty::getPropertyId, propertyId)
                        .eq(XianyuGoodsSkuProperty::getValueId, valueId);
                XianyuGoodsSkuProperty existing = goodsSkuPropertyMapper.selectOne(wrapper);
                ensureAccountOwnership(existing != null ? existing.getXianyuAccountId() : null,
                        accountId, "商品SKU属性");

                XianyuGoodsSkuProperty property = new XianyuGoodsSkuProperty();
                property.setXyGoodsId(xyGoodsId);
                property.setPropertyId(propertyId);
                property.setPropertyText((String) map.get("propertyText"));
                property.setPropertySortOrder(integerValue(map.get("propertySortOrder")));
                property.setValueId(valueId);
                property.setValueText((String) map.get("valueText"));
                property.setValueSortOrder(integerValue(map.get("valueSortOrder")));
                property.setXianyuAccountId(accountId);
                if (existing == null) {
                    goodsSkuPropertyMapper.insert(property);
                } else {
                    property.setId(existing.getId());
                    property.setCreatedTime(existing.getCreatedTime());
                    goodsSkuPropertyMapper.updateById(property);
                }
            } catch (Exception e) {
                throw new IllegalStateException("导入单条商品SKU属性失败", e);
            }
        }
    }

    private Long resolveAccountId(Map<String, Object> map,
                                  Map<String, Long> unbToAccountId,
                                  String dataType) {
        String unb = requireString(map, "unb", dataType);
        Long accountId = unbToAccountId.get(unb);
        if (accountId == null) {
            throw new IllegalStateException("找不到" + dataType + "关联账号: " + unb);
        }
        return accountId;
    }

    private String requireString(Map<String, Object> map, String key, String dataType) {
        Object value = map.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(dataType + "缺少字段: " + key);
        }
        return string;
    }

    private Integer integerValue(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private void ensureAccountOwnership(Long existingAccountId, Long accountId, String dataType) {
        if (existingAccountId != null && !Objects.equals(existingAccountId, accountId)) {
            throw new IllegalStateException(dataType + "已属于其他账号，拒绝覆盖");
        }
    }
}
