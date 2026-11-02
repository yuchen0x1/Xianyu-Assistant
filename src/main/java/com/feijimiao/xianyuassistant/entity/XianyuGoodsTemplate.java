package com.feijimiao.xianyuassistant.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 商品发布模板实体（卡片F3：模板CRUD + CSV批量导入上架）
 */
@Data
public class XianyuGoodsTemplate {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 模板标题
     */
    private String title;

    /**
     * 模板描述
     */
    private String description;

    /**
     * 价格（数字或占位符，如 9.9 / {价格}）
     */
    private String pricePlaceholder;

    /**
     * 图片URL列表（逗号或分号分隔）
     */
    private String imageUrls;

    /**
     * 类目ID（可选）
     */
    private String categoryId;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}