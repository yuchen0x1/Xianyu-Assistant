package com.feijimiao.xianyuassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.feijimiao.xianyuassistant.security.EncryptedStringTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 闲鱼Cookie实体类
 */
@Data
/**
 * 开启自动结果映射，确保查询敏感字段时执行 EncryptedStringTypeHandler 解密。
 */
@TableName(value = "xianyu_cookie", autoResultMap = true)
public class XianyuCookie {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long xianyuAccountId;
    
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    private String cookieText;
    
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    private String mH5Tk;
    
    private Integer cookieStatus;
    
    private LocalDateTime expireTime;
    
    private LocalDateTime createdTime;
    
    private LocalDateTime updatedTime;
    
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    private String websocketToken;
    
    private Long tokenExpireTime;
}
