package com.feijimiao.xianyuassistant.service.update;

import com.feijimiao.xianyuassistant.controller.dto.VersionInfoRespDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelfUpdateAutoSchedulerTest {

    private final UpdateCheckService updateCheckService = mock(UpdateCheckService.class);
    private final SelfUpdateService selfUpdateService = mock(SelfUpdateService.class);
    private final SelfUpdateAutoScheduler scheduler =
            new SelfUpdateAutoScheduler(updateCheckService, selfUpdateService);

    @Test
    void disabledAutoUpdateDoesNothing() {
        ReflectionTestUtils.setField(scheduler, "autoUpdateEnabled", false);
        stubStatus(SelfUpdateService.PHASE_IDLE);
        stubCheckResult(availableUpdate("3.6.0"));

        scheduler.scheduledAutoUpdate();

        verify(selfUpdateService, never()).apply(anyString());
        verify(selfUpdateService, never()).restartNow();
    }

    @Test
    void selfUpdateUnavailableSkipsCheck() {
        ReflectionTestUtils.setField(scheduler, "autoUpdateEnabled", true);
        when(selfUpdateService.isAvailable()).thenReturn(false);

        assertThat(scheduler.findPendingUpdate()).isNull();

        verify(updateCheckService, never()).checkUpdate();
        verify(selfUpdateService, never()).apply(anyString());
    }

    @Test
    void inProgressPhaseSkipsCheck() {
        ReflectionTestUtils.setField(scheduler, "autoUpdateEnabled", true);
        when(selfUpdateService.isAvailable()).thenReturn(true);
        stubStatus(SelfUpdateService.PHASE_DOWNLOADING);

        assertThat(scheduler.findPendingUpdate()).isNull();

        verify(updateCheckService, never()).checkUpdate();
    }

    @Test
    void swappedPhaseSkipsCheck() {
        ReflectionTestUtils.setField(scheduler, "autoUpdateEnabled", true);
        when(selfUpdateService.isAvailable()).thenReturn(true);
        stubStatus(SelfUpdateService.PHASE_SWAPPED);

        assertThat(scheduler.findPendingUpdate()).isNull();

        verify(updateCheckService, never()).checkUpdate();
    }

    @Test
    void unavailableCheckResultDoesNotTriggerUpdate() {
        ReflectionTestUtils.setField(scheduler, "autoUpdateEnabled", true);
        when(selfUpdateService.isAvailable()).thenReturn(true);
        stubStatus(SelfUpdateService.PHASE_IDLE);
        stubCheckResult(unavailableResult());

        assertThat(scheduler.findPendingUpdate()).isNull();

        verify(selfUpdateService, never()).apply(anyString());
    }

    @Test
    void noUpdateAvailableDoesNotTriggerUpdate() {
        ReflectionTestUtils.setField(scheduler, "autoUpdateEnabled", true);
        when(selfUpdateService.isAvailable()).thenReturn(true);
        stubStatus(SelfUpdateService.PHASE_IDLE);
        stubCheckResult(availableNoUpdate());

        assertThat(scheduler.findPendingUpdate()).isNull();

        verify(selfUpdateService, never()).apply(anyString());
    }

    @Test
    void pendingUpdateTriggersApplyAndRestartOnSwapped() {
        ReflectionTestUtils.setField(scheduler, "autoUpdateEnabled", true);
        scheduler.pollInterval = Duration.ofMillis(10);
        when(selfUpdateService.isAvailable()).thenReturn(true);
        stubStatus(SelfUpdateService.PHASE_IDLE);
        stubCheckResult(availableUpdate("3.6.0"));

        assertThat(scheduler.findPendingUpdate()).isEqualTo("3.6.0");

        // apply 启动后台下载后，状态轮询按序读到 DOWNLOADING → SWAPPED。
        stubStatusSequence(SelfUpdateService.PHASE_DOWNLOADING, SelfUpdateService.PHASE_SWAPPED);
        when(selfUpdateService.apply("3.6.0")).thenReturn(true);

        scheduler.applyAndWaitRestart("3.6.0");

        verify(selfUpdateService).restartNow();
    }

    @Test
    void startAutoUpdateRunsApplyInBackgroundThenExitsOnFailure() throws Exception {
        when(selfUpdateService.isAvailable()).thenReturn(true);
        CountDownLatch applied = new CountDownLatch(1);
        when(selfUpdateService.apply("3.6.0")).thenAnswer(invocation -> {
            applied.countDown();
            return true;
        });
        // apply 之后状态进入 FAILED，后台线程随即退出，不触发重启。
        stubStatus(SelfUpdateService.PHASE_FAILED);

        assertThat(scheduler.startAutoUpdate("3.6.0")).isTrue();

        assertThat(applied.await(5, TimeUnit.SECONDS)).isTrue();
        verify(selfUpdateService, timeout(5000)).apply("3.6.0");
        verify(selfUpdateService, never()).restartNow();
    }

    @Test
    void rejectedApplyDoesNotWaitOrRestart() {
        ReflectionTestUtils.setField(scheduler, "autoUpdateEnabled", true);
        ReflectionTestUtils.setField(scheduler, "restartMode", "exit");
        when(selfUpdateService.isAvailable()).thenReturn(true);
        stubStatus(SelfUpdateService.PHASE_IDLE);
        stubCheckResult(availableUpdate("3.6.0"));
        when(selfUpdateService.apply("3.6.0")).thenReturn(false);

        scheduler.applyAndWaitRestart("3.6.0");

        verify(selfUpdateService, never()).restartNow();
    }

    @Test
    void failedPhaseDoesNotRestart() {
        ReflectionTestUtils.setField(scheduler, "autoUpdateEnabled", true);
        ReflectionTestUtils.setField(scheduler, "restartMode", "exit");
        when(selfUpdateService.isAvailable()).thenReturn(true);
        when(selfUpdateService.apply("3.6.0")).thenReturn(true);
        // apply 后状态立即进入 FAILED。
        when(selfUpdateService.getStatus()).thenReturn(
                new SelfUpdateService.Status(SelfUpdateService.PHASE_FAILED, 0, 0, 0, "3.6.0", "校验失败"));

        scheduler.applyAndWaitRestart("3.6.0");

        verify(selfUpdateService, never()).restartNow();
    }

    @Test
    void manualRestartModeReplacesWithoutRestarting() {
        ReflectionTestUtils.setField(scheduler, "autoUpdateEnabled", true);
        ReflectionTestUtils.setField(scheduler, "restartMode", "manual");
        when(selfUpdateService.isAvailable()).thenReturn(true);
        when(selfUpdateService.apply("3.6.0")).thenReturn(true);
        when(selfUpdateService.getStatus()).thenReturn(
                new SelfUpdateService.Status(SelfUpdateService.PHASE_SWAPPED, 100, 1, 1, "3.6.0", "新包已就绪"));

        scheduler.applyAndWaitRestart("3.6.0");

        verify(selfUpdateService, never()).restartNow();
    }

    @Test
    void waitTimeoutGivesUpWithoutRestart() {
        ReflectionTestUtils.setField(scheduler, "autoUpdateEnabled", true);
        ReflectionTestUtils.setField(scheduler, "restartMode", "exit");
        scheduler.applyWaitTimeout = Duration.ofMillis(30);
        scheduler.pollInterval = Duration.ofMillis(5);
        when(selfUpdateService.isAvailable()).thenReturn(true);
        when(selfUpdateService.apply("3.6.0")).thenReturn(true);
        when(selfUpdateService.getStatus()).thenReturn(
                new SelfUpdateService.Status(SelfUpdateService.PHASE_DOWNLOADING, 10, 1, 10, "3.6.0", null));

        scheduler.applyAndWaitRestart("3.6.0");

        verify(selfUpdateService, never()).restartNow();
    }

    @Test
    void concurrentTriggerIsIgnoredWhileRunning() {
        when(selfUpdateService.isAvailable()).thenReturn(true);

        ReflectionTestUtils.setField(scheduler, "applying", new java.util.concurrent.atomic.AtomicBoolean(true));
        assertThat(scheduler.startAutoUpdate("3.6.0")).isFalse();
        verify(selfUpdateService, never()).apply(anyString());
    }

    private void stubStatus(String phase) {
        when(selfUpdateService.getStatus()).thenReturn(
                new SelfUpdateService.Status(phase, 0, 0, 0, null, null));
    }

    private void stubStatusSequence(String... phases) {
        if (phases.length == 1) {
            stubStatus(phases[0]);
            return;
        }
        SelfUpdateService.Status[] statuses = new SelfUpdateService.Status[phases.length];
        for (int i = 0; i < phases.length; i++) {
            statuses[i] = new SelfUpdateService.Status(phases[i], 0, 0, 0, null, null);
        }
        when(selfUpdateService.getStatus()).thenReturn(statuses[0],
                java.util.Arrays.copyOfRange(statuses, 1, statuses.length));
    }

    private void stubCheckResult(VersionInfoRespDTO info) {
        when(updateCheckService.checkUpdate()).thenReturn(info);
    }

    private VersionInfoRespDTO availableUpdate(String latestVersion) {
        VersionInfoRespDTO info = new VersionInfoRespDTO();
        info.setCurrentVersion("3.5.16");
        info.setLatestVersion(latestVersion);
        info.setHasUpdate(true);
        info.setCheckStatus("available");
        return info;
    }

    private VersionInfoRespDTO availableNoUpdate() {
        VersionInfoRespDTO info = new VersionInfoRespDTO();
        info.setCurrentVersion("3.5.16");
        info.setLatestVersion("3.5.16");
        info.setHasUpdate(false);
        info.setCheckStatus("available");
        return info;
    }

    private VersionInfoRespDTO unavailableResult() {
        VersionInfoRespDTO info = new VersionInfoRespDTO();
        info.setCurrentVersion("3.5.16");
        info.setLatestVersion("3.5.16");
        info.setHasUpdate(false);
        info.setCheckStatus("unavailable");
        return info;
    }
}
