package com.feijimiao.xianyuassistant.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统角色
 */
@Data
@TableName("sys_role")
public class SysRole {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String roleType;     // SYSTEM / CUSTOM
    private Integer status;      // 1:启用 0:禁用
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
