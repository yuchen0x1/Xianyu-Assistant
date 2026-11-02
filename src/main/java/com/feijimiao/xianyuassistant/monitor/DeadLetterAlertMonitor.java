package com.feijimiao.xianyuassistant.monitor;

import com.feijimiao.xianyuassistant.job.mapper.JobDeadLetterMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 死信堆积告警巡检：定时统计未处理死信数量并触发告警。
 * <p>范围约束：不触碰 JobEngine，仅通过 mapper 统计并调用 AlertService 检查入口。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterAlertMonitor {

    private final JobDeadLetterMapper deadLetterMapper;
    private final AlertService alertService;

    private static final int DEAD_LETTER_ALERT_THRESHOLD = 5;

    @PostConstruct
    public void init() {
        log.info("死信堆积告警巡检已启动");
    }

    /** 每 15 分钟巡检一次未处理死信数量。 */
    @Scheduled(fixedDelay = 900_000L, initialDelay = 120_000L)
    public void check() {
        try {
            int count = Math.toIntExact(deadLetterMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.feijimiao.xianyuassistant.job.entity.JobDeadLetter>()
                            .eq(com.feijimiao.xianyuassistant.job.entity.JobDeadLetter::getResolved, 0)));
            alertService.checkDeadLetterCount(count, DEAD_LETTER_ALERT_THRESHOLD);
        } catch (Exception e) {
            log.warn("死信堆积告警巡检异常: errorType={}", e.getClass().getSimpleName());
        }
    }
}