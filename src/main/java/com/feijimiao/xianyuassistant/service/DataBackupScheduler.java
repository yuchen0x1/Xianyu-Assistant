package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.service.bo.BackupExportReqBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Creates a complete local backup on a fixed cadence and trims old snapshots. */
@Slf4j
@Component
public class DataBackupScheduler {

    private static final String FILE_PREFIX = "xianyu-auto-backup-";
    private static final String FILE_SUFFIX = ".json";
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    private final DataBackupService dataBackupService;
    private final Path backupDirectory;
    private final boolean enabled;
    private final long intervalMillis;
    private final int retentionCount;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile Instant lastSuccessfulBackup;
    private volatile String lastBackupFile;
    private volatile String lastError;

    public DataBackupScheduler(
            DataBackupService dataBackupService,
            @Value("${app.backup.directory:dbdata/backups}") String backupDirectory,
            @Value("${app.backup.enabled:true}") boolean enabled,
            @Value("${app.backup.interval-ms:43200000}") long intervalMillis,
            @Value("${app.backup.retention-count:14}") int retentionCount) {
        this.dataBackupService = dataBackupService;
        this.backupDirectory = Paths.get(backupDirectory).toAbsolutePath().normalize();
        this.enabled = enabled;
        this.intervalMillis = Math.max(1L, intervalMillis);
        this.retentionCount = Math.max(1, retentionCount);
    }

    @Scheduled(
            fixedDelayString = "${app.backup.interval-ms:43200000}",
            initialDelayString = "${app.backup.initial-delay-ms:60000}")
    public void scheduledBackup() {
        runBackup();
    }

    /** Runs one backup immediately; useful for startup checks and focused tests. */
    public void runBackup() {
        if (!enabled) {
            log.debug("[DataBackup] 自动备份已禁用");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[DataBackup] 已有备份任务运行中，跳过本次调度");
            return;
        }

        try {
            Files.createDirectories(backupDirectory);

            Instant completedAt = Instant.now();
            String fileName = FILE_PREFIX + FILE_TIME_FORMAT.format(completedAt) + FILE_SUFFIX;
            Path target = backupDirectory.resolve(fileName).normalize();
            if (!target.getParent().equals(backupDirectory)) {
                throw new IllegalStateException("备份文件路径无效");
            }
            Path temp = Files.createTempFile(backupDirectory, FILE_PREFIX, ".tmp");
            try {
                // 直接把 JSON 流写进临时文件：旧实现先拼一整份字符串再 writeString，
                // 十万级聊天记录时堆里会同时存在 byte[] 和 String 两份完整备份。
                // 自动备份每天都会跑，OOM 风险比手动导出更高。
                try (OutputStream out = new BufferedOutputStream(
                        Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING))) {
                    dataBackupService.exportDataToStream(out, new BackupExportReqBO());
                }
                // 空文件不能顶掉已有备份；导出中途抛异常时根本走不到 move
                if (Files.size(temp) == 0) {
                    throw new IllegalStateException("备份服务未写出数据");
                }
                moveIntoPlace(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
            trimOldBackups();
            lastSuccessfulBackup = completedAt;
            lastBackupFile = fileName;
            lastError = null;
            log.info("[DataBackup] 自动备份完成: file={}, retention={}", target, retentionCount);
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("[DataBackup] 自动备份失败: directory={}", backupDirectory, e);
        } finally {
            running.set(false);
        }
    }

    public BackupScheduleStatus getStatus() {
        Path latest = findLatestBackup();
        Instant latestTime = latest == null ? lastSuccessfulBackup : fileTime(latest);
        String latestName = latest == null ? lastBackupFile : latest.getFileName().toString();
        Instant next = enabled && latestTime != null
                ? latestTime.plusMillis(intervalMillis)
                : enabled ? Instant.now().plusMillis(intervalMillis) : null;
        return new BackupScheduleStatus(enabled, intervalMillis, retentionCount,
                backupDirectory.toString(), latestName, latestTime, next, lastError);
    }

    private void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void trimOldBackups() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDirectory,
                FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) files.add(file);
            }
        }
        files.sort(Comparator.comparing(this::fileTime, Comparator.nullsLast(Comparator.reverseOrder())));
        for (int i = retentionCount; i < files.size(); i++) {
            Files.deleteIfExists(files.get(i));
        }
    }

    private Path findLatestBackup() {
        if (!Files.isDirectory(backupDirectory)) return null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDirectory,
                FILE_PREFIX + "*" + FILE_SUFFIX)) {
            Path latest = null;
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                if (latest == null) {
                    latest = file;
                    continue;
                }
                Instant fileInstant = fileTime(file);
                Instant latestInstant = fileTime(latest);
                if (fileInstant != null && (latestInstant == null || fileInstant.isAfter(latestInstant))) {
                    latest = file;
                }
            }
            return latest;
        } catch (IOException e) {
            log.debug("[DataBackup] 无法读取最新备份: {}", e.getMessage());
            return null;
        }
    }

    private Instant fileTime(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    public record BackupScheduleStatus(
            boolean enabled,
            long intervalMillis,
            int retentionCount,
            String directory,
            String latestBackupFile,
            Instant latestBackupAt,
            Instant nextBackupAt,
            String lastError) {
    }
}
