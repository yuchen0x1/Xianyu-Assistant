package com.feijimiao.xianyuassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品价格历史快照
 */
@Data
@TableName("xianyu_goods_price_history")
public class XianyuGoodsPriceHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 闲鱼商品ID */
    private String xyGoodId;

    /** 关联账号 */
    private Long xianyuAccountId;

    /** 快照价格 */
    private BigDecimal price;

    /** 快照时间 */
    private LocalDateTime snapshotTime;
}
