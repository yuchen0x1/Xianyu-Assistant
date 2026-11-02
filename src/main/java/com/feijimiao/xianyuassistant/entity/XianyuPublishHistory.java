package com.feijimiao.xianyuassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品发布/下架历史记录
 */
@Data
@TableName("xianyu_publish_history")
public class XianyuPublishHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发布账号ID */
    private Long accountId;

    /** 账号备注快照 */
    private String accountNote;

    /** 商品标题 */
    private String title;

    /** 商品描述 */
    private String description;

    /** 商品价格 */
    private BigDecimal price;

    /** 库存数量 */
    private Integer quantity;

    /** 图片数量 */
    private Integer imageCount;

    /** 类目ID（AI 识别结果） */
    private String categoryId;

    /** 发布成功后返回的商品ID */
    private String itemId;

    /** 操作类型 PUBLISH / OFF_SHELF */
    private String operationType;

    /** 状态 SUCCESS / FAILED */
    private String status;

    /** 失败原因 */
    private String errorMessage;

    /** 平台原始响应（脱敏） */
    private String rawResponse;

    /** 创建时间 */
    private LocalDateTime createdTime;
}
