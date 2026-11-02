package com.feijimiao.xianyuassistant.command;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.AllItemsReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.RefreshItemsRespDTO;
import com.feijimiao.xianyuassistant.service.ItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 同步商品列表命令。
 */
@Component
@RequiredArgsConstructor
public class SyncGoodsCommand implements XianyuCommand {

    private final ItemService itemService;

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "sync_goods",
                "同步商品列表（从闲鱼刷新商品到本地）",
                List.of(
                        new ParamSpec("accountId", "number", true, "闲鱼账号ID"),
                        new ParamSpec("pageSize", "number", false, "每页数量，默认 20"),
                        new ParamSpec("maxPages", "number", false, "最大页数限制，不传表示不限制")
                ),
                true
        );
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        AllItemsReqDTO req = new AllItemsReqDTO();
        req.setXianyuAccountId(CommandArgs.getLong(args, "accountId", true));
        req.setPageSize(CommandArgs.getInteger(args, "pageSize", false));
        req.setMaxPages(CommandArgs.getInteger(args, "maxPages", false));

        ResultObject<RefreshItemsRespDTO> result = itemService.refreshItems(req);
        if (result.getCode() == null || result.getCode() != 200) {
            throw new CommandExecutionException("同步商品失败: " + result.getMsg());
        }
        RefreshItemsRespDTO data = result.getData();
        if (data == null || !Boolean.TRUE.equals(data.getSuccess())) {
            String message = data == null ? "无返回结果" : data.getMessage();
            throw new CommandExecutionException("同步商品失败: " + message);
        }
        return Map.of(
                "success", true,
                "message", data.getMessage() == null ? "同步完成" : data.getMessage(),
                "data", data
        );
    }
}
