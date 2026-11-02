package com.feijimiao.xianyuassistant.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 角色-权限关联
 */
@Data
@TableName("sys_role_permission")
public class SysRolePermission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roleId;
    private Long permissionId;
    private LocalDateTime createdTime;
}
