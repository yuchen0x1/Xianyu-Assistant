package com.feijimiao.xianyuassistant.command;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 命令执行请求。
 */
@Data
public class CommandExecuteRequest {

    @NotBlank(message = "命令名称不能为空")
    private String name;

    /** 命令参数，可为空 */
    private Map<String, Object> args;
}
