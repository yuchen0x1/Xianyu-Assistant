package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.controller.vo.PriceHistoryTrendVO;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsPriceHistory;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsPriceHistoryMapper;
import com.feijimiao.xianyuassistant.service.PriceHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品价格历史服务实现类
 */
@Slf4j
@Service
public class PriceHistoryServiceImpl implements PriceHistoryService {

    @Autowired
    private XianyuGoodsPriceHistoryMapper priceHistoryMapper;

    @Override
    public void recordPriceSnapshot(String xyGoodId, Long accountId, String priceStr) {
        try {
            if (xyGoodId == null || xyGoodId.isBlank()) {
                return;
            }
            BigDecimal price = parsePrice(priceStr);
            if (price == null) {
                log.debug("商品价格无法解析，跳过快照: xyGoodId={}, price={}", xyGoodId, priceStr);
                return;
            }

            // 去重：同一商品同一天内最多记录一条，已有则更新价格，避免商品刷新产生大量重复数据
            LocalDateTime todayStart = LocalDate.now().atStartOfDay();
            LocalDateTime todayEnd = todayStart.plusDays(1);
            LambdaQueryWrapper<XianyuGoodsPriceHistory> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(XianyuGoodsPriceHistory::getXyGoodId, xyGoodId)
                    .ge(XianyuGoodsPriceHistory::getSnapshotTime, todayStart)
                    .lt(XianyuGoodsPriceHistory::getSnapshotTime, todayEnd)
                    .last("LIMIT 1");
            XianyuGoodsPriceHistory existing = priceHistoryMapper.selectOne(wrapper);

            if (existing != null) {
                existing.setPrice(price);
                existing.setXianyuAccountId(accountId);
                priceHistoryMapper.updateById(existing);
                log.debug("更新当日价格快照: xyGoodId={}, price={}", xyGoodId, price);
            } else {
                XianyuGoodsPriceHistory history = new XianyuGoodsPriceHistory();
                history.setXyGoodId(xyGoodId);
                history.setXianyuAccountId(accountId);
                history.setPrice(price);
                history.setSnapshotTime(LocalDateTime.now());
                priceHistoryMapper.insert(history);
                log.debug("新增价格快照: xyGoodId={}, price={}", xyGoodId, price);
            }
        } catch (Exception e) {
            log.warn("记录价格快照失败: xyGoodId={}, price={}", xyGoodId, priceStr, e);
        }
    }

    @Override
    public List<PriceHistoryTrendVO> queryTrend(String xyGoodId, int days) {
        List<PriceHistoryTrendVO> result = new ArrayList<>();
        try {
            int limit = Math.max(1, Math.min(days, 3650));
            List<XianyuGoodsPriceHistoryMapper.PriceTrendPoint> points =
                    priceHistoryMapper.queryTrend(xyGoodId, limit);
            for (XianyuGoodsPriceHistoryMapper.PriceTrendPoint point : points) {
                PriceHistoryTrendVO vo = new PriceHistoryTrendVO();
                vo.setDate(point.getDate());
                vo.setPrice(point.getPrice());
                result.add(vo);
            }
        } catch (Exception e) {
            log.error("查询商品价格趋势失败: xyGoodId={}", xyGoodId, e);
        }
        return result;
    }

    /**
     * 解析价格字符串，兼容 "¥123.00"、"￥123"、"123.50" 等格式
     *
     * @return 解析失败返回 null
     */
    private BigDecimal parsePrice(String priceStr) {
        if (priceStr == null || priceStr.isBlank()) {
            return null;
        }
        try {
            String cleaned = priceStr.trim();
            if (cleaned.startsWith("¥") || cleaned.startsWith("￥")) {
                cleaned = cleaned.substring(1).trim();
            }
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
