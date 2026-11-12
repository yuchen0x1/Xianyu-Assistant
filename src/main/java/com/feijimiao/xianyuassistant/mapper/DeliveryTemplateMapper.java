package com.feijimiao.xianyuassistant.mapper;

import com.feijimiao.xianyuassistant.entity.DeliveryTemplate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 有序多段自动发货模板持久化。 */
@Mapper
public interface DeliveryTemplateMapper {

    @Insert("INSERT INTO xianyu_delivery_template " +
            "(xianyu_account_id, name, enabled) VALUES (#{xianyuAccountId}, #{name}, #{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DeliveryTemplate template);

    @Update("UPDATE xianyu_delivery_template SET name = #{name}, enabled = #{enabled}, " +
            "update_time = datetime('now', 'localtime') WHERE id = #{id} AND xianyu_account_id = #{xianyuAccountId}")
    int update(DeliveryTemplate template);

    @Delete("DELETE FROM xianyu_delivery_template WHERE id = #{id} AND xianyu_account_id = #{xianyuAccountId}")
    int deleteByIdAndAccount(@Param("id") Long id, @Param("xianyuAccountId") Long xianyuAccountId);

    @Select("SELECT id, xianyu_account_id AS xianyuAccountId, name, enabled, " +
            "create_time AS createTime, update_time AS updateTime " +
            "FROM xianyu_delivery_template WHERE id = #{id} AND xianyu_account_id = #{xianyuAccountId}")
    DeliveryTemplate selectByIdAndAccount(@Param("id") Long id, @Param("xianyuAccountId") Long xianyuAccountId);

    @Select("SELECT id, xianyu_account_id AS xianyuAccountId, name, enabled, " +
            "create_time AS createTime, update_time AS updateTime " +
            "FROM xianyu_delivery_template WHERE xianyu_account_id = #{xianyuAccountId} " +
            "ORDER BY enabled DESC, update_time DESC, id DESC")
    List<DeliveryTemplate> selectByAccount(@Param("xianyuAccountId") Long xianyuAccountId);

    @Select("SELECT COUNT(*) FROM xianyu_goods_auto_delivery_config " +
            "WHERE xianyu_account_id = #{xianyuAccountId} AND delivery_template_id = #{templateId}")
    int countConfigReferences(@Param("xianyuAccountId") Long xianyuAccountId,
                              @Param("templateId") Long templateId);
}
