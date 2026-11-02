package com.feijimiao.xianyuassistant.command;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.ItemListFromDbReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.ItemListFromDbRespDTO;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.service.ItemService;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 查询商品列表命令。
 */
@Component
@RequiredArgsConstructor
public class ListGoodsCommand implements XianyuCommand {

    private final ItemService itemService;
    private final RbacService rbacService;

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "list_goods",
                "查询商品列表（从本地数据库）",
                List.of(
                        new ParamSpec("accountId", "number", false, "闲鱼账号ID（可选，查询全部账号）"),
                        new ParamSpec("keyword", "string", false, "标题关键字过滤"),
                        new ParamSpec("page", "number", false, "页码，从 1 开始，默认 1"),
                        new ParamSpec("pageSize", "number", false, "每页数量，默认 20")
                ),
                false
        );
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        List<Long> visibleAccountIds = rbacService.getCommandVisibleAccountIds(UserContext.getUserId());
        Long requestedAccountId = CommandArgs.getLong(args, "accountId", false);
        if (visibleAccountIds != null && requestedAccountId == null) {
            throw new CommandExecutionException("非管理员必须指定 accountId");
        }
        if (visibleAccountIds != null && !visibleAccountIds.contains(requestedAccountId)) {
            throw new CommandExecutionException("无权访问该账号");
        }

        ItemListFromDbReqDTO req = new ItemListFromDbReqDTO();
        req.setXianyuAccountId(requestedAccountId);
        req.setPageNum(CommandArgs.getInteger(args, "page", false));
        req.setPageSize(CommandArgs.getInteger(args, "pageSize", false));
        req.setOnlyOnSale(false);
        String keyword = CommandArgs.getString(args, "keyword", false);

        ResultObject<ItemListFromDbRespDTO> result = itemService.getItemsFromDb(req);
        if (result.getCode() == null || result.getCode() != 200) {
            throw new CommandExecutionException("查询商品失败: " + result.getMsg());
        }
        ItemListFromDbRespDTO data = result.getData();
        if (data == null) {
            data = new ItemListFromDbRespDTO();
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        List<XianyuGoodsInfo> items = data.getItems() == null ? List.of() : data.getItems();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            items = items.stream()
                    .filter(item -> item.getTitle() != null && item.getTitle().toLowerCase().contains(kw))
                    .collect(Collectors.toList());
        }
        if (visibleAccountIds != null) {
            items = items.stream()
                    .filter(item -> item.getXianyuAccountId() != null
                            && visibleAccountIds.contains(item.getXianyuAccountId()))
                    .collect(Collectors.toList());
        }
        resp.put("items", items);
        resp.put("totalCount", data.getTotalCount());
        resp.put("pageNum", data.getPageNum());
        resp.put("pageSize", data.getPageSize());
        resp.put("totalPage", data.getTotalPage());
        resp.put("success", true);
        return resp;
    }
}
