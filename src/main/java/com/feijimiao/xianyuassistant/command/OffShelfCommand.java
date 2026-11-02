package com.feijimiao.xianyuassistant.command;

import com.feijimiao.xianyuassistant.service.PublishService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 下架/删除商品命令。
 */
@Component
@RequiredArgsConstructor
public class OffShelfCommand implements XianyuCommand {

    private final PublishService publishService;

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "off_shelf",
                "下架/删除商品",
                List.of(
                        new ParamSpec("accountId", "number", true, "闲鱼账号ID"),
                        new ParamSpec("itemId", "string", true, "商品ID")
                ),
                true
        );
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        Long accountId = CommandArgs.getLong(args, "accountId", true);
        String itemId = CommandArgs.getString(args, "itemId", true);

        boolean success = publishService.offShelf(accountId, itemId);
        if (!success) {
            throw new CommandExecutionException("下架/删除商品失败: itemId=" + itemId);
        }
        return Map.of(
                "success", true,
                "message", "下架/删除成功",
                "itemId", itemId
        );
    }
}
