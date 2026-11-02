package com.feijimiao.xianyuassistant.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户-账号可见范围（P0-1 账号数据隔离）。
 */
@Data
@TableName("sys_user_account_scope")
public class SysUserAccountScope {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long accountId;
    private LocalDateTime createdTime;
}
