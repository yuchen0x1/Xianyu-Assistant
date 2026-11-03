package com.feijimiao.xianyuassistant.service.update;

import com.feijimiao.xianyuassistant.controller.dto.VersionInfoRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时自动更新调度器：周期检查官方发布通道，发现新版本后自动执行
 * 下载 → 校验 → 替换 → 重启，无需人工介入。
 *
 * <p>复用 {@link SelfUpdateService} 的更新管线（SHA-256 + Ed25519 签名校验、原子替换、失败回滚），
 * 复用 {@link UpdateCheckService} 的版本检查（主机白名单 + 缓存）。重启语义跟随 restart-mode：
 * exit（默认）退出进程交由 systemd {@code Restart=always} / 守护循环拉起；manual 只替换新包，
 * 等待人工重启；self 由进程自拉起。</p>
 *
 * <p>检查本身在调度线程内完成（轻量、有缓存），下载与替换则交给独立守护线程执行，
 * 避免长时间下载阻塞 Spring 默认单线程调度器上的其他定时任务。</p>
 *
 * @author IAMLZY
 */
@Slf4j
@Component
public class SelfUpdateAutoScheduler {

    /** 自动更新开关：默认开启，可用 XIANYU_ASSISTANT_AUTO_UPDATE_ENABLED=false 关闭。 */
    @Value("${app.self-update.auto-update:true}")
    private boolean autoUpdateEnabled;

    /** 重启策略（与 SelfUpdateService 同源）：manual 时不自动重启。 */
    @Value("${app.self-update.restart-mode:exit}")
    private String restartMode;

    /** 等待替换完成的超时时间；超出后放弃本次自动重启，等待下一轮检查。 */
    Duration applyWaitTimeout = Duration.ofMinutes(30);
    /** 轮询更新状态的间隔。 */
    Duration pollInterval = Duration.ofSeconds(5);

    private final UpdateCheckService updateCheckService;
    private final SelfUpdateService selfUpdateService;
    /** 防重入：同一时间只允许一个自动更新流程在执行。 */
    private final AtomicBoolean applying = new AtomicBoolean(false);

    public SelfUpdateAutoScheduler(UpdateCheckService updateCheckService,
                                   SelfUpdateService selfUpdateService) {
        this.updateCheckService = updateCheckService;
        this.selfUpdateService = selfUpdateService;
    }

    /** 默认每 6 小时检查一次，启动 5 分钟后做首次检查。 */
    @Scheduled(
            fixedDelayString = "${app.self-update.auto-update-check-interval-ms:21600000}",
            initialDelayString = "${app.self-update.auto-update-initial-delay-ms:300000}")
    public void scheduledAutoUpdate() {
        if (!autoUpdateEnabled) {
            return;
        }
        try {
            String target = findPendingUpdate();
            if (target != null) {
                startAutoUpdate(target);
            }
        } catch (Exception e) {
            log.warn("自动更新检查失败: {}", e.getMessage());
        }
    }

    /**
     * 判断当前是否需要自动更新；需要则返回目标版本号，否则返回 null。
     * 更新流程进行中（含已替换待重启）时跳过，避免与手动更新/重启流程竞争。
     */
    String findPendingUpdate() {
        if (!selfUpdateService.isAvailable()) {
            return null;
        }
        SelfUpdateService.Status status = selfUpdateService.getStatus();
        String phase = status == null ? null : status.phase();
        if (phase != null && !SelfUpdateService.PHASE_IDLE.equals(phase)
                && !SelfUpdateService.PHASE_FAILED.equals(phase)) {
            log.info("自动更新跳过：已有更新流程进行中（阶段 {}）", phase);
            return null;
        }

        VersionInfoRespDTO info = updateCheckService.checkUpdate();
        if (info == null || !"available".equals(info.getCheckStatus())
                || !Boolean.TRUE.equals(info.getHasUpdate())) {
            return null;
        }
        String target = info.getLatestVersion();
        return target == null || target.isBlank() ? null : target;
    }

    /**
     * 在独立守护线程中执行自动更新流程；同一时间仅允许一个（重复调用返回 false）。
     */
    boolean startAutoUpdate(String targetVersion) {
        if (!applying.compareAndSet(false, true)) {
            log.info("自动更新已在进行中，忽略本次触发（目标版本 v{}）", targetVersion);
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                applyAndWaitRestart(targetVersion);
            } catch (Exception e) {
                log.warn("自动更新 v{} 执行异常: {}", targetVersion, e.getMessage());
            } finally {
                applying.set(false);
            }
        }, "self-update-auto");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /** 同步执行：apply → 等待替换完成 → 按策略重启。供调度线程交接与测试直接调用。 */
    void applyAndWaitRestart(String targetVersion) {
        log.info("自动更新：发现新版本 v{}，开始下载并替换", targetVersion);
        if (!selfUpdateService.apply(targetVersion)) {
            SelfUpdateService.Status status = selfUpdateService.getStatus();
            log.warn("自动更新：v{} 未能开始（阶段 {}，原因：{}）",
                    targetVersion, status == null ? null : status.phase(),
                    status == null ? null : status.message());
            return;
        }

        if (!waitUntilSwapped(targetVersion)) {
            return;
        }

        if ("manual".equalsIgnoreCase(restartMode == null ? "" : restartMode.trim())) {
            log.info("自动更新：v{} 新包已替换完成，restart-mode=manual，等待人工重启", targetVersion);
            return;
        }
        log.info("自动更新：v{} 新包已就绪，执行重启（restart-mode={}），进程将由 systemd Restart=always 拉起",
                targetVersion, restartMode);
        if (!selfUpdateService.restartNow()) {
            SelfUpdateService.Status status = selfUpdateService.getStatus();
            log.warn("自动更新：v{} 重启未执行（当前阶段 {}），请人工检查",
                    targetVersion, status == null ? null : status.phase());
        }
    }

    /** 轮询更新状态，直到新包替换完成（true）或失败/超时（false）。 */
    boolean waitUntilSwapped(String targetVersion) {
        long deadline = System.nanoTime() + applyWaitTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            SelfUpdateService.Status status = selfUpdateService.getStatus();
            String phase = status == null ? null : status.phase();
            if (SelfUpdateService.PHASE_SWAPPED.equals(phase)) {
                return true;
            }
            if (SelfUpdateService.PHASE_FAILED.equals(phase)) {
                log.warn("自动更新：v{} 更新失败（原因：{}）", targetVersion,
                        status == null ? null : status.message());
                return false;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("自动更新：等待 v{} 替换完成时被中断", targetVersion);
                return false;
            }
        }
        log.warn("自动更新：等待 v{} 替换完成超时（{} 分钟），放弃本次自动重启，请人工检查更新状态",
                targetVersion, applyWaitTimeout.toMinutes());
        return false;
    }
}
