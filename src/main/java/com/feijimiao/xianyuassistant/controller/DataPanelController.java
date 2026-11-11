package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.cache.CacheService;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.service.DataPanelAccountScope;
import com.feijimiao.xianyuassistant.service.OrderQueryService;
import com.feijimiao.xianyuassistant.service.AutoReplyRecordService;
import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.DataPanelStatsRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.DataPanelTrendRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.SalesRevenueRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.AccountCompareRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/data-panel")
@RequirePermission("dashboard:read")
public class DataPanelController {

    
    
    @Autowired
    private CacheService cacheService;
    @Autowired
    private OrderQueryService orderQueryService;

    @Autowired
    private AutoReplyRecordService autoReplyRecordService;

    @Autowired
    private RbacService rbacService;

    @Autowired
    private com.feijimiao.xianyuassistant.mapper.XianyuChatMessageMapper chatMessageMapper;

    @Autowired
    private com.feijimiao.xianyuassistant.mapper.XianyuGoodsInfoMapper goodsInfoMapper;

    @Autowired
    private com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper accountMapper;

    @Autowired
    private com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;

    @Autowired
    private com.feijimiao.xianyuassistant.mapper.XianyuOperationLogMapper operationLogMapper;

    private static final long CACHE_TTL_MINUTES = 30;

    private DataPanelAccountScope currentScope() {
        Long userId = UserContext.getUserId();
        List<Long> visibleAccountIds = rbacService.getCommandVisibleAccountIds(userId);
        return visibleAccountIds == null
                ? DataPanelAccountScope.all(userId)
                : DataPanelAccountScope.restricted(userId, visibleAccountIds);
    }

    private String scopedCacheKey(String prefix, DataPanelAccountScope scope, Object... parts) {
        StringBuilder key = new StringBuilder(prefix).append(":").append(scope.cacheKeyPart());
        for (Object part : parts) key.append(":").append(part);
        return key.toString();
    }

    private LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(field + "格式必须为 yyyy-MM-dd");
        }
    }

    private void validateRange(LocalDate start, LocalDate end) {
        if (start != null && end != null) {
            if (start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
            if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > 366)
                throw new IllegalArgumentException("日期范围不能超过366天");
        }
    }

    private int validateLimit(Integer limit, int defaultValue, int max) {
        if (limit == null) return defaultValue;
        if (limit < 1 || limit > max) throw new IllegalArgumentException("limit必须在1到" + max + "之间");
        return limit;
    }

    @PostMapping("/stats")
    public ResultObject<DataPanelStatsRespDTO> getDataPanelStats(@RequestBody(required = false) StatsReq req) {
        try {
            DataPanelAccountScope scope = currentScope();
            String date;
            if (req != null && req.getDate() != null && !req.getDate().isEmpty()) {
                date = parseDate(req.getDate(), "date").toString();
            } else {
                date = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            }

            boolean isToday = date.equals(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
            String cacheKey = scopedCacheKey("dataPanelStats", scope, date);

            if (!isToday) {
                DataPanelStatsRespDTO cached = cacheService.get(cacheKey, DataPanelStatsRespDTO.class);
                if (cached != null) {
                    return ResultObject.success(cached);
                }
            }

            DataPanelStatsRespDTO respDTO = new DataPanelStatsRespDTO();
            respDTO.setOrderCount(orderQueryService.countOrdersByDate(date, scope));
            respDTO.setDeliverySuccessCount(orderQueryService.countDeliverySuccessByDate(date, scope));
            respDTO.setDeliveryFailCount(orderQueryService.countDeliveryFailByDate(date, scope));
            respDTO.setAiReplyCount(autoReplyRecordService.countAiRepliesByDateScoped(date, scope));
            respDTO.setHasData(orderQueryService.countAllOrders(scope) > 0 || autoReplyRecordService.countAllRepliesScoped(scope) > 0);

            if (!isToday) {
                cacheService.put(cacheKey, respDTO, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }

            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取数据面板统计失败", e);
            return ResultObject.failed("获取数据面板统计失败: " + e.getMessage());
        }
    }

    @PostMapping("/trend")
    public ResultObject<DataPanelTrendRespDTO> getDataPanelTrend() {
        try {
            DataPanelAccountScope scope = currentScope();
            String cacheKey = scopedCacheKey("dataPanelTrend", scope, LocalDate.now());
            DataPanelTrendRespDTO cached = cacheService.get(cacheKey, DataPanelTrendRespDTO.class);
            if (cached != null) {
                return ResultObject.success(cached);
            }

            DataPanelTrendRespDTO respDTO = new DataPanelTrendRespDTO();
            List<String> dates = new ArrayList<>();
            List<Integer> deliverySuccess = new ArrayList<>();
            List<Integer> deliveryFail = new ArrayList<>();
            List<Integer> aiReplies = new ArrayList<>();

            LocalDate today = LocalDate.now();
            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;

            for (int i = 7; i >= 1; i--) {
                LocalDate d = today.minusDays(i);
                String dateStr = d.format(fmt);
                dates.add(d.getMonthValue() + "/" + d.getDayOfMonth());
                deliverySuccess.add(orderQueryService.countDeliverySuccessByDate(dateStr, scope));
                deliveryFail.add(orderQueryService.countDeliveryFailByDate(dateStr, scope));
                aiReplies.add(autoReplyRecordService.countAiRepliesByDateScoped(dateStr, scope));
            }

            respDTO.setDates(dates);
            respDTO.setDeliverySuccess(deliverySuccess);
            respDTO.setDeliveryFail(deliveryFail);
            respDTO.setAiReplies(aiReplies);

            cacheService.put(cacheKey, respDTO, 10, TimeUnit.MINUTES);

            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取数据面板趋势失败", e);
            return ResultObject.failed("获取数据面板趋势失败: " + e.getMessage());
        }
    }

    @GetMapping("/realtimeRevenue")
    public ResultObject<Double> getRealtimeRevenue() {
        try {
            DataPanelAccountScope scope = currentScope();
            double amount = orderQueryService.sumDeliverySuccessAmount(scope);
            return ResultObject.success(amount);
        } catch (Exception e) {
            log.error("获取实时销售额失败", e);
            return ResultObject.failed("获取实时销售额失败: " + e.getMessage());
        }
    }

    @lombok.Data
    public static class StatsReq {
        private String date;
    }

    @PostMapping("/salesRevenue")
    public ResultObject<SalesRevenueRespDTO> getSalesRevenue(@Valid @RequestBody SalesRevenueReq req) {
        try {
            DataPanelAccountScope scope = currentScope();
            String dimension = req.getDimension() != null ? req.getDimension() : "day";
            String startDateStr = req.getStartDate();
            String endDateStr = req.getEndDate();

            LocalDate endDate;
            LocalDate startDate;
            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;

            if (endDateStr != null && !endDateStr.isEmpty()) {
                endDate = LocalDate.parse(endDateStr, fmt);
            } else {
                endDate = LocalDate.now();
            }

            if (startDateStr != null && !startDateStr.isEmpty()) {
                startDate = LocalDate.parse(startDateStr, fmt);
            } else {
                startDate = endDate.minusDays(9);
            }
            validateRange(startDate, endDate);

            boolean isToday = !endDate.isBefore(LocalDate.now());
            String cacheKey = scopedCacheKey("salesRevenue", scope, dimension, startDate, endDate);

            if (!isToday) {
                SalesRevenueRespDTO cached = cacheService.get(cacheKey, SalesRevenueRespDTO.class);
                if (cached != null) {
                    log.debug("销售额趋势命中缓存: {}", cacheKey);
                    return ResultObject.success(cached);
                }
            }

            SalesRevenueRespDTO respDTO = new SalesRevenueRespDTO();
            List<String> labels = new ArrayList<>();
            List<Double> values = new ArrayList<>();

            switch (dimension) {
                case "week":
                    buildWeeklyData(startDate, endDate, labels, values, scope);
                    break;
                case "month":
                    buildMonthlyData(startDate, endDate, labels, values, scope);
                    break;
                case "quarter":
                    buildQuarterlyData(startDate, endDate, labels, values, scope);
                    break;
                default:
                    buildDailyData(startDate, endDate, labels, values, scope);
                    break;
            }

            respDTO.setLabels(labels);
            respDTO.setValues(values);

            if (!isToday) {
                cacheService.put(cacheKey, respDTO, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }

            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取销售额趋势失败", e);
            return ResultObject.failed("获取销售额趋势失败: " + e.getMessage());
        }
    }

    private void buildDailyData(LocalDate startDate, LocalDate endDate, List<String> labels, List<Double> values, DataPanelAccountScope scope) {
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("M/d");
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            labels.add(d.format(labelFmt));
            values.add(orderQueryService.sumDeliverySuccessAmountByDate(d.format(fmt), scope));
        }
    }

    private void buildWeeklyData(LocalDate startDate, LocalDate endDate, List<String> labels, List<Double> values, DataPanelAccountScope scope) {
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        LocalDate weekStart = startDate;
        int weekNum = 1;
        while (!weekStart.isAfter(endDate)) {
            LocalDate weekEnd = weekStart.plusDays(6);
            if (weekEnd.isAfter(endDate)) weekEnd = endDate;
            labels.add("第" + weekNum + "周");
            values.add(orderQueryService.sumDeliverySuccessAmountByDateRange(weekStart.format(fmt), weekEnd.format(fmt), scope));
            weekStart = weekEnd.plusDays(1);
            weekNum++;
        }
    }

    private void buildMonthlyData(LocalDate startDate, LocalDate endDate, List<String> labels, List<Double> values, DataPanelAccountScope scope) {
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        LocalDate monthStart = startDate.withDayOfMonth(1);
        while (!monthStart.isAfter(endDate)) {
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
            if (monthEnd.isAfter(endDate)) monthEnd = endDate;
            if (monthStart.isBefore(startDate)) monthStart = startDate;
            labels.add(monthStart.getYear() + "/" + monthStart.getMonthValue());
            values.add(orderQueryService.sumDeliverySuccessAmountByDateRange(monthStart.format(fmt), monthEnd.format(fmt), scope));
            monthStart = monthStart.plusMonths(1).withDayOfMonth(1);
        }
    }

    private void buildQuarterlyData(LocalDate startDate, LocalDate endDate, List<String> labels, List<Double> values, DataPanelAccountScope scope) {
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        LocalDate quarterStart = startDate;
        while (!quarterStart.isAfter(endDate)) {
            int quarter = (quarterStart.getMonthValue() - 1) / 3 + 1;
            LocalDate quarterEnd = quarterStart.plusMonths(3).minusDays(1);
            if (quarterEnd.isAfter(endDate)) quarterEnd = endDate;
            LocalDate actualStart = quarterStart.isBefore(startDate) ? startDate : quarterStart;
            labels.add(quarterStart.getYear() + "Q" + quarter);
            values.add(orderQueryService.sumDeliverySuccessAmountByDateRange(actualStart.format(fmt), quarterEnd.format(fmt), scope));
            quarterStart = quarterEnd.plusDays(1);
        }
    }

    @lombok.Data
    public static class SalesRevenueReq {
        private String dimension;
        private String startDate;
        private String endDate;
    }

    /**
     * 商品排行（成交TOP + 咨询TOP）
     */
    @PostMapping("/goodsRank")
    public ResultObject<com.feijimiao.xianyuassistant.controller.dto.GoodsRankRespDTO> getGoodsRank(@RequestBody(required = false) GoodsRankReq req) {
        try {
            DataPanelAccountScope scope = currentScope();
            int limit = (req != null && req.getLimit() != null && req.getLimit() > 0)
                    ? Math.min(req.getLimit(), 10) : 5;

            com.feijimiao.xianyuassistant.controller.dto.GoodsRankRespDTO respDTO =
                    new com.feijimiao.xianyuassistant.controller.dto.GoodsRankRespDTO();

            // 成交排行
            List<com.feijimiao.xianyuassistant.controller.dto.GoodsRankRespDTO.RankItem> orderRank = new ArrayList<>();
            List<java.util.Map<String, Object>> orderRows = orderQueryService.getTopGoodsByOrders(limit, scope);
            for (java.util.Map<String, Object> row : orderRows) {
                com.feijimiao.xianyuassistant.controller.dto.GoodsRankRespDTO.RankItem item =
                        new com.feijimiao.xianyuassistant.controller.dto.GoodsRankRespDTO.RankItem();
                item.setGoodsId(objStr(row.get("goodsId")));
                item.setGoodsTitle(fillGoodsTitle(objStr(row.get("goodsId")), objStr(row.get("goodsTitle"))));
                item.setCount(((Number) row.getOrDefault("orderCount", 0)).longValue());
                item.setAmount(((Number) row.getOrDefault("totalAmount", 0)).doubleValue());
                orderRank.add(item);
            }
            respDTO.setOrderRank(orderRank);

            // 咨询排行
            List<com.feijimiao.xianyuassistant.controller.dto.GoodsRankRespDTO.RankItem> inquiryRank = new ArrayList<>();
            List<java.util.Map<String, Object>> msgRows = chatMessageMapper.selectTopGoodsByMessagesScoped(limit, scope);
            for (java.util.Map<String, Object> row : msgRows) {
                com.feijimiao.xianyuassistant.controller.dto.GoodsRankRespDTO.RankItem item =
                        new com.feijimiao.xianyuassistant.controller.dto.GoodsRankRespDTO.RankItem();
                item.setGoodsId(objStr(row.get("goodsId")));
                item.setGoodsTitle(fillGoodsTitle(objStr(row.get("goodsId")), null));
                item.setCount(((Number) row.getOrDefault("msgCount", 0)).longValue());
                inquiryRank.add(item);
            }
            respDTO.setInquiryRank(inquiryRank);

            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取商品排行失败", e);
            return ResultObject.failed("获取商品排行失败: " + e.getMessage());
        }
    }

    /**
     * 补全商品标题：优先使用已有标题，否则从商品表查询
     */
    private String fillGoodsTitle(String goodsId, String existingTitle) {
        if (existingTitle != null && !existingTitle.isBlank()) {
            return existingTitle;
        }
        if (goodsId == null || goodsId.isEmpty()) {
            return "未知商品";
        }
        try {
            com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo info = goodsInfoMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo>()
                            .eq(com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo::getXyGoodId, goodsId)
                            .last("LIMIT 1")
            );
            if (info != null && info.getTitle() != null && !info.getTitle().isBlank()) {
                return info.getTitle();
            }
        } catch (Exception ignored) {
        }
        return "商品" + goodsId;
    }

    private static String objStr(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * 多账号对比统计（指定日期各账号订单/发货/回复/销售额）
     */
    @PostMapping("/accountCompare")
    public ResultObject<AccountCompareRespDTO> getAccountCompare(@RequestBody(required = false) AccountCompareReq req) {
        try {
            DataPanelAccountScope scope = currentScope();
            String date;
            if (req != null && req.getDate() != null && !req.getDate().isEmpty()) {
                date = parseDate(req.getDate(), "date").toString();
            } else {
                date = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            }

            boolean isToday = date.equals(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
            String cacheKey = scopedCacheKey("accountCompare", scope, date);
            if (!isToday) {
                AccountCompareRespDTO cached = cacheService.get(cacheKey, AccountCompareRespDTO.class);
                if (cached != null) {
                    return ResultObject.success(cached);
                }
            }

            AccountCompareRespDTO respDTO = new AccountCompareRespDTO();

            // 账号名缓存（避免重复查询）
            Map<Long, String> nameCache = new HashMap<>();

            // 订单统计
            List<java.util.Map<String, Object>> orderRows = orderQueryService.getAccountCompareByDate(date, scope);
            for (java.util.Map<String, Object> row : orderRows) {
                AccountCompareRespDTO.AccountStat stat = new AccountCompareRespDTO.AccountStat();
                Long accountId = row.get("accountId") != null ? Long.parseLong(objStr(row.get("accountId"))) : null;
                stat.setAccountId(accountId);
                stat.setAccountName(accountId != null ? getAccountName(accountId, nameCache) : "未知账号");
                stat.setOrderCount(((Number) row.getOrDefault("orderCount", 0)).intValue());
                stat.setSuccessCount(((Number) row.getOrDefault("successCount", 0)).intValue());
                stat.setFailCount(((Number) row.getOrDefault("failCount", 0)).intValue());
                stat.setAmount(((Number) row.getOrDefault("amount", 0)).doubleValue());
                respDTO.getAccounts().add(stat);
                respDTO.setTotalOrders(respDTO.getTotalOrders() + stat.getOrderCount());
                respDTO.setTotalAmount(respDTO.getTotalAmount() + stat.getAmount());
            }

            // AI回复统计
            List<java.util.Map<String, Object>> replyRows = autoReplyRecordService.getAccountRepliesByDateScoped(date, scope);
            if (replyRows != null) {
                Map<Long, Integer> replyMap = new HashMap<>();
                for (java.util.Map<String, Object> row : replyRows) {
                    if (row.get("accountId") == null) continue;
                    Long accountId = Long.parseLong(objStr(row.get("accountId")));
                    int count = ((Number) row.getOrDefault("replyCount", 0)).intValue();
                    replyMap.put(accountId, count);
                    respDTO.setTotalReplies(respDTO.getTotalReplies() + count);
                }
                for (AccountCompareRespDTO.AccountStat stat : respDTO.getAccounts()) {
                    stat.setReplyCount(replyMap.getOrDefault(stat.getAccountId(), 0));
                }
            }

            if (!isToday) {
                cacheService.put(cacheKey, respDTO, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }

            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取多账号对比失败", e);
            return ResultObject.failed("获取多账号对比失败: " + e.getMessage());
        }
    }

    private String getAccountName(Long accountId, Map<Long, String> nameCache) {
        String cached = nameCache.get(accountId);
        if (cached != null) {
            return cached;
        }
        try {
            com.feijimiao.xianyuassistant.entity.XianyuAccount account = accountMapper.selectById(accountId);
            String name = account != null && account.getAccountNote() != null && !account.getAccountNote().isBlank()
                    ? account.getAccountNote()
                    : (account != null && account.getUnb() != null && !account.getUnb().isBlank() ? account.getUnb() : "账号" + accountId);
            nameCache.put(accountId, name);
            return name;
        } catch (Exception e) {
            return "账号" + accountId;
        }
    }

    @lombok.Data
    public static class AccountCompareReq {
        private String date;
    }

    @lombok.Data
    public static class GoodsRankReq {
        private Integer limit;
    }

    /**
     * 关键词命中排行（指定日期各关键词命中/成功/失败统计）
     */
    @PostMapping("/keywordRank")
    public ResultObject<com.feijimiao.xianyuassistant.controller.dto.KeywordRankRespDTO> getKeywordRank(
            @RequestBody(required = false) KeywordRankReq req) {
        try {
            DataPanelAccountScope scope = currentScope();
            String date;
            if (req != null && req.getDate() != null && !req.getDate().isEmpty()) {
                date = parseDate(req.getDate(), "date").toString();
            } else {
                date = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            int limit = (req != null && req.getLimit() != null && req.getLimit() > 0)
                    ? Math.min(req.getLimit(), 20) : 10;

            boolean isToday = date.equals(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
            String cacheKey = scopedCacheKey("keywordRank", scope, date, limit);
            if (!isToday) {
                com.feijimiao.xianyuassistant.controller.dto.KeywordRankRespDTO cached =
                        cacheService.get(cacheKey, com.feijimiao.xianyuassistant.controller.dto.KeywordRankRespDTO.class);
                if (cached != null) {
                    return ResultObject.success(cached);
                }
            }

            com.feijimiao.xianyuassistant.controller.dto.KeywordRankRespDTO respDTO =
                    new com.feijimiao.xianyuassistant.controller.dto.KeywordRankRespDTO();
            List<com.feijimiao.xianyuassistant.controller.dto.KeywordRankRespDTO.RankItem> items = new ArrayList<>();
            long totalHits = 0;
            List<Map<String, Object>> rows = autoReplyRecordMapper.selectKeywordRankByDateScoped(date, limit, scope);
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    com.feijimiao.xianyuassistant.controller.dto.KeywordRankRespDTO.RankItem item =
                            new com.feijimiao.xianyuassistant.controller.dto.KeywordRankRespDTO.RankItem();
                    item.setKeyword(objStr(row.get("keyword")));
                    item.setHitCount(((Number) row.getOrDefault("hitCount", 0)).longValue());
                    item.setSuccessCount(((Number) row.getOrDefault("successCount", 0)).longValue());
                    item.setFailCount(((Number) row.getOrDefault("failCount", 0)).longValue());
                    item.setPendingCount(((Number) row.getOrDefault("pendingCount", 0)).longValue());
                    item.setBuyerCount(((Number) row.getOrDefault("buyerCount", 0)).longValue());
                    totalHits += item.getHitCount();
                    items.add(item);
                }
            }
            respDTO.setItems(items);
            respDTO.setTotalHits(totalHits);

            if (!isToday) {
                cacheService.put(cacheKey, respDTO, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }

            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取关键词命中排行失败", e);
            return ResultObject.failed("获取关键词命中排行失败: " + e.getMessage());
        }
    }

    @lombok.Data
    public static class KeywordRankReq {
        private String date;
        private Integer limit;
    }

    /**
     * 操作健康度统计（指定日期各操作类型成功率/耗时）
     */
    @PostMapping("/operationStats")
    public ResultObject<com.feijimiao.xianyuassistant.controller.dto.OperationStatsRespDTO> getOperationStats(
            @RequestBody(required = false) OperationStatsReq req) {
        try {
            DataPanelAccountScope scope = currentScope();
            String date;
            if (req != null && req.getDate() != null && !req.getDate().isEmpty()) {
                date = parseDate(req.getDate(), "date").toString();
            } else {
                date = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            int limit = (req != null && req.getLimit() != null && req.getLimit() > 0)
                    ? Math.min(req.getLimit(), 20) : 10;

            boolean isToday = date.equals(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
            String cacheKey = scopedCacheKey("operationStats", scope, date, limit);
            if (!isToday) {
                com.feijimiao.xianyuassistant.controller.dto.OperationStatsRespDTO cached =
                        cacheService.get(cacheKey, com.feijimiao.xianyuassistant.controller.dto.OperationStatsRespDTO.class);
                if (cached != null) {
                    return ResultObject.success(cached);
                }
            }

            com.feijimiao.xianyuassistant.controller.dto.OperationStatsRespDTO respDTO =
                    new com.feijimiao.xianyuassistant.controller.dto.OperationStatsRespDTO();
            List<com.feijimiao.xianyuassistant.controller.dto.OperationStatsRespDTO.TypeStat> types = new ArrayList<>();
            long totalOps = 0, totalSuccess = 0, totalFail = 0, totalPartial = 0;

            List<Map<String, Object>> rows = operationLogMapper.selectOperationStatsByDateScoped(date, limit, scope);
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    com.feijimiao.xianyuassistant.controller.dto.OperationStatsRespDTO.TypeStat stat =
                            new com.feijimiao.xianyuassistant.controller.dto.OperationStatsRespDTO.TypeStat();
                    stat.setType(objStr(row.get("typeName")));
                    long total = ((Number) row.getOrDefault("totalCount", 0)).longValue();
                    long success = ((Number) row.getOrDefault("successCount", 0)).longValue();
                    long fail = ((Number) row.getOrDefault("failCount", 0)).longValue();
                    long partial = ((Number) row.getOrDefault("partialCount", 0)).longValue();
                    double avg = row.get("avgDurationMs") != null
                            ? ((Number) row.get("avgDurationMs")).doubleValue() : 0;
                    stat.setTotalCount(total);
                    stat.setSuccessCount(success);
                    stat.setFailCount(fail);
                    stat.setPartialCount(partial);
                    stat.setAvgDurationMs(Math.round(avg * 10) / 10.0);
                    stat.setSuccessRate(total > 0 ? Math.round(success * 1000.0 / total) / 10.0 : 0);

                    totalOps += total;
                    totalSuccess += success;
                    totalFail += fail;
                    totalPartial += partial;
                    types.add(stat);
                }
            }

            respDTO.setTypes(types);
            respDTO.setTotalOperations(totalOps);
            respDTO.setTotalSuccess(totalSuccess);
            respDTO.setTotalFail(totalFail);
            respDTO.setTotalPartial(totalPartial);
            respDTO.setSuccessRate(totalOps > 0 ? Math.round(totalSuccess * 1000.0 / totalOps) / 10.0 : 0);

            if (!isToday) {
                cacheService.put(cacheKey, respDTO, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }

            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取操作健康度统计失败", e);
            return ResultObject.failed("获取操作健康度统计失败: " + e.getMessage());
        }
    }

    @lombok.Data
    public static class OperationStatsReq {
        private String date;
        private Integer limit;
    }

    /**
     * 失败原因排行（指定日期最常见的操作失败原因）
     */
    @PostMapping("/failureRank")
    public ResultObject<com.feijimiao.xianyuassistant.controller.dto.FailureRankRespDTO> getFailureRank(
            @RequestBody(required = false) OperationStatsReq req) {
        try {
            DataPanelAccountScope scope = currentScope();
            String date;
            if (req != null && req.getDate() != null && !req.getDate().isEmpty()) {
                date = parseDate(req.getDate(), "date").toString();
            } else {
                date = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            int limit = (req != null && req.getLimit() != null && req.getLimit() > 0)
                    ? Math.min(req.getLimit(), 20) : 10;

            boolean isToday = date.equals(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
            String cacheKey = scopedCacheKey("failureRank", scope, date, limit);
            if (!isToday) {
                com.feijimiao.xianyuassistant.controller.dto.FailureRankRespDTO cached =
                        cacheService.get(cacheKey, com.feijimiao.xianyuassistant.controller.dto.FailureRankRespDTO.class);
                if (cached != null) {
                    return ResultObject.success(cached);
                }
            }

            com.feijimiao.xianyuassistant.controller.dto.FailureRankRespDTO respDTO =
                    new com.feijimiao.xianyuassistant.controller.dto.FailureRankRespDTO();
            List<com.feijimiao.xianyuassistant.controller.dto.FailureRankRespDTO.FailItem> items = new ArrayList<>();
            long totalFailures = 0;

            List<Map<String, Object>> rows = operationLogMapper.selectFailureRankByDateScoped(date, limit, scope);
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    com.feijimiao.xianyuassistant.controller.dto.FailureRankRespDTO.FailItem item =
                            new com.feijimiao.xianyuassistant.controller.dto.FailureRankRespDTO.FailItem();
                    item.setReason(objStr(row.get("reason")));
                    long count = ((Number) row.getOrDefault("failCount", 0)).longValue();
                    item.setFailCount(count);
                    item.setRelatedTypes(objStr(row.get("relatedTypes")));
                    totalFailures += count;
                    items.add(item);
                }
            }
            respDTO.setItems(items);
            respDTO.setTotalFailures(totalFailures);

            if (!isToday) {
                cacheService.put(cacheKey, respDTO, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }

            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取失败原因排行失败", e);
            return ResultObject.failed("获取失败原因排行失败: " + e.getMessage());
        }
    }
}
