package com.feijimiao.xianyuassistant.command;

import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 命令注册表：维护命令名称到实现的映射。
 */
@Slf4j
@Component
public class CommandRegistry {

    private final Map<String, XianyuCommand> commandMap;
    private final RbacService rbacService;

    public CommandRegistry(List<XianyuCommand> commands, RbacService rbacService) {
        this.rbacService = rbacService;
        this.commandMap = commands.stream()
                .collect(Collectors.toMap(
                        cmd -> cmd.descriptor().name(),
                        Function.identity(),
                        (a, b) -> {
                            String name = a.descriptor().name();
                            throw new IllegalStateException("检测到重复的命令名称: " + name);
                        },
                        LinkedHashMap::new
                ));
        log.info("命令注册表初始化完成，共 {} 个命令: {}", commandMap.size(), commandMap.keySet());
    }

    /**
     * 全部命令描述列表
     */
    public List<CommandDescriptor> list() {
        return commandMap.values().stream()
                .map(XianyuCommand::descriptor)
                .collect(Collectors.toList());
    }

    /**
     * 按名称获取命令，不存在时抛出异常
     */
    public XianyuCommand get(String name) {
        XianyuCommand command = commandMap.get(name);
        if (command == null) {
            throw new CommandExecutionException("未知命令: " + name + "，可用命令: " + commandMap.keySet());
        }
        return command;
    }

    /**
     * 执行命令。空参数自动补 {}，并剔除值为 null 的参数。
     */
    public Map<String, Object> execute(String name, Map<String, Object> args) {
        XianyuCommand command = get(name);
        Map<String, Object> safeArgs = args == null ? new HashMap<>() : new HashMap<>(args);
        safeArgs.entrySet().removeIf(entry -> entry.getValue() == null);
        enforceAccountScope(command, safeArgs);
        return command.execute(safeArgs);
    }

    private void enforceAccountScope(XianyuCommand command, Map<String, Object> args) {
        if (!command.descriptor().needAccount() && !args.containsKey("accountId")) {
            return;
        }
        Long userId = UserContext.getUserId();
        List<Long> visibleAccountIds = rbacService.getCommandVisibleAccountIds(userId);
        Object rawAccountId = args.get("accountId");
        if (rawAccountId == null || rawAccountId.toString().isBlank()) {
            if (visibleAccountIds != null) {
                throw new CommandExecutionException("非管理员必须指定 accountId");
            }
            return;
        }
        Long accountId;
        try {
            accountId = rawAccountId instanceof Number number
                    ? number.longValue()
                    : Long.valueOf(rawAccountId.toString().trim());
        } catch (NumberFormatException e) {
            throw new CommandExecutionException("参数 accountId 不是合法的数字: " + rawAccountId);
        }
        if (visibleAccountIds != null && !visibleAccountIds.contains(accountId)) {
            throw new CommandExecutionException("无权访问该账号");
        }
    }
}
