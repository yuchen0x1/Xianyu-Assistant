package com.feijimiao.xianyuassistant.command;

import java.util.Map;

/**
 * 结构化运营命令抽象。
 * 实现类通过 {@link #descriptor()} 暴露元数据，通过 {@link #execute(Map)} 执行并返回结构化结果。
 */
public interface XianyuCommand {

    /**
     * 命令描述（名称、说明、参数 schema）
     */
    CommandDescriptor descriptor();

    /**
     * 执行命令。
     *
     * @param args 命令参数（key 为参数名）
     * @return 结构化执行结果
     * @throws CommandExecutionException 执行失败时抛出，携带用户可读消息
     */
    Map<String, Object> execute(Map<String, Object> args);
}
