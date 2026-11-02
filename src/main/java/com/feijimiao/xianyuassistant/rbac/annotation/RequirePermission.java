package com.feijimiao.xianyuassistant.rbac.annotation;

import java.lang.annotation.*;

/**
 * 权限校验注解，标注在 Controller 方法上。
 * 拥有指定权限编码的用户才能访问该接口。
 * 多个 value 之间是 OR 关系（满足其一即可）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {
    String[] value();
}
