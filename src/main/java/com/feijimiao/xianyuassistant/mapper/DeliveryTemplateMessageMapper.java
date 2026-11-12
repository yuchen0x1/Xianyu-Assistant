package com.feijimiao.xianyuassistant.mapper;

import com.feijimiao.xianyuassistant.entity.DeliveryTemplateMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 有序多段自动发货模板消息持久化。 */
@Mapper
public interface DeliveryTemplateMessageMapper {

    @Insert("INSERT INTO xianyu_delivery_template_message " +
            "(template_id, sort_order, message_type, content) " +
            "VALUES (#{templateId}, #{sortOrder}, #{messageType}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DeliveryTemplateMessage message);

    @Delete("DELETE FROM xianyu_delivery_template_message WHERE template_id = #{templateId}")
    int deleteByTemplateId(@Param("templateId") Long templateId);

    @Select("SELECT id, template_id AS templateId, sort_order AS sortOrder, " +
            "message_type AS messageType, content, create_time AS createTime, update_time AS updateTime " +
            "FROM xianyu_delivery_template_message WHERE template_id = #{templateId} " +
            "ORDER BY sort_order ASC, id ASC")
    List<DeliveryTemplateMessage> selectByTemplateId(@Param("templateId") Long templateId);
}
