package com.feijimiao.xianyuassistant.command;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 命令执行 HTTP 端点。
 */
@Slf4j
@RestController
@RequestMapping("/api/commands")
@RequiredArgsConstructor
@RequirePermission("commands:read")
public class CommandController {

    private final CommandRegistry commandRegistry;

    /**
     * 命令列表（含参数 schema）
     */
    @PostMapping("/list")
    public ResultObject<List<CommandDescriptor>> list() {
        return ResultObject.success(commandRegistry.list());
    }

    /**
     * 执行命令
     */
    @PostMapping("/execute")
    @RequirePermission("commands:execute")
    public ResultObject<Map<String, Object>> execute(@Valid @RequestBody CommandExecuteRequest req) {
        try {
            Map<String, Object> result = commandRegistry.execute(req.getName(), req.getArgs());
            return ResultObject.success(result);
        } catch (CommandExecutionException e) {
            log.warn("命令执行失败: name={}, msg={}", req.getName(), e.getMessage());
            return ResultObject.failed(e.getMessage());
        }
    }
}
