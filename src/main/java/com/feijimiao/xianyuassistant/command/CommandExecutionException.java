package com.feijimiao.xianyuassistant.command;

/**
 * 命令执行异常，携带用户可读消息。
 */
public class CommandExecutionException extends RuntimeException {

    public CommandExecutionException(String message) {
        super(message);
    }

    public CommandExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
