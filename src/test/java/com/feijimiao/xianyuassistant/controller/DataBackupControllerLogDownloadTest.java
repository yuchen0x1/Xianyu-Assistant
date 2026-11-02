package com.feijimiao.xianyuassistant.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DataBackupControllerLogDownloadTest {

    @TempDir
    Path tempDir;

    private DataBackupController controller;
    private Path logRoot;

    @BeforeEach
    void setUp() {
        controller = new DataBackupController();
        logRoot = tempDir.resolve("logs");
        ReflectionTestUtils.setField(controller, "logRoot", logRoot);
    }

    @Test
    void rejectsTraversalAndInvalidCalendarDatesBeforeReadingFiles() throws Exception {
        Files.createDirectories(logRoot);
        Path outsideLog = tempDir.resolve("outside.log");
        Files.writeString(outsideLog, "private", StandardCharsets.UTF_8);

        assertEquals(HttpStatus.BAD_REQUEST, controller.downloadLog("../").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.downloadLog("2026-02-30").getStatusCode());
        assertFalse(Files.exists(tempDir.resolve(".zip")));
    }

    @Test
    void streamsOnlyRegularLogFilesWithoutCreatingAnArchiveOnDisk() throws Exception {
        Path dateDirectory = logRoot.resolve("2026-07-14");
        Files.createDirectories(dateDirectory);
        Files.writeString(dateDirectory.resolve("application.log"), "first", StandardCharsets.UTF_8);
        Files.writeString(dateDirectory.resolve("worker.log"), "second", StandardCharsets.UTF_8);
        Files.writeString(dateDirectory.resolve("ignored.txt"), "ignored", StandardCharsets.UTF_8);

        var response = controller.downloadLog("2026-07-14");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        Map<String, String> entries = readZip(output.toByteArray());
        assertEquals(Map.of("application.log", "first", "worker.log", "second"), entries);
        assertFalse(Files.exists(logRoot.resolve("2026-07-14.zip")));
    }

    @Test
    void refusesARegularLogThatIsReplacedByASymlinkBeforeStreaming() throws Exception {
        Path dateDirectory = logRoot.resolve("2026-07-14");
        Files.createDirectories(dateDirectory);
        Path logFile = dateDirectory.resolve("application.log");
        Files.writeString(logFile, "public", StandardCharsets.UTF_8);
        Path outsideFile = tempDir.resolve("outside-secret.log");
        Files.writeString(outsideFile, "must-not-leak", StandardCharsets.UTF_8);

        var response = controller.downloadLog("2026-07-14");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        Files.delete(logFile);
        try {
            Files.createSymbolicLink(logFile, outsideFile);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "当前文件系统不允许创建符号链接");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        assertEquals(Map.of("application.log", "public"), readZip(output.toByteArray()));
        assertFalse(output.toString(StandardCharsets.ISO_8859_1).contains("must-not-leak"));
    }

    @Test
    void streamsFromOpenedHandlesWhenTheDateDirectoryIsReplacedBeforeStreaming() throws Exception {
        Path dateDirectory = logRoot.resolve("2026-07-14");
        Files.createDirectories(dateDirectory);
        Files.writeString(dateDirectory.resolve("application.log"), "public", StandardCharsets.UTF_8);

        var response = controller.downloadLog("2026-07-14");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        Path originalDirectory = logRoot.resolve("original-date-directory");
        try {
            Files.move(dateDirectory, originalDirectory);
            Files.createDirectories(dateDirectory);
            Files.writeString(dateDirectory.resolve("application.log"), "must-not-leak", StandardCharsets.UTF_8);
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "当前文件系统不允许替换包含已打开文件的目录");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        assertEquals(Map.of("application.log", "public"), readZip(output.toByteArray()));
        assertFalse(output.toString(StandardCharsets.ISO_8859_1).contains("must-not-leak"));
    }

    private Map<String, String> readZip(byte[] zipBytes) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zipInput.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
