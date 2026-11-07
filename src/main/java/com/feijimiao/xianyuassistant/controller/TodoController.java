package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.TodoRespDTO;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.service.TodoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Todo center controller.
 *
 * <p>Aggregates seller daily todos: pending shipment, pending reply,
 * pending review and pending evaluation, scoped by current user RBAC.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/todo")
@RequirePermission("dashboard:read")
public class TodoController {

    @Autowired
    private TodoService todoService;

    /**
     * Get aggregated todo overview.
     *
     * @param limit max items per category, default 20
     */
    @GetMapping("/overview")
    public ResultObject<TodoRespDTO> overview(@RequestParam(defaultValue = "20") int limit) {
        try {
            log.info("Get todo overview");
            return ResultObject.success(todoService.overview(limit));
        } catch (Exception e) {
            log.error("Get todo overview failed", e);
            return ResultObject.failed("Get todo overview failed");
        }
    }
}