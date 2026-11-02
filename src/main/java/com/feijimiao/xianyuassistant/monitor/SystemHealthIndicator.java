package com.feijimiao.xianyuassistant.monitor;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;

/**
 * 系统健康指示器：内存、CPU、线程信息。
 */
@Component("systemHealth")
@RequiredArgsConstructor
public class SystemHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

        long heapUsed = memory.getHeapMemoryUsage().getUsed();
        long heapMax = memory.getHeapMemoryUsage().getMax();
        double heapUsage = heapMax > 0 ? (double) heapUsed / heapMax * 100 : 0;

        int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();

        return Health.up()
                .withDetail("jvm", "Java " + System.getProperty("java.version"))
                .withDetail("os", os.getName() + " " + os.getVersion())
                .withDetail("cpu", os.getAvailableProcessors() + " cores")
                .withDetail("heapUsed", formatBytes(heapUsed))
                .withDetail("heapMax", formatBytes(heapMax))
                .withDetail("heapUsage", String.format("%.1f%%", heapUsage))
                .withDetail("threads", threadCount)
                .build();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
