package com.feijimiao.xianyuassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 闲鱼账号实体类
 */
@Data
@TableName("xianyu_account")
public class XianyuAccount {
    
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 闲鱼账号备注
     */
    private String accountNote;
    
    /**
     * UNB标识
     */
    private String unb;
    
    /**
     * 设备ID（UUID格式-用户ID，用于WebSocket连接）
     * 格式: XXXXXXXX-XXXX-4XXX-XXXX-XXXXXXXXXXXX-用户ID
     * 例如: ED4CBA2C-5DA0-4154-A902-BF5CB52409E2-3888777108
     */
    private String deviceId;
    
    /**
     * 账号状态 1:正常，-1:需要手机号验证，-2:需要滑块验证
     */
    private Integer status;

    /**
     * WebSocket自动连接开关 1:允许后台保持连接，0:用户明确停用
     */
    private Integer websocketEnabled;

    /**
     * 闲鱼昵称（扫码后从个人主页接口获取，可能为空）
     */
    private String nickname;

    /**
     * 闲鱼头像URL（扫码后从个人主页接口获取，可能为空）
     */
    private String avatarUrl;

    /**
     * 闲鱼信用分（可能为空）
     */
    private Integer creditScore;

    /**
     * 卖出件数（来自个人主页数据，可能为空）
     */
    private Integer soldCount;

    /**
     * 粉丝数（可能为空）
     */
    private Integer followers;

    /**
     * 关注数（可能为空）
     */
    private Integer following;

    /**
     * 创建时间
     */
    private LocalDateTime createdTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedTime;
}
