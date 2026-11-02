package com.feijimiao.xianyuassistant.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DataBackupSchedulerTest {

    @TempDir
    Path tempDir;

    @Test
    void writesCompleteBackupAndTrimsOldSnapshots() throws Exception {
        DataBackupService service = mock(DataBackupService.class);
        stubStreamExport(service, "{\"version\":\"2.0\",\"modules\":{}}");
        DataBackupScheduler scheduler = new DataBackupScheduler(service, tempDir.toString(), true, 43_200_000L, 2);

        Path oldOne = tempDir.resolve("xianyu-auto-backup-20200101-000000-000.json");
        Path oldTwo = tempDir.resolve("xianyu-auto-backup-20200102-000000-000.json");
        Path oldThree = tempDir.resolve("xianyu-auto-backup-20200103-000000-000.json");
        Files.writeString(oldOne, "one");
        Files.writeString(oldTwo, "two");
        Files.writeString(oldThree, "three");
        Files.setLastModifiedTime(oldOne, java.nio.file.attribute.FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        Files.setLastModifiedTime(oldTwo, java.nio.file.attribute.FileTime.from(Instant.parse("2020-01-02T00:00:00Z")));
        Files.setLastModifiedTime(oldThree, java.nio.file.attribute.FileTime.from(Instant.parse("2020-01-03T00:00:00Z")));

        scheduler.runBackup();

        List<Path> backups;
        try (var stream = Files.list(tempDir)) {
            backups = stream.filter(path -> path.getFileName().toString().endsWith(".json")).toList();
        }
        assertEquals(2, backups.size());
        assertFalse(Files.exists(oldOne));
        assertNotNull(scheduler.getStatus().latestBackupFile());
        assertNull(scheduler.getStatus().lastError());
        // 备份内容应当来自流，而不是先拼成一个大字符串
        assertEquals("{\"version\":\"2.0\",\"modules\":{}}",
                Files.readString(backups.stream()
                        .filter(p -> !p.getFileName().toString().startsWith("xianyu-auto-backup-2020"))
                        .findFirst().orElseThrow()));
        verify(service).exportDataToStream(any(), any());
    }

    @Test
    void emptyStreamDoesNotReplaceExistingBackup() throws Exception {
        DataBackupService service = mock(DataBackupService.class);
        stubStreamExport(service, "");
        DataBackupScheduler scheduler = new DataBackupScheduler(service, tempDir.toString(), true, 43_200_000L, 2);

        scheduler.runBackup();

        assertNull(scheduler.getStatus().latestBackupFile());
        assertNotNull(scheduler.getStatus().lastError());
        try (var stream = Files.list(tempDir)) {
            assertFalse(stream.anyMatch(p -> p.getFileName().toString().endsWith(".json")));
        }
    }

    @Test
    void failedStreamLeavesNoPartialSnapshot() throws Exception {
        DataBackupService service = mock(DataBackupService.class);
        doThrow(new IllegalStateException("模块导出失败"))
                .when(service).exportDataToStream(any(), any());
        DataBackupScheduler scheduler = new DataBackupScheduler(service, tempDir.toString(), true, 43_200_000L, 2);

        scheduler.runBackup();

        assertNull(scheduler.getStatus().latestBackupFile());
        assertNotNull(scheduler.getStatus().lastError());
        try (var stream = Files.list(tempDir)) {
            assertFalse(stream.anyMatch(p -> p.getFileName().toString().endsWith(".json") || p.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static void stubStreamExport(DataBackupService service, String content) throws Exception {
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(0);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(service).exportDataToStream(any(), any());
    }

    @Test
    void disabledSchedulerDoesNotExport() {
        DataBackupService service = mock(DataBackupService.class);
        DataBackupScheduler scheduler = new DataBackupScheduler(service, tempDir.toString(), false, 1_000L, 2);

        scheduler.runBackup();

        assertFalse(scheduler.getStatus().enabled());
        assertNull(scheduler.getStatus().latestBackupFile());
    }
}
