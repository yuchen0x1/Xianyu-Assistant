package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.ScheduleTaskReqDTO;
import com.feijimiao.xianyuassistant.entity.XianyuScheduleTask;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.service.ScheduleTaskService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Cron 计划任务管理接口。 */
@RestController
@RequestMapping("/api/schedule-tasks")
@RequirePermission("jobs:read")
public class ScheduleTaskController {

    @Autowired
    private ScheduleTaskService scheduleTaskService;

    @GetMapping("/list")
    public ResultObject<List<XianyuScheduleTask>> list() {
        return ResultObject.success(scheduleTaskService.list());
    }

    @PostMapping
    @RequirePermission("jobs:admin")
    public ResultObject<XianyuScheduleTask> create(@Valid @RequestBody ScheduleTaskReqDTO req) {
        return ResultObject.success(scheduleTaskService.create(req));
    }

    @PutMapping("/{id}")
    @RequirePermission("jobs:admin")
    public ResultObject<XianyuScheduleTask> update(@PathVariable Long id,
                                                   @Valid @RequestBody ScheduleTaskReqDTO req) {
        return ResultObject.success(scheduleTaskService.update(id, req));
    }

    @PostMapping("/{id}/toggle")
    @RequirePermission("jobs:admin")
    public ResultObject<XianyuScheduleTask> toggle(@PathVariable Long id,
                                                   @RequestParam boolean enabled) {
        return ResultObject.success(scheduleTaskService.toggle(id, enabled));
    }

    @PostMapping("/{id}/trigger")
    @RequirePermission("jobs:admin")
    public ResultObject<XianyuScheduleTask> trigger(@PathVariable Long id) {
        return ResultObject.success(scheduleTaskService.trigger(id));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("jobs:admin")
    public ResultObject<Boolean> delete(@PathVariable Long id) {
        return ResultObject.success(scheduleTaskService.delete(id));
    }
}
