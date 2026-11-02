package com.feijimiao.xianyuassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsPriceHistory;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品价格历史 Mapper
 */
@Mapper
public interface XianyuGoodsPriceHistoryMapper extends BaseMapper<XianyuGoodsPriceHistory> {

    /**
     * 查询商品最近的价格趋势点（按日期升序，最多 limit 条）
     *
     * @param xyGoodId 闲鱼商品ID
     * @param limit    最多返回条数
     * @return 价格趋势点列表
     */
    @Select("SELECT date(snapshot_time) AS date, price " +
            "FROM xianyu_goods_price_history " +
            "WHERE xy_good_id = #{xyGoodId} " +
            "ORDER BY snapshot_time ASC " +
            "LIMIT #{limit}")
    List<PriceTrendPoint> queryTrend(@Param("xyGoodId") String xyGoodId, @Param("limit") int limit);

    /**
     * 价格趋势点：date 为快照日期（yyyy-MM-dd），price 为当日价格
     */
    @Data
    class PriceTrendPoint {
        /** 快照日期（yyyy-MM-dd） */
        private String date;

        /** 当日价格 */
        private BigDecimal price;
    }
}
