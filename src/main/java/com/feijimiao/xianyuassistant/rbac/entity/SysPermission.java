package com.feijimiao.xianyuassistant.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统权限
 */
@Data
@TableName("sys_permission")
public class SysPermission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String module;
    private String action;       // read / write / admin / execute
    private String description;
    private Integer status;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
