package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.feijimiao.xianyuassistant.cache.CacheService;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsInfoMapper;
import com.feijimiao.xianyuassistant.service.DashboardStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
public class DashboardStatsServiceImpl implements DashboardStatsService {

    /** 统计缓存 TTL（秒） */
    private static final long CACHE_TTL_SECONDS = 30L;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private XianyuGoodsInfoMapper goodsMapper;

    @Autowired
    private CacheService cacheService;

    @Override
    public long countAccounts() {
        return cachedCount("dashboard:stats:accounts", () -> accountMapper.selectCount(null));
    }

    @Override
    public long countAccounts(com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return scopedCount("accounts", scope, () -> {
            QueryWrapper<com.feijimiao.xianyuassistant.entity.XianyuAccount> query = new QueryWrapper<>();
            if (scope.getAccountIds() != null) query.in("id", scope.getAccountIds());
            return accountMapper.selectCount(query);
        });
    }

    @Override
    public long countItems() {
        return cachedCount("dashboard:stats:items", () -> goodsMapper.selectCount(null));
    }

    @Override
    public long countItems(com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return countScopedGoods(null, scope);
    }

    @Override
    public long countSellingItems() {
        return cachedCount("dashboard:stats:selling",
                () -> goodsMapper.selectCount(new QueryWrapper<XianyuGoodsInfo>().eq("status", 0)));
    }

    @Override
    public long countSellingItems(com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return countScopedGoods(0, scope);
    }

    @Override
    public long countOffShelfItems() {
        return cachedCount("dashboard:stats:offshelf",
                () -> goodsMapper.selectCount(new QueryWrapper<XianyuGoodsInfo>().eq("status", 1)));
    }

    @Override
    public long countOffShelfItems(com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return countScopedGoods(1, scope);
    }

    @Override
    public long countSoldItems() {
        return cachedCount("dashboard:stats:sold",
                () -> goodsMapper.selectCount(new QueryWrapper<XianyuGoodsInfo>().eq("status", 2)));
    }

    @Override
    public long countSoldItems(com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return countScopedGoods(2, scope);
    }

    private long countScopedGoods(Integer status, com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return scopedCount("goods:" + status, scope,
                () -> (long) goodsMapper.countByAccountScope(status, null, scope.getAccountIds()));
    }

    private long scopedCount(String metric, com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope,
                             Supplier<Long> loader) {
        if (scope.getAccountIds() != null && scope.getAccountIds().isEmpty()) return 0;
        return cachedCount("dashboard:stats:" + metric + ":" + scope.cacheKeyPart(), loader);
    }

    /**
     * 带缓存的计数查询：先读缓存，未命中则执行查询并写入缓存；任何异常回退为直接查询
     */
    private long cachedCount(String key, Supplier<Long> loader) {
        try {
            Long cached = cacheService.get(key, Long.class);
            if (cached != null) {
                return cached;
            }
            long value = loader.get();
            cacheService.put(key, value, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            return value;
        } catch (Exception e) {
            log.warn("读取统计缓存失败，回退直接查询: key={}", key, e);
            return loader.get();
        }
    }
}
