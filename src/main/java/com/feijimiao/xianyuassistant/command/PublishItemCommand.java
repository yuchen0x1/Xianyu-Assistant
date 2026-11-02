package com.feijimiao.xianyuassistant.command;

import com.feijimiao.xianyuassistant.controller.dto.PublishItemReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.PublishItemRespDTO;
import com.feijimiao.xianyuassistant.service.PublishService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 发布商品命令。
 */
@Component
@RequiredArgsConstructor
public class PublishItemCommand implements XianyuCommand {

    private final PublishService publishService;

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "publish_item",
                "发布商品（到闲鱼）",
                List.of(
                        new ParamSpec("accountId", "number", true, "闲鱼账号ID"),
                        new ParamSpec("title", "string", true, "商品标题"),
                        new ParamSpec("description", "string", false, "商品描述"),
                        new ParamSpec("price", "number", true, "商品价格（元）"),
                        new ParamSpec("quantity", "number", false, "库存数量，默认 1"),
                        new ParamSpec("images", "list", false, "图片 CDN URL 列表"),
                        new ParamSpec("categoryId", "string", false, "类目ID，不传时自动识别推荐")
                ),
                true
        );
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        PublishItemReqDTO req = new PublishItemReqDTO();
        req.setAccountId(CommandArgs.getLong(args, "accountId", true));
        req.setTitle(CommandArgs.getString(args, "title", true));
        req.setDescription(CommandArgs.getString(args, "description", false));
        req.setPrice(CommandArgs.getBigDecimal(args, "price", true));
        req.setQuantity(CommandArgs.getInteger(args, "quantity", false));
        req.setImages(CommandArgs.getStringList(args, "images", false));
        req.setCategoryId(CommandArgs.getString(args, "categoryId", false));

        PublishItemRespDTO resp = publishService.publish(req);
        if (resp == null || !resp.isSuccess()) {
            String message = resp == null ? "发布失败（无返回结果）" : resp.getMessage();
            throw new CommandExecutionException("发布商品失败: " + message);
        }
        return Map.of(
                "success", true,
                "message", resp.getMessage() == null ? "发布成功" : resp.getMessage(),
                "data", resp
        );
    }
}
