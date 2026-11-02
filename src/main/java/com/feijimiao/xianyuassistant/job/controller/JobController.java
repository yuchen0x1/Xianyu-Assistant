package com.feijimiao.xianyuassistant.job.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.job.entity.JobDeadLetter;
import com.feijimiao.xianyuassistant.job.entity.JobDefinition;
import com.feijimiao.xianyuassistant.job.entity.JobExecution;
import com.feijimiao.xianyuassistant.job.service.JobEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@RequirePermission("jobs:read")
public class JobController {

    private final JobEngine jobEngine;

    /** 查询任务列表 */
    @GetMapping("/list")
    public ResultObject<List<JobDefinition>> listJobs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String jobType,
            @RequestParam(defaultValue = "50") int limit) {
        return ResultObject.success(jobEngine.listJobs(status, jobType, limit));
    }

    /** 查询任务的执行历史 */
    @GetMapping("/{jobId}/executions")
    public ResultObject<List<JobExecution>> getExecutions(
            @PathVariable Long jobId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResultObject.success(jobEngine.getJobExecutions(jobId, limit));
    }

    /** 查询死信列表 */
    @GetMapping("/dead-letters")
    public ResultObject<List<JobDeadLetter>> listDeadLetters(@RequestParam(defaultValue = "50") int limit) {
        return ResultObject.success(jobEngine.listDeadLetters(limit));
    }

    /** 处理死信（标记已解决） */
    @PostMapping("/dead-letters/{id}/resolve")
    @RequirePermission("jobs:admin")
    public ResultObject<Void> resolveDeadLetter(@PathVariable Long id, @RequestBody Map<String, String> body) {
        jobEngine.resolveDeadLetter(id, body.getOrDefault("note", ""), UserContext.getUserId());
        return ResultObject.success(null);
    }

    /** 重试死信（恢复为 PENDING 任务） */
    @PostMapping("/dead-letters/{id}/retry")
    @RequirePermission("jobs:admin")
    public ResultObject<Void> retryDeadLetter(@PathVariable Long id) {
        jobEngine.retryDeadLetter(id, UserContext.getUserId());
        return ResultObject.success(null);
    }
}
