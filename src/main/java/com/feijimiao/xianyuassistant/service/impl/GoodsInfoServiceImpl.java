package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.exception.BusinessException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsInfoMapper;
import com.feijimiao.xianyuassistant.controller.dto.ItemDTO;
import com.feijimiao.xianyuassistant.controller.dto.CreateItemDraftReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.UpdateItemInfoReqDTO;
import com.feijimiao.xianyuassistant.service.GoodsInfoService;
import com.feijimiao.xianyuassistant.service.PriceHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.Set;

/**
 * 商品信息服务实现类
 */
@Slf4j
@Service
public class GoodsInfoServiceImpl implements GoodsInfoService {

    @Autowired
    private XianyuGoodsInfoMapper goodsInfoMapper;

    @Autowired
    private PriceHistoryService priceHistoryService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    
    /**
     * 获取当前时间字符串
     */
    private LocalDateTime getCurrentTimeString() {
        return LocalDateTime.now();
    }

    /**
     * 商品保存成功后记录价格快照（失败不影响主流程）
     */
    private void recordPriceSnapshot(XianyuGoodsInfo goodsInfo) {
        try {
            priceHistoryService.recordPriceSnapshot(
                    goodsInfo.getXyGoodId(), goodsInfo.getXianyuAccountId(), goodsInfo.getSoldPrice());
        } catch (Exception e) {
            log.warn("记录商品价格快照失败: xyGoodId={}", goodsInfo.getXyGoodId(), e);
        }
    }

    private String normalizeRequiredText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + "不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private String normalizeOptionalText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + "不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private String normalizePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0 || price.scale() > 2 || price.compareTo(new BigDecimal("100000000")) > 0) {
            throw new IllegalArgumentException("商品价格必须大于0且最多保留2位小数");
        }
        return price.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    private String normalizeInfoPic(String raw) {
        if (raw == null || raw.isBlank()) {
            return "[]";
        }
        ArrayList<Map<String, String>> pics = new ArrayList<>();
        for (String value : raw.split(",")) {
            String url = value.trim();
            if (url.isEmpty()) continue;
            if (pics.size() >= 20) {
                throw new IllegalArgumentException("图片最多20张");
            }
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                throw new IllegalArgumentException("图片地址必须是 HTTP/HTTPS URL");
            }
            Map<String, String> pic = new LinkedHashMap<>();
            pic.put("url", url);
            pics.add(pic);
        }
        try {
            return objectMapper.writeValueAsString(pics);
        } catch (Exception e) {
            throw new IllegalArgumentException("图片资料格式无效");
        }
    }

    private String firstImageUrl(String normalizedInfoPic) {
        try {
            var images = objectMapper.readTree(normalizedInfoPic);
            if (images.isArray() && !images.isEmpty()) {
                return images.get(0).path("url").asText("");
            }
        } catch (Exception e) {
            log.warn("读取商品首图失败，将使用空封面", e);
        }
        return "";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveOrUpdateGoodsInfo(ItemDTO itemDTO, Long xianyuAccountId) {
        try {
            if (itemDTO == null || itemDTO.getDetailParams() == null) {
                log.warn("商品信息为空，跳过保存");
                return false;
            }
            
            String xyGoodId = itemDTO.getDetailParams().getItemId();
            if (xyGoodId == null || xyGoodId.isEmpty()) {
                log.warn("商品ID为空，跳过保存");
                return false;
            }
            
            // 查询是否已存在
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(XianyuGoodsInfo::getXyGoodId, xyGoodId);
            XianyuGoodsInfo existingGoods = goodsInfoMapper.selectOne(queryWrapper);
            
            // 构建商品信息
            XianyuGoodsInfo goodsInfo = new XianyuGoodsInfo();
            goodsInfo.setXyGoodId(xyGoodId);
            goodsInfo.setTitle(itemDTO.getDetailParams().getTitle());
            goodsInfo.setCoverPic(itemDTO.getDetailParams().getPicUrl());
            
            // 将图片信息JSON数组保存到info_pic字段
            String infoPic = itemDTO.getDetailParams().getImageInfos();
            goodsInfo.setInfoPic(infoPic);
            
            // 商品详情页URL
            goodsInfo.setDetailUrl(itemDTO.getDetailUrl());
            
            // 关联闲鱼账号ID
            goodsInfo.setXianyuAccountId(xianyuAccountId);
            
            // 价格信息
            if (itemDTO.getPriceInfo() != null) {
                goodsInfo.setSoldPrice(itemDTO.getPriceInfo().getPrice());
            }
            
            // 商品状态
            goodsInfo.setStatus(itemDTO.getItemStatus());
            
            if (existingGoods != null) {
                // 更新现有商品
                goodsInfo.setId(existingGoods.getId());
                goodsInfo.setUpdatedTime(getCurrentTimeString());
                int updated = goodsInfoMapper.updateById(goodsInfo);
                log.info("更新商品信息: xyGoodId={}, title={}, accountId={}", xyGoodId, goodsInfo.getTitle(), xianyuAccountId);
                if (updated > 0) {
                    recordPriceSnapshot(goodsInfo);
                }
                return updated > 0;
            } else {
                // 新增商品（ID使用雪花算法自动生成）
                goodsInfo.setCreatedTime(getCurrentTimeString());
                goodsInfo.setUpdatedTime(getCurrentTimeString());
                int inserted = goodsInfoMapper.insert(goodsInfo);
                log.info("新增商品信息: xyGoodId={}, title={}, id={}, accountId={}", 
                        xyGoodId, goodsInfo.getTitle(), goodsInfo.getId(), xianyuAccountId);
                if (inserted > 0) {
                    recordPriceSnapshot(goodsInfo);
                }
                return inserted > 0;
            }
            
        } catch (Exception e) {
            log.error("保存或更新商品信息失败: itemId={}", 
                    itemDTO.getDetailParams() != null ? itemDTO.getDetailParams().getItemId() : "null", e);
            throw new BusinessException("保存或更新商品信息失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchSaveOrUpdateGoodsInfo(List<ItemDTO> itemList, Long xianyuAccountId) {
        if (itemList == null || itemList.isEmpty()) {
            log.warn("商品列表为空，跳过批量保存");
            return 0;
        }
        
        int successCount = 0;
        for (ItemDTO itemDTO : itemList) {
            try {
                if (saveOrUpdateGoodsInfo(itemDTO, xianyuAccountId)) {
                    successCount++;
                }
            } catch (Exception e) {
                log.error("批量保存商品信息时出错: itemId={}", 
                        itemDTO.getDetailParams() != null ? itemDTO.getDetailParams().getItemId() : "null", e);
                // 继续处理下一个商品
            }
        }
        
        log.info("批量保存商品信息完成: 总数={}, 成功={}, accountId={}", itemList.size(), successCount, xianyuAccountId);
        return successCount;
    }

    @Override
    public XianyuGoodsInfo getByXyGoodId(String xyGoodId) {
        try {
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(XianyuGoodsInfo::getXyGoodId, xyGoodId);
            return goodsInfoMapper.selectOne(queryWrapper);
        } catch (Exception e) {
            log.error("根据闲鱼商品ID查询商品信息失败: xyGoodId={}", xyGoodId, e);
            return null;
        }
    }

    @Override
    public List<XianyuGoodsInfo> listByStatus(Integer status) {
        try {
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(XianyuGoodsInfo::getStatus, status);
            queryWrapper.orderByDesc(XianyuGoodsInfo::getUpdatedTime);
            return goodsInfoMapper.selectList(queryWrapper);
        } catch (Exception e) {
            log.error("根据状态查询商品列表失败: status={}", status, e);
            return null;
        }
    }
    
    @Override
    public List<XianyuGoodsInfo> listByStatusAndAccountId(Integer status, Long xianyuAccountId) {
        try {
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(XianyuGoodsInfo::getStatus, status);
            if (xianyuAccountId != null) {
                queryWrapper.eq(XianyuGoodsInfo::getXianyuAccountId, xianyuAccountId);
            }
            queryWrapper.orderByDesc(XianyuGoodsInfo::getUpdatedTime);
            return goodsInfoMapper.selectList(queryWrapper);
        } catch (Exception e) {
            log.error("根据状态和账号ID查询商品列表失败: status={}, accountId={}", status, xianyuAccountId, e);
            return null;
        }
    }
    
    @Override
    public List<XianyuGoodsInfo> listByStatus(Integer status, int pageNum, int pageSize) {
        try {
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(XianyuGoodsInfo::getStatus, status);
            queryWrapper.orderByDesc(XianyuGoodsInfo::getUpdatedTime);
            
            // 计算偏移量
            int offset = (pageNum - 1) * pageSize;
            
            // 使用MyBatis Plus的分页查询
            return goodsInfoMapper.selectList(queryWrapper.last("LIMIT " + offset + ", " + pageSize));
        } catch (Exception e) {
            log.error("根据状态查询商品列表失败: status={}, pageNum={}, pageSize={}", status, pageNum, pageSize, e);
            return new java.util.ArrayList<>(); // 返回空列表而不是null
        }
    }
    
    @Override
    public List<XianyuGoodsInfo> listByStatusAndAccountId(Integer status, Long xianyuAccountId, int pageNum, int pageSize) {
        try {
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(XianyuGoodsInfo::getStatus, status);
            if (xianyuAccountId != null) {
                queryWrapper.eq(XianyuGoodsInfo::getXianyuAccountId, xianyuAccountId);
            }
            queryWrapper.orderByDesc(XianyuGoodsInfo::getUpdatedTime);
            
            // 计算偏移量
            int offset = (pageNum - 1) * pageSize;
            
            // 使用MyBatis Plus的分页查询
            return goodsInfoMapper.selectList(queryWrapper.last("LIMIT " + offset + ", " + pageSize));
        } catch (Exception e) {
            log.error("根据状态和账号ID查询商品列表失败: status={}, accountId={}, pageNum={}, pageSize={}", 
                    status, xianyuAccountId, pageNum, pageSize, e);
            return new java.util.ArrayList<>(); // 返回空列表而不是null
        }
    }
    
    @Override
    public List<XianyuGoodsInfo> listByAccountId(Long xianyuAccountId, int pageNum, int pageSize) {
        try {
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            if (xianyuAccountId != null) {
                queryWrapper.eq(XianyuGoodsInfo::getXianyuAccountId, xianyuAccountId);
            }
            queryWrapper.orderByDesc(XianyuGoodsInfo::getUpdatedTime);
            int offset = (pageNum - 1) * pageSize;
            return goodsInfoMapper.selectList(queryWrapper.last("LIMIT " + offset + ", " + pageSize));
        } catch (Exception e) {
            log.error("根据账号ID查询商品列表失败: accountId={}, pageNum={}, pageSize={}", xianyuAccountId, pageNum, pageSize, e);
            return new java.util.ArrayList<>();
        }
    }
    
    @Override
    public int countByStatusAndAccountId(Integer status, Long xianyuAccountId) {
        try {
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(XianyuGoodsInfo::getStatus, status);
            if (xianyuAccountId != null) {
                queryWrapper.eq(XianyuGoodsInfo::getXianyuAccountId, xianyuAccountId);
            }
            return Math.toIntExact(goodsInfoMapper.selectCount(queryWrapper));
        } catch (Exception e) {
            log.error("根据状态和账号ID统计商品数量失败: status={}, accountId={}", status, xianyuAccountId, e);
            return 0;
        }
    }
    
    @Override
    public int countByAccountId(Long xianyuAccountId) {
        try {
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            if (xianyuAccountId != null) {
                queryWrapper.eq(XianyuGoodsInfo::getXianyuAccountId, xianyuAccountId);
            }
            return Math.toIntExact(goodsInfoMapper.selectCount(queryWrapper));
        } catch (Exception e) {
            log.error("根据账号ID统计商品数量失败: accountId={}", xianyuAccountId, e);
            return 0;
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateDetailInfo(String xyGoodId, String detailInfo) {
        try {
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(XianyuGoodsInfo::getXyGoodId, xyGoodId);
            XianyuGoodsInfo existingGoods = goodsInfoMapper.selectOne(queryWrapper);
            
            if (existingGoods == null) {
                log.warn("商品不存在，无法更新详情: xyGoodId={}", xyGoodId);
                return false;
            }
            
            existingGoods.setDetailInfo(detailInfo);
            existingGoods.setUpdatedTime(getCurrentTimeString());
            int updated = goodsInfoMapper.updateById(existingGoods);
            
            log.info("更新商品详情成功: xyGoodId={}, 详情长度={}", 
                    xyGoodId, detailInfo != null ? detailInfo.length() : 0);
            return updated > 0;
        } catch (Exception e) {
            log.error("更新商品详情失败: xyGoodId={}", xyGoodId, e);
            throw new BusinessException("更新商品详情失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public String getDetailInfoByGoodsId(String xyGoodId) {
        LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(XianyuGoodsInfo::getXyGoodId, xyGoodId);
        XianyuGoodsInfo goods = goodsInfoMapper.selectOne(queryWrapper);
        return goods != null ? goods.getDetailInfo() : null;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteGoodsInfo(Long xianyuAccountId, String xyGoodId) {
        try {
            // 查询商品信息
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(XianyuGoodsInfo::getXyGoodId, xyGoodId);
            queryWrapper.eq(XianyuGoodsInfo::getXianyuAccountId, xianyuAccountId);
            XianyuGoodsInfo existingGoods = goodsInfoMapper.selectOne(queryWrapper);
            
            if (existingGoods == null) {
                log.warn("商品不存在，无法删除: xyGoodId={}, accountId={}", xyGoodId, xianyuAccountId);
                return false;
            }
            
            // 删除商品
            int deleted = goodsInfoMapper.deleteById(existingGoods.getId());
            
            log.info("删除商品成功: xyGoodId={}, title={}, id={}, accountId={}", 
                    xyGoodId, existingGoods.getTitle(), existingGoods.getId(), xianyuAccountId);
            return deleted > 0;
        } catch (Exception e) {
            log.error("删除商品失败: xyGoodId={}, accountId={}", xyGoodId, xianyuAccountId, e);
            throw new BusinessException("删除商品失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateSkuCount(String xyGoodId, int skuCount) {
        try {
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(XianyuGoodsInfo::getXyGoodId, xyGoodId);
            XianyuGoodsInfo existingGoods = goodsInfoMapper.selectOne(queryWrapper);

            if (existingGoods == null) {
                log.warn("商品不存在，无法更新SKU数量: xyGoodId={}", xyGoodId);
                return false;
            }

            existingGoods.setSkuCount(skuCount);
            existingGoods.setUpdatedTime(getCurrentTimeString());
            int updated = goodsInfoMapper.updateById(existingGoods);

            log.info("更新商品SKU数量成功: xyGoodId={}, skuCount={}", xyGoodId, skuCount);
            return updated > 0;
        } catch (Exception e) {
            log.error("更新商品SKU数量失败: xyGoodId={}", xyGoodId, e);
            return false;
        }
    }

    @Override
    public void markOfflineIfNotInRemote(Long xianyuAccountId, Set<String> remoteItemIds) {
        if (xianyuAccountId == null || remoteItemIds == null) return;
        try {
            LambdaQueryWrapper<XianyuGoodsInfo> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(XianyuGoodsInfo::getXianyuAccountId, xianyuAccountId);
            queryWrapper.in(XianyuGoodsInfo::getStatus, 0, 1);
            List<XianyuGoodsInfo> localItems = goodsInfoMapper.selectList(queryWrapper);

            int markedCount = 0;
            for (XianyuGoodsInfo localItem : localItems) {
                if (!remoteItemIds.contains(localItem.getXyGoodId())) {
                    localItem.setStatus(-1);
                    localItem.setUpdatedTime(getCurrentTimeString());
                    goodsInfoMapper.updateById(localItem);
                    markedCount++;
                    log.info("商品已删除(远程不存在): xyGoodId={}, title={}", localItem.getXyGoodId(), localItem.getTitle());
                }
            }
            if (markedCount > 0) {
                log.info("【账号{}】同步时标记{}个商品为已删除(status=-1)", xianyuAccountId, markedCount);
            }
        } catch (Exception e) {
            log.error("标记删除商品失败: accountId={}", xianyuAccountId, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public XianyuGoodsInfo updateItemInfo(UpdateItemInfoReqDTO reqDTO) {
        if (reqDTO == null || reqDTO.getXianyuAccountId() == null || reqDTO.getXyGoodsId() == null
                || reqDTO.getXyGoodsId().isBlank()) {
            throw new IllegalArgumentException("账号ID和商品ID不能为空");
        }

        LambdaQueryWrapper<XianyuGoodsInfo> query = new LambdaQueryWrapper<>();
        query.eq(XianyuGoodsInfo::getXianyuAccountId, reqDTO.getXianyuAccountId())
                .eq(XianyuGoodsInfo::getXyGoodId, reqDTO.getXyGoodsId());
        XianyuGoodsInfo goods = goodsInfoMapper.selectOne(query);
        if (goods == null) {
            throw new IllegalArgumentException("商品不存在或不属于当前账号");
        }

        goods.setTitle(normalizeRequiredText(reqDTO.getTitle(), "商品标题", 120));
        goods.setDetailInfo(normalizeOptionalText(reqDTO.getDetailInfo(), "商品描述", 10000));
        goods.setSoldPrice(normalizePrice(reqDTO.getSoldPrice()));
        if (reqDTO.getInfoPic() != null) {
            String normalizedInfoPic = normalizeInfoPic(reqDTO.getInfoPic());
            goods.setInfoPic(normalizedInfoPic);
            goods.setCoverPic(firstImageUrl(normalizedInfoPic));
        }
        goods.setUpdatedTime(getCurrentTimeString());
        goodsInfoMapper.updateById(goods);
        log.info("更新本地商品资料成功: accountId={}, xyGoodId={}",
                reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
        return goods;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public XianyuGoodsInfo createItemDraft(CreateItemDraftReqDTO reqDTO) {
        if (reqDTO == null || reqDTO.getXianyuAccountId() == null) {
            throw new IllegalArgumentException("账号ID不能为空");
        }

        XianyuGoodsInfo goods = new XianyuGoodsInfo();
        goods.setXyGoodId("draft-" + UUID.randomUUID().toString().replace("-", ""));
        goods.setXianyuAccountId(reqDTO.getXianyuAccountId());
        goods.setTitle(normalizeRequiredText(reqDTO.getTitle(), "商品标题", 120));
        goods.setDetailInfo(normalizeOptionalText(reqDTO.getDetailInfo(), "商品描述", 10000));
        goods.setSoldPrice(normalizePrice(reqDTO.getSoldPrice()));
        String normalizedInfoPic = normalizeInfoPic(reqDTO.getInfoPic());
        goods.setInfoPic(normalizedInfoPic);
        goods.setCoverPic(firstImageUrl(normalizedInfoPic));
        goods.setStatus(-2); // 本地草稿，不代表闲鱼远程状态
        goods.setSkuCount(0);
        goods.setCreatedTime(getCurrentTimeString());
        goods.setUpdatedTime(getCurrentTimeString());
        goodsInfoMapper.insert(goods);
        log.info("创建本地商品草稿成功: accountId={}, xyGoodId={}",
                reqDTO.getXianyuAccountId(), goods.getXyGoodId());
        return goods;
    }
}

