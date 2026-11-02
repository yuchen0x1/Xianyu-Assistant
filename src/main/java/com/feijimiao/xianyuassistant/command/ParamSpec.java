package com.feijimiao.xianyuassistant.command;

/**
 * 命令参数规格：名称、类型（string/number/boolean/list）、是否必填、说明。
 */
public record ParamSpec(
        String name,
        String type,
        boolean required,
        String description
) {
}
