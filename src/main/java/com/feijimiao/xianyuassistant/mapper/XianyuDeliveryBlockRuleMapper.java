package com.feijimiao.xianyuassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feijimiao.xianyuassistant.entity.XianyuDeliveryBlockRule;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface XianyuDeliveryBlockRuleMapper extends BaseMapper<XianyuDeliveryBlockRule> {

    @Select("SELECT * FROM xianyu_delivery_block_rule WHERE xianyu_account_id = #{accountId} ORDER BY priority ASC, id ASC")
    List<XianyuDeliveryBlockRule> selectByAccountId(@Param("accountId") Long accountId);

    @Delete("DELETE FROM xianyu_delivery_block_rule WHERE xianyu_account_id = #{accountId}")
    int deleteByAccountId(@Param("accountId") Long accountId);
}
