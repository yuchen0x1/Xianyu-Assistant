package com.feijimiao.xianyuassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 闲鱼商品信息Mapper
 */
@Mapper
public interface XianyuGoodsInfoMapper extends BaseMapper<XianyuGoodsInfo> {

    @Select("<script>SELECT COUNT(*) FROM xianyu_goods " +
            "<where>" +
            "<if test='status != null'>AND status = #{status}</if>" +
            "<if test='accountId != null'>AND xianyu_account_id = #{accountId}</if>" +
            "<if test='accountIds != null'>" +
            "<choose><when test='accountIds.isEmpty()'>AND 1 = 0</when>" +
            "<otherwise>AND xianyu_account_id IN " +
            "<foreach collection='accountIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</otherwise></choose></if>" +
            "</where></script>")
    int countByAccountScope(@Param("status") Integer status,
                            @Param("accountId") Long accountId,
                            @Param("accountIds") List<Long> accountIds);

    @Select("<script>SELECT * FROM xianyu_goods " +
            "<where>" +
            "<if test='status != null'>AND status = #{status}</if>" +
            "<if test='accountId != null'>AND xianyu_account_id = #{accountId}</if>" +
            "<if test='accountIds != null'>" +
            "<choose><when test='accountIds.isEmpty()'>AND 1 = 0</when>" +
            "<otherwise>AND xianyu_account_id IN " +
            "<foreach collection='accountIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</otherwise></choose></if>" +
            "</where>ORDER BY updated_time DESC LIMIT #{offset}, #{pageSize}</script>")
    List<XianyuGoodsInfo> selectByAccountScope(@Param("status") Integer status,
                                               @Param("accountId") Long accountId,
                                               @Param("accountIds") List<Long> accountIds,
                                               @Param("offset") int offset,
                                               @Param("pageSize") int pageSize);
}
