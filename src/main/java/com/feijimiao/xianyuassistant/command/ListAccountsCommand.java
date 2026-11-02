package com.feijimiao.xianyuassistant.command;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 查询账号列表命令。
 * 仅返回简要信息（id、accountNote、unb、status），不包含任何 Cookie。
 */
@Component
@RequiredArgsConstructor
public class ListAccountsCommand implements XianyuCommand {

    private final XianyuAccountMapper xianyuAccountMapper;
    private final RbacService rbacService;

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "list_accounts",
                "查询账号列表（简要信息，不含 Cookie）",
                List.of(),
                false
        );
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        List<Long> visibleAccountIds = rbacService.getCommandVisibleAccountIds(
                com.feijimiao.xianyuassistant.context.UserContext.getUserId());
        LambdaQueryWrapper<XianyuAccount> query = new LambdaQueryWrapper<XianyuAccount>()
                .orderByAsc(XianyuAccount::getId);
        if (visibleAccountIds != null) {
            if (visibleAccountIds.isEmpty()) {
                return response(List.of());
            }
            query.in(XianyuAccount::getId, visibleAccountIds);
        }
        List<XianyuAccount> accounts = xianyuAccountMapper.selectList(query);
        return response(accounts);
    }

    private Map<String, Object> response(List<XianyuAccount> accounts) {
        List<Map<String, Object>> brief = accounts.stream().map(account -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", account.getId());
            item.put("accountNote", account.getAccountNote());
            item.put("unb", account.getUnb());
            item.put("status", account.getStatus());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("accounts", brief);
        resp.put("total", brief.size());
        return resp;
    }
}
