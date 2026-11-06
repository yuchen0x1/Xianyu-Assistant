package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.AllItemsReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.PublishItemReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.PublishItemRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.ScheduleTaskReqDTO;
import com.feijimiao.xianyuassistant.entity.XianyuScheduleTask;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.mapper.XianyuScheduleTaskMapper;
import com.feijimiao.xianyuassistant.service.ItemService;
import com.feijimiao.xianyuassistant.service.PublishService;
import com.feijimiao.xianyuassistant.service.ScheduleTaskService;
import com.feijimiao.xianyuassistant.utils.XianyuAuthFailureGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 定时任务服务实现。
 *
 * <p>基于 Spring {@link ThreadPoolTaskScheduler} + {@link CronTrigger} 动态调度，
 * 任务类型：SYNC_GOODS（定时同步商品）、PUBLISH（定时发布商品）、OFF_SHELF（定时下架商品）。
 * 连续失败达到阈值自动暂停（失败保护）；账号脱离认证/风控冷却后自动恢复调度（guard 恢复）。</p>
 */
@Slf4j
@Service
public class ScheduleTaskServiceImpl implements ScheduleTaskService {

    /** 连续失败自动暂停阈值 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** 失败任务自动恢复检查间隔（秒） */
    private static final long RECOVERY_CHECK_INTERVAL_SECONDS = 60L;

    /** 暂停后最小恢复间隔（秒）：避免非 guard 原因暂停的任务被频繁恢复形成失败循环 */
    private static final long RECOVERY_MIN_GAP_SECONDS = 300L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<Long, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    @Autowired
    private XianyuScheduleTaskMapper scheduleTaskMapper;

    @Autowired
    private ItemService itemService;

    @Autowired
    private PublishService publishService;

    /** 账号认证/风控失败保护，用于判断账号是否已脱离冷却（guard 恢复） */
    @Autowired
    private XianyuAuthFailureGuard authFailureGuard;

    @Autowired
    private RbacService rbacService;

    private ThreadPoolTaskScheduler taskScheduler;

    @PostConstruct
    public void initScheduler() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(4);
        taskScheduler.setThreadNamePrefix("schedule-task-");
        taskScheduler.setWaitForTasksToCompleteOnShutdown(false);
        taskScheduler.initialize();
        // 注册失败任务自动恢复检查（guard 恢复）：账号脱离冷却后自动重新调度被暂停的任务
        taskScheduler.scheduleAtFixedRate(this::recoverPausedTasksQuietly,
                Duration.ofSeconds(RECOVERY_CHECK_INTERVAL_SECONDS));
        log.info("定时任务调度器已初始化，失败恢复检查间隔={}秒", RECOVERY_CHECK_INTERVAL_SECONDS);
    }

    @PreDestroy
    public void shutdownScheduler() {
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
    }

    @Override
    public XianyuScheduleTask create(ScheduleTaskReqDTO req) {
        validateReq(req);
        assertVisible(req.getAccountId());
        XianyuScheduleTask task = new XianyuScheduleTask();
        applyReq(task, req);
        task.setEnabled(req.getEnabled() ? 1 : 0);
        task.setPaused(0);
        task.setFailCount(0);
        scheduleTaskMapper.insert(task);
        schedule(task);
        log.info("定时任务已创建: id={}, type={}, cron={}", task.getId(), task.getTaskType(), task.getCronExpr());
        return task;
    }

    @Override
    public XianyuScheduleTask update(Long id, ScheduleTaskReqDTO req) {
        XianyuScheduleTask task = requireTask(id);
        assertVisible(task.getAccountId());
        validateReq(req);
        assertVisible(req.getAccountId());
        cancel(task.getId());
        applyReq(task, req);
        task.setEnabled(req.getEnabled() ? 1 : 0);
        task.setPaused(0);
        task.setFailCount(0);
        task.setUpdatedTime(LocalDateTime.now());
        scheduleTaskMapper.updateById(task);
        if (task.getEnabled() == 1) {
            schedule(task);
        }
        log.info("定时任务已更新: id={}", id);
        return task;
    }

    @Override
    public XianyuScheduleTask toggle(Long id, boolean enabled) {
        XianyuScheduleTask task = requireTask(id);
        assertVisible(task.getAccountId());
        task.setEnabled(enabled ? 1 : 0);
        if (enabled) {
            task.setPaused(0);
            task.setFailCount(0);
        }
        task.setUpdatedTime(LocalDateTime.now());
        scheduleTaskMapper.updateById(task);
        if (enabled) {
            schedule(task);
        } else {
            cancel(task.getId());
        }
        return task;
    }

    @Override
    public boolean delete(Long id) {
        XianyuScheduleTask task = requireTask(id);
        assertVisible(task.getAccountId());
        cancel(task.getId());
        return scheduleTaskMapper.deleteById(id) > 0;
    }

    @Override
    public XianyuScheduleTask trigger(Long id) {
        XianyuScheduleTask task = requireTask(id);
        assertVisible(task.getAccountId());
        executeTask(task);
        return scheduleTaskMapper.selectById(id);
    }

    @Override
    public List<XianyuScheduleTask> list() {
        LambdaQueryWrapper<XianyuScheduleTask> wrapper = new LambdaQueryWrapper<>();
        Long userId = UserContext.getUserId();
        List<Long> visible = rbacService.getCommandVisibleAccountIds(userId);
        if (visible != null) {
            if (visible.isEmpty()) {
                return List.of();
            }
            wrapper.in(XianyuScheduleTask::getAccountId, visible);
        }
        wrapper.orderByDesc(XianyuScheduleTask::getId);
        return scheduleTaskMapper.selectList(wrapper);
    }

    @Override
    public void reloadAll() {
        if (taskScheduler == null) {
            initScheduler();
        }
        List<XianyuScheduleTask> enabledTasks = scheduleTaskMapper.selectList(
                new LambdaQueryWrapper<XianyuScheduleTask>().eq(XianyuScheduleTask::getEnabled, 1));
        for (XianyuScheduleTask task : enabledTasks) {
            try {
                schedule(task);
            } catch (Exception e) {
                log.warn("定时任务启动调度失败: id={}, reason={}", task.getId(), e.getMessage());
            }
        }
        log.info("定时任务已重载 {} 个启用任务", enabledTasks.size());
    }

    /**
     * guard 恢复：扫描因连续失败自动暂停但仍启用的任务，若关联账号已脱离认证/风控冷却，
     * 且暂停已超过最小恢复间隔，则自动恢复调度（重置失败计数）。
     *
     * <p>恢复条件（全部满足）：
     * <ul>
     *   <li>{@code enabled=1 且 paused=1}（自动暂停但仍期望运行）</li>
     *   <li>{@code accountId != null} 且 {@code !authFailureGuard.isCoolingDown(accountId)}（账号已恢复）</li>
     *   <li>距最近一次执行（失败）已超过 {@link #RECOVERY_MIN_GAP_SECONDS}（避免失败循环）</li>
     * </ul>
     * </p>
     */
    @Override
    public void recoverPausedTasks() {
        List<XianyuScheduleTask> pausedTasks = scheduleTaskMapper.selectList(
                new LambdaQueryWrapper<XianyuScheduleTask>()
                        .eq(XianyuScheduleTask::getEnabled, 1)
                        .eq(XianyuScheduleTask::getPaused, 1));
        if (pausedTasks.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int recovered = 0;
        for (XianyuScheduleTask task : pausedTasks) {
            Long accountId = task.getAccountId();
            if (accountId == null) {
                // 无关联账号，无法判断 guard 状态，跳过（保留人工恢复）
                continue;
            }
            if (authFailureGuard.isCoolingDown(accountId)) {
                // 账号仍在认证/风控冷却中，暂不恢复
                continue;
            }
            // 暂停后最小恢复间隔：避免非 guard 原因的暂停被频繁恢复形成失败循环
            if (task.getLastRunTime() != null
                    && java.time.Duration.between(task.getLastRunTime(), now).getSeconds() < RECOVERY_MIN_GAP_SECONDS) {
                continue;
            }
            task.setPaused(0);
            task.setFailCount(0);
            task.setUpdatedTime(now);
            scheduleTaskMapper.updateById(task);
            schedule(task);
            recovered++;
            log.info("定时任务 guard 恢复：账号{}已脱离冷却，重新调度任务 id={}", accountId, task.getId());
        }
        if (recovered > 0) {
            log.info("本次 guard 恢复共恢复 {} 个暂停任务", recovered);
        }
    }

    /** 定时恢复入口：异常不外抛，避免固定速率调度被中断。 */
    private void recoverPausedTasksQuietly() {
        try {
            recoverPausedTasks();
        } catch (Exception e) {
            log.warn("定时任务 guard 恢复检查异常: {}", e.getMessage());
        }
    }

    // ==================== 私有方法 ====================

    private void validateReq(ScheduleTaskReqDTO req) {
        try {
            CronExpression.parse(req.getCronExpr());
        } catch (Exception e) {
            throw new BusinessException("Cron表达式无效: " + req.getCronExpr());
        }
        if ("SYNC_GOODS".equals(req.getTaskType()) && req.getAccountId() == null) {
            throw new BusinessException("同步商品任务必须指定账号");
        }
        if ("OFF_SHELF".equals(req.getTaskType()) && req.getAccountId() == null) {
            throw new BusinessException("定时下架任务必须指定账号");
        }
        if ("PUBLISH".equals(req.getTaskType()) && req.getAccountId() == null) {
            throw new BusinessException("定时发布任务必须指定账号");
        }
        if ("POLISH_GOODS".equals(req.getTaskType()) && req.getAccountId() == null) {
            throw new BusinessException("定时擦亮任务必须指定账号");
        }
        if (!"SYNC_GOODS".equals(req.getTaskType()) && !"OFF_SHELF".equals(req.getTaskType())
                && !"PUBLISH".equals(req.getTaskType()) && !"POLISH_GOODS".equals(req.getTaskType())) {
            throw new BusinessException("不支持的任务类型: " + req.getTaskType());
        }
    }

    private void applyReq(XianyuScheduleTask task, ScheduleTaskReqDTO req) {
        task.setTaskName(req.getTaskName());
        task.setTaskType(req.getTaskType());
        task.setCronExpr(req.getCronExpr());
        task.setAccountId(req.getAccountId());
        task.setConfigJson(req.getConfigJson());
    }

    private XianyuScheduleTask requireTask(Long id) {
        XianyuScheduleTask task = scheduleTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException("定时任务不存在: " + id);
        }
        return task;
    }

    private void assertVisible(Long accountId) {
        rbacService.assertAccountVisible(UserContext.getUserId(), accountId);
    }

    private void schedule(XianyuScheduleTask task) {
        cancel(task.getId());
        if (task.getEnabled() != 1 || task.getPaused() == 1) {
            return;
        }
        try {
            CronTrigger trigger = new CronTrigger(task.getCronExpr());
            ScheduledFuture<?> future = taskScheduler.schedule(() -> executeTaskQuietly(task.getId()), trigger);
            scheduledFutures.put(task.getId(), future);
        } catch (Exception e) {
            log.error("定时任务调度注册失败: id={}", task.getId(), e);
        }
    }

    private void cancel(Long id) {
        ScheduledFuture<?> future = scheduledFutures.remove(id);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** 调度线程入口：异常不外抛，交由执行方法记录。 */
    private void executeTaskQuietly(Long taskId) {
        try {
            XianyuScheduleTask task = scheduleTaskMapper.selectById(taskId);
            if (task == null || task.getEnabled() != 1) {
                return;
            }
            executeTask(task);
        } catch (Exception e) {
            log.error("定时任务执行异常: taskId={}", taskId, e);
        }
    }

    /** 执行任务并按结果更新状态；连续失败达到阈值自动暂停。 */
    private void executeTask(XianyuScheduleTask task) {
        boolean success;
        String error = null;
        try {
            switch (task.getTaskType()) {
                case "SYNC_GOODS" -> success = executeSyncGoods(task);
                case "OFF_SHELF" -> success = executeOffShelf(task);
                case "PUBLISH" -> success = executePublish(task);
                case "POLISH_GOODS" -> success = executePolishGoods(task);
                default -> {
                    log.warn("未知任务类型: {}", task.getTaskType());
                    success = false;
                    error = "未知任务类型";
                }
            }
        } catch (Exception e) {
            success = false;
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("定时任务执行失败: id={}, type={}", task.getId(), task.getTaskType(), e);
        }

        task.setLastRunTime(LocalDateTime.now());
        task.setLastRunStatus(success ? "SUCCESS" : "FAILED");
        task.setLastError(success ? null : truncate(error, 500));
        if (success) {
            task.setFailCount(0);
        } else {
            task.setFailCount((task.getFailCount() == null ? 0 : task.getFailCount()) + 1);
            if (task.getFailCount() >= MAX_CONSECUTIVE_FAILURES) {
                task.setPaused(1);
                cancel(task.getId());
                log.warn("定时任务连续失败{}次，已自动暂停: id={}", MAX_CONSECUTIVE_FAILURES, task.getId());
            }
        }
        scheduleTaskMapper.updateById(task);
    }

    private boolean executeSyncGoods(XianyuScheduleTask task) {
        if (task.getAccountId() == null) {
            return false;
        }
        AllItemsReqDTO req = new AllItemsReqDTO();
        req.setXianyuAccountId(task.getAccountId());
        req.setPageSize(20);
        req.setMaxPages(5);
        ResultObject<?> result = itemService.refreshItems(req);
        return result != null && result.getCode() != null && result.getCode() == 200;
    }

    private boolean executeOffShelf(XianyuScheduleTask task) {
        if (task.getAccountId() == null) {
            return false;
        }
        List<String> itemIds = parseItemIds(task.getConfigJson());
        if (itemIds.isEmpty()) {
            log.warn("定时下架任务未配置商品ID列表: id={}", task.getId());
            return false;
        }
        boolean allSuccess = true;
        for (String itemId : itemIds) {
            try {
                boolean ok = publishService.offShelf(task.getAccountId(), itemId);
                if (!ok) {
                    allSuccess = false;
                }
            } catch (Exception e) {
                allSuccess = false;
                log.warn("定时下架单个商品失败: taskId={}, itemId={}, reason={}",
                        task.getId(), itemId, e.getMessage());
            }
        }
        return allSuccess;
    }

    private boolean executePublish(XianyuScheduleTask task) {
        if (task.getAccountId() == null || task.getConfigJson() == null || task.getConfigJson().isBlank()) {
            log.warn("定时发布任务未配置账号或商品内容: id={}", task.getId());
            return false;
        }
        try {
            PublishItemReqDTO req = objectMapper.readValue(task.getConfigJson(), PublishItemReqDTO.class);
            req.setAccountId(task.getAccountId());
            req.setRecommendCategoryOnly(false);
            PublishItemRespDTO response = publishService.publish(req);
            if (response == null) {
                throw new BusinessException("发布服务未返回结果");
            }
            if (!response.isSuccess()) {
                throw new BusinessException(response.getMessage() == null ? "商品发布失败" : response.getMessage());
            }
            return true;
        } catch (Exception e) {
            log.warn("定时发布任务执行失败: taskId={}, reason={}", task.getId(), e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseItemIds(String configJson) {
        List<String> itemIds = new ArrayList<>();
        if (configJson == null || configJson.isBlank()) {
            return itemIds;
        }
        try {
            Map<String, Object> config = objectMapper.readValue(configJson, Map.class);
            Object ids = config.get("itemIds");
            if (ids instanceof List) {
                for (Object id : (List<Object>) ids) {
                    if (id != null) {
                        itemIds.add(String.valueOf(id));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析下架任务配置失败: {}", e.getMessage());
        }
        return itemIds;
    }

    private boolean executePolishGoods(XianyuScheduleTask task) {
        if (task.getAccountId() == null) {
            return false;
        }
        List<String> itemIds = parseItemIds(task.getConfigJson());
        com.feijimiao.xianyuassistant.controller.dto.BatchPolishItemsReqDTO req =
                new com.feijimiao.xianyuassistant.controller.dto.BatchPolishItemsReqDTO();
        req.setXianyuAccountId(task.getAccountId());
        req.setXyGoodsIds(itemIds.isEmpty() ? null : itemIds);
        ResultObject<com.feijimiao.xianyuassistant.controller.dto.PolishItemRespDTO> result = itemService.batchPolishItems(req);
        return result != null && result.getCode() != null && result.getCode() == 200;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
