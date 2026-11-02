package com.feijimiao.xianyuassistant.command;

import com.feijimiao.xianyuassistant.service.EnhancedMessageSendService;
import com.feijimiao.xianyuassistant.service.EnhancedMessageSendService.MessageSendResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 发送聊天消息命令。
 */
@Component
@RequiredArgsConstructor
public class SendMessageCommand implements XianyuCommand {

    private final EnhancedMessageSendService enhancedMessageSendService;

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "send_message",
                "发送聊天消息（向买家发送一条消息）",
                List.of(
                        new ParamSpec("accountId", "number", true, "闲鱼账号ID"),
                        new ParamSpec("chatId", "string", true, "会话ID"),
                        new ParamSpec("toUserId", "string", true, "接收方用户ID"),
                        new ParamSpec("message", "string", true, "消息内容")
                ),
                true
        );
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        Long accountId = CommandArgs.getLong(args, "accountId", true);
        String chatId = CommandArgs.getString(args, "chatId", true);
        String toUserId = CommandArgs.getString(args, "toUserId", true);
        String message = CommandArgs.getString(args, "message", true);

        MessageSendResult result = enhancedMessageSendService.sendMessageWithRetry(accountId, chatId, toUserId, message);
        String readable = switch (result) {
            case SUCCESS -> "发送成功";
            case FAILED -> "发送失败";
            case NOT_CONNECTED -> "WebSocket 未连接，消息未发送";
            case RETRY_EXHAUSTED -> "重试次数已耗尽，消息未发送";
        };
        if (result != MessageSendResult.SUCCESS) {
            throw new CommandExecutionException("发送消息失败: " + readable);
        }
        return Map.of(
                "success", true,
                "message", readable,
                "result", result.name()
        );
    }
}
