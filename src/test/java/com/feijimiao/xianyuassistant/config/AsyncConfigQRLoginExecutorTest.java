package com.feijimiao.xianyuassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncConfigQRLoginExecutorTest {

    @Test
    void qrLoginExecutorUsesBoundedCapacityAndRejectsOverflow() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 1, 1, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            executor.execute(() -> awaitRelease(running, release));
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> { });

            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .isInstanceOf(TaskRejectedException.class);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("qr-login-monitor-");
        } finally {
            release.countDown();
            executor.destroy();
        }
    }

    @Test
    void destroyingQrLoginExecutorInterruptsRunningTasksAndReclaimsThreads() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 1, 0, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        executor.execute(() -> {
            running.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();

        executor.destroy();

        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
        assertThat(executor.getThreadPoolExecutor().isShutdown()).isTrue();
        assertThat(executor.getThreadPoolExecutor().awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    private ThreadPoolTaskExecutor executor(
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            int shutdownAwaitSeconds) {
        AsyncConfig config = new AsyncConfig();
        setField(config, "qrLoginCorePoolSize", corePoolSize);
        setField(config, "qrLoginMaxPoolSize", maxPoolSize);
        setField(config, "qrLoginQueueCapacity", queueCapacity);
        setField(config, "qrLoginShutdownAwaitSeconds", shutdownAwaitSeconds);
        ThreadPoolTaskExecutor executor = ReflectionTestUtils.invokeMethod(config, "qrLoginExecutor");
        executor.afterPropertiesSet();
        return executor;
    }

    private void setField(AsyncConfig config, String fieldName, int value) {
        Field field = ReflectionUtils.findField(AsyncConfig.class, fieldName);
        assertThat(field).as(fieldName).isNotNull();
        ReflectionUtils.makeAccessible(field);
        ReflectionUtils.setField(field, config, value);
    }

    private void awaitRelease(CountDownLatch running, CountDownLatch release) {
        running.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
