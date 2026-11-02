package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.controller.dto.ScheduleTaskReqDTO;
import com.feijimiao.xianyuassistant.entity.XianyuScheduleTask;

import java.util.List;

/**
 * 定时任务服务（Cron 定时同步/发布/下架）
 */
public interface ScheduleTaskService {

    /** 创建任务并立即调度 */
    XianyuScheduleTask create(ScheduleTaskReqDTO req);

    /** 更新任务并重新调度 */
    XianyuScheduleTask update(Long id, ScheduleTaskReqDTO req);

    /** 启用/禁用任务（paused 归零） */
    XianyuScheduleTask toggle(Long id, boolean enabled);

    /** 删除任务 */
    boolean delete(Long id);

    /** 立即执行一次（不依赖 Cron 调度） */
    XianyuScheduleTask trigger(Long id);

    /** 查询全部任务 */
    List<XianyuScheduleTask> list();

    /** 应用启动时重新调度所有启用任务 */
    void reloadAll();

    /**
     * guard 恢复：扫描因连续失败自动暂停但仍启用的任务，若关联账号已脱离认证/风控冷却
     * 且暂停超过最小恢复间隔，则自动恢复调度。由内部定时器周期调用，也可手动触发。
     */
    void recoverPausedTasks();
}
