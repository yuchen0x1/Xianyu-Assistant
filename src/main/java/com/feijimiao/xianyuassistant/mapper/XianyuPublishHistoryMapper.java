package com.feijimiao.xianyuassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feijimiao.xianyuassistant.entity.XianyuPublishHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品发布/下架历史 Mapper
 */
@Mapper
public interface XianyuPublishHistoryMapper extends BaseMapper<XianyuPublishHistory> {

    @Select("<script>SELECT * FROM xianyu_publish_history " +
            "<where>" +
            "<if test='accountId != null'>AND account_id = #{accountId}</if>" +
            "<if test='accountId == null and accountIds != null'>" +
            "<choose><when test='accountIds.isEmpty()'>AND 1 = 0</when>" +
            "<otherwise>AND account_id IN " +
            "<foreach collection='accountIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</otherwise></choose></if>" +
            "</where>ORDER BY id DESC LIMIT #{limit}</script>")
    List<XianyuPublishHistory> selectByAccountScope(@Param("accountId") Long accountId,
                                                    @Param("accountIds") List<Long> accountIds,
                                                    @Param("limit") int limit);
}
