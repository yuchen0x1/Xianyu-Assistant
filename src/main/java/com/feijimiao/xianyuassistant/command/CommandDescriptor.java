package com.feijimiao.xianyuassistant.command;

import java.util.List;

/**
 * 命令描述信息：命令名称、说明、参数 schema、是否需要账号。
 */
public record CommandDescriptor(
        String name,
        String description,
        List<ParamSpec> params,
        boolean needAccount
) {
}
