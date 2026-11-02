package com.feijimiao.xianyuassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feijimiao.xianyuassistant.entity.XianyuKamiUsageRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface XianyuKamiUsageRecordMapper extends BaseMapper<XianyuKamiUsageRecord> {

    @Select("SELECT * FROM xianyu_kami_usage_record WHERE kami_item_id = #{kamiItemId} AND order_id = #{orderId} LIMIT 1")
    XianyuKamiUsageRecord findByItemIdAndOrderId(@Param("kamiItemId") Long kamiItemId,
                                                  @Param("orderId") String orderId);

    @Select("SELECT * FROM xianyu_kami_usage_record WHERE kami_item_id = #{kamiItemId} ORDER BY id DESC")
    java.util.List<XianyuKamiUsageRecord> findByItemId(@Param("kamiItemId") Long kamiItemId);

    @Delete("DELETE FROM xianyu_kami_usage_record WHERE kami_item_id = #{kamiItemId}")
    int deleteByItemId(@Param("kamiItemId") Long kamiItemId);

    @Delete("DELETE FROM xianyu_kami_usage_record WHERE kami_item_id = #{kamiItemId} AND order_id = #{orderId}")
    int deleteByItemIdAndOrderId(@Param("kamiItemId") Long kamiItemId,
                                 @Param("orderId") String orderId);
}
