package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.controller.dto.*;
import com.feijimiao.xianyuassistant.service.DataBackupService;
import com.feijimiao.xianyuassistant.service.DataBackupScheduler;
import com.feijimiao.xianyuassistant.service.bo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@RestController
@RequestMapping("/api/backup")
@RequirePermission("backup:read")
public class DataBackupController {

    private Path logRoot = Paths.get("logs");

    @Autowired
    private DataBackupService dataBackupService;

    @Autowired
    private DataBackupScheduler dataBackupScheduler;

    @Autowired
    private RbacService rbacService;

    @GetMapping("/schedule")
    public ResultObject<BackupScheduleStatusDTO> getScheduleStatus() {
        try {
            DataBackupScheduler.BackupScheduleStatus status = dataBackupScheduler.getStatus();
            BackupScheduleStatusDTO dto = new BackupScheduleStatusDTO();
            dto.setEnabled(status.enabled());
            dto.setIntervalMillis(status.intervalMillis());
            dto.setRetentionCount(status.retentionCount());
            dto.setDirectory(status.directory());
            dto.setLatestBackupFile(status.latestBackupFile());
            dto.setLatestBackupAt(status.latestBackupAt());
            dto.setNextBackupAt(status.nextBackupAt());
            dto.setLastError(status.lastError());
            return ResultObject.success(dto);
        } catch (Exception e) {
            log.error("获取自动备份状态失败", e);
            return ResultObject.failed("获取自动备份状态失败: " + e.getMessage());
        }
    }

    @PostMapping("/modules")
    public ResultObject<List<BackupModuleRespDTO>> getModules() {
        try {
            List<BackupModuleRespBO> boList = dataBackupService.getModules();
            List<BackupModuleRespDTO> result = new ArrayList<>();
            for (BackupModuleRespBO bo : boList) {
                BackupModuleRespDTO dto = new BackupModuleRespDTO();
                dto.setModuleKey(bo.getModuleKey());
                dto.setModuleName(bo.getModuleName());
                result.add(dto);
            }
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("获取备份模块列表失败", e);
            return ResultObject.failed("获取备份模块列表失败: " + e.getMessage());
        }
    }

    /**
     * 导出备份。与 {@code /export/stream} 完全等价，保留这个 URL 是为了兼容
     * 旧客户端；两者都直接写响应流，不再把整份 JSON 拼成 String。
     *
     * <p>兼容性变化：响应体不再是 {@code ResultObject<BackupExportRespDTO>}
     * 包裹的 JSON 字符串，而是附件形式的原始备份 JSON。当前前端已改用流式下载。</p>
     */
    @PostMapping("/export")
    @RequirePermission("backup:write")
    public ResponseEntity<StreamingResponseBody> exportData(@Valid @RequestBody BackupExportReqDTO reqDTO) {
        return buildStreamingBackup(reqDTO);
    }

    /**
     * 流式导出：边生成边下发，服务端内存峰值约等于"单页 + 单个最大模块"。
     *
     * <p>旧实现会把整份备份序列化成 String 再放进统一返回体，十万级聊天记录时
     * 堆里同时存在「实体列表 + Map 列表 + JSON 字符串」三份，512MB 堆很容易
     * OOM。现在直接写响应流，下载体积不再受堆大小限制。</p>
     *
     * <p>权限校验必须发生在开始写流之前：一旦 {@code StreamingResponseBody}
     * 开始执行，HTTP 状态码已经发出去了，再抛异常客户端只会看到半截文件。</p>
     */
    @PostMapping("/export/stream")
    @RequirePermission("backup:write")
    public ResponseEntity<StreamingResponseBody> exportDataStream(@Valid @RequestBody BackupExportReqDTO reqDTO) {
        return buildStreamingBackup(reqDTO);
    }

    private ResponseEntity<StreamingResponseBody> buildStreamingBackup(BackupExportReqDTO reqDTO) {
        if (!isCurrentUserAdmin()) {
            log.warn("[Backup] 非ADMIN用户尝试导出全库备份，已拒绝: userId={}, modules={}",
                    UserContext.getUserId(), reqDTO.getModules());
            throw new BusinessException(403, "数据备份包含全部账号的订单、消息与卡密，仅管理员可导出");
        }
        boolean includeSecrets = Boolean.TRUE.equals(reqDTO.getIncludeSecrets());
        if (includeSecrets) {
            // 明文导出会带出全部账号的 Cookie/Token、AI Key 与 SMTP 密码，
            // 即使同为 ADMIN 也需要留痕。
            log.warn("[Backup] 管理员导出含明文凭据的备份: userId={}, modules={}",
                    UserContext.getUserId(), reqDTO.getModules());
        }

        BackupExportReqBO reqBO = new BackupExportReqBO();
        reqBO.setModules(reqDTO.getModules());
        reqBO.setIncludeSecrets(includeSecrets);

        String fileName = "xianyu_backup_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".json";
        StreamingResponseBody body = outputStream -> dataBackupService.exportDataToStream(outputStream, reqBO);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                // 正常备份与错误 JSON 的 Content-Type 都是 application/json，
                // 前端靠这个标记区分：异常处理器返回的结果不会带它。
                .header("X-Backup-Export", "ok")
                .body(body);
    }

    @PostMapping("/import")
    @RequirePermission("backup:write")
    public ResultObject<BackupImportRespDTO> importData(@Valid @RequestBody BackupImportReqDTO reqDTO) {
        try {
            if (reqDTO == null || reqDTO.getJsonData() == null || reqDTO.getJsonData().trim().isEmpty()) {
                return ResultObject.validateFailed("备份数据不能为空");
            }
            if (!isCurrentUserAdmin()) {
                // 导入是整库覆盖写：OPERATOR 手握 backup:write 就能用一份手工构造的备份
                // 覆盖全部账号的数据，或把别人的订单/卡密写进自己的视图。
                log.warn("[Backup] 非ADMIN用户尝试导入全库备份，已拒绝: userId={}, modules={}",
                        UserContext.getUserId(), reqDTO.getModules());
                return ResultObject.forbiddenWithMessage("数据恢复会覆盖全部账号的数据，仅管理员可执行");
            }

            BackupImportReqBO reqBO = new BackupImportReqBO();
            reqBO.setJsonData(reqDTO.getJsonData());
            reqBO.setModules(reqDTO.getModules());

            BackupImportRespBO respBO = dataBackupService.importData(reqBO);

            BackupImportRespDTO respDTO = new BackupImportRespDTO();
            respDTO.setTotalCount(respBO.getTotalCount());
            respDTO.setSuccessCount(respBO.getSuccessCount());
            respDTO.setFailedModules(respBO.getFailedModules());
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("导入备份数据失败", e);
            return ResultObject.failed("导入备份数据失败: " + e.getMessage());
        }
    }

    /**
     * 备份是**整库快照**，无法按账号裁剪：12 个模块之间存在外键与业务关联，
     * 裁一半的备份恢复回去只会制造脏数据。因此导出/导入都收敛到 ADMIN。
     *
     * <p>{@code backup:write} 只是「管理备份」的权限位，OPERATOR 默认拥有；
     * 它不该等价于「可以把全部账号的订单、聊天记录、卡密搬走」。列表与状态
     * 查询（{@code backup:read}）仍然保留，运维可以看但拿不走。</p>
     */
    private boolean isCurrentUserAdmin() {
        try {
            Long userId = UserContext.getUserId();
            return userId != null && rbacService.getUserRoleNames(userId).contains("ADMIN");
        } catch (Exception e) {
            log.warn("校验备份操作人角色失败，按非管理员处理", e);
            return false;
        }
    }

    @GetMapping("/log-dates")
    public ResultObject<List<String>> getLogDates() {
        try {
            Path logDir = Paths.get("logs");
            if (!Files.exists(logDir)) {
                return ResultObject.success(new ArrayList<>());
            }

            File[] dirs = logDir.toFile().listFiles(File::isDirectory);
            if (dirs == null || dirs.length == 0) {
                return ResultObject.success(new ArrayList<>());
            }

            List<String> dates = new ArrayList<>();
            for (File dir : dirs) {
                String name = dir.getName();
                if (name.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    dates.add(name);
                }
            }

            dates.sort(Comparator.reverseOrder());
            return ResultObject.success(dates);
        } catch (Exception e) {
            log.error("获取日志日期列表失败", e);
            return ResultObject.failed("获取日志日期列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/log-download")
    public ResponseEntity<StreamingResponseBody> downloadLog(@RequestParam("date") String date) {
        if (!isValidLogDate(date)) {
            return ResponseEntity.badRequest().build();
        }

        try {
            Path normalizedRoot = logRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
                return ResponseEntity.notFound().build();
            }

            Path realRoot = normalizedRoot.toRealPath();
            Path requestedDirectory = normalizedRoot.resolve(date).normalize();
            if (!requestedDirectory.startsWith(normalizedRoot)
                    || !Files.isDirectory(requestedDirectory, LinkOption.NOFOLLOW_LINKS)) {
                return ResponseEntity.notFound().build();
            }

            Path realDirectory = requestedDirectory.toRealPath();
            if (!realDirectory.startsWith(realRoot)) {
                return ResponseEntity.badRequest().build();
            }

            List<OpenedLogFile> logFiles = openLogFiles(realDirectory);
            if (logFiles.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            StreamingResponseBody body = outputStream -> {
                try (ZipOutputStream zipOutput = new ZipOutputStream(outputStream)) {
                    for (OpenedLogFile logFile : logFiles) {
                        try (SeekableByteChannel channel = logFile.channel();
                             InputStream input = Channels.newInputStream(channel)) {
                            zipOutput.putNextEntry(new ZipEntry(logFile.name()));
                            input.transferTo(zipOutput);
                            zipOutput.closeEntry();
                        }
                    }
                } finally {
                    closeLogFiles(logFiles);
                }
            };

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + date + ".zip\"")
                    .body(body);
        } catch (Exception e) {
            log.error("下载日志文件失败: errorType={}", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().build();
        }
    }

    private List<OpenedLogFile> openLogFiles(Path realDirectory) throws IOException {
        List<OpenedLogFile> openedFiles = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(
                realDirectory,
                entry -> entry.getFileName().toString().endsWith(".log"))) {
            if (entries instanceof SecureDirectoryStream<?> rawSecureEntries) {
                @SuppressWarnings("unchecked")
                SecureDirectoryStream<Path> secureEntries = (SecureDirectoryStream<Path>) rawSecureEntries;
                for (Path entry : entries) {
                    Path fileName = entry.getFileName();
                    BasicFileAttributeView attributeView = secureEntries.getFileAttributeView(
                            fileName,
                            BasicFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS);
                    BasicFileAttributes attributes = attributeView.readAttributes();
                    if (!attributes.isRegularFile()) {
                        continue;
                    }
                    openedFiles.add(new OpenedLogFile(
                            fileName.toString(),
                            secureEntries.newByteChannel(
                                    fileName,
                                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))));
                }
            } else {
                for (Path entry : entries) {
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    openedFiles.add(new OpenedLogFile(
                            entry.getFileName().toString(),
                            Files.newByteChannel(
                                    entry,
                                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))));
                }
                if (!Files.isDirectory(realDirectory, LinkOption.NOFOLLOW_LINKS)
                        || !realDirectory.equals(realDirectory.toRealPath())) {
                    throw new IOException("日志目录在读取期间发生变化");
                }
            }
            openedFiles.sort(Comparator.comparing(OpenedLogFile::name));
            return openedFiles;
        } catch (IOException | RuntimeException e) {
            closeLogFiles(openedFiles);
            throw e;
        }
    }

    private void closeLogFiles(List<OpenedLogFile> logFiles) {
        for (OpenedLogFile logFile : logFiles) {
            try {
                logFile.channel().close();
            } catch (IOException ignored) {
                // Best-effort cleanup after a failed response or interrupted stream.
            }
        }
    }

    private record OpenedLogFile(String name, SeekableByteChannel channel) {
    }

    private boolean isValidLogDate(String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return false;
        }
        try {
            LocalDate.parse(date);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}

