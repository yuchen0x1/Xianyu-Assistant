package com.feijimiao.xianyuassistant.mapper;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsTemplate;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 商品发布模板Mapper
 */
@Mapper
public interface XianyuGoodsTemplateMapper {

    @Insert("INSERT INTO xianyu_goods_template (title, description, price_placeholder, image_urls, category_id, remark) " +
            "VALUES (#{title}, #{description}, #{pricePlaceholder}, #{imageUrls}, #{categoryId}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(XianyuGoodsTemplate template);

    @Update("UPDATE xianyu_goods_template SET title = #{title}, description = #{description}, " +
            "price_placeholder = #{pricePlaceholder}, image_urls = #{imageUrls}, " +
            "category_id = #{categoryId}, remark = #{remark}, " +
            "update_time = datetime('now', 'localtime') WHERE id = #{id}")
    int update(XianyuGoodsTemplate template);

    @Delete("DELETE FROM xianyu_goods_template WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Select("SELECT * FROM xianyu_goods_template WHERE id = #{id}")
    XianyuGoodsTemplate selectById(@Param("id") Long id);

    @Select("SELECT * FROM xianyu_goods_template ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<XianyuGoodsTemplate> selectPage(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM xianyu_goods_template")
    int countAll();

}