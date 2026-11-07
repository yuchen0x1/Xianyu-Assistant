package com.feijimiao.xianyuassistant.service.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.common.VersionUtils;
import com.feijimiao.xianyuassistant.security.ReleaseSignatureVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在线自更新服务：从发布通道下载新版 JAR → SHA-256 校验 → 原子替换 → 按重启策略重启。
 *
 * <p>安全约束：
 * <ul>
 *   <li>下载仅允许 HTTPS，且主机限定为 GitHub 官方域名及 release-assets.githubusercontent.com、githubusercontent.com（含手动重定向逐跳校验）；</li>
 *   <li>重定向跨主机时剥离 Authorization 头，避免下载令牌泄漏给第三方对象主机；</li>
 *   <li>新包必须通过随 Release 发布的 .sha256 校验，否则拒绝替换；</li>
 *   <li>官方渠道更新额外强制 Ed25519 发布物签名校验（.jar.sig）：SHA-256 校验文件与安装包同源，
 *       发布通道被入侵时无法同时伪造签名；签名私钥仅在发布方 CI Secret/离线保管，客户端只内置公钥；</li>
 *   <li>替换采用"备份 → 原子移动 → 失败回滚"流程，保证任意时刻磁盘上都有可用的 JAR。</li>
 * </ul></p>
 *
 * <p>重启策略 restart-mode：exit（默认，退出交给 systemd/Docker/install.sh 循环等进程管理器拉起）、
 * self（进程自拉起：退出前以相同命令行生成新进程接管服务，适合裸 nohup 部署，与外部循环/容器共存会端口竞争）、
 * manual（只替换，人工重启）。历史配置的 command 模式已废弃：不再执行配置驱动的 shell 命令，
 * 统一按 exit 处理。</p>
 */
@Slf4j
@Service
public class SelfUpdateService {

    public static final String PHASE_IDLE = "IDLE";
    public static final String PHASE_DOWNLOADING = "DOWNLOADING";
    public static final String PHASE_VERIFYING = "VERIFYING";
    public static final String PHASE_SWAPPED = "SWAPPED";
    public static final String PHASE_RESTARTING = "RESTARTING";
    public static final String PHASE_FAILED = "FAILED";

    private static final String GITHUB_API_PREFIX = "https://api.github.com/repos/";
    private static final String GITHUB_API_ACCEPT = "application/vnd.github+json";
    private static final String GITHUB_API_VERSION = "2022-11-28";
    private static final Pattern SHA256_LINE = Pattern.compile("([0-9a-fA-F]{64})");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);
    /** 资产大小探测（HEAD）专用超时：探测失败仅退化为无百分比进度，不能拖住更新流程。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);
    /**
     * 响应体读停滞守护阈值：HttpRequest 的超时只覆盖到收到响应头，此后 body 读阻塞没有超时；
     * 连接中途停滞（无新字节超过该时长）必须主动中止，否则更新线程永久卡死、状态机无法复位。
     */
    private static final Duration BODY_STALL_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_REDIRECT_HOPS = 5;

    /** 官方发布渠道仓库标识：更新源为该仓库时强制发布物签名校验（auto 模式）。 */
    static final String OFFICIAL_UPDATE_SLUG = "yuchen0x1/Xianyu-Assistant";

    /**
     * 官方发布签名验证公钥（Ed25519，X.509 SPKI DER 的 Base64）。
     * 仅公钥可公开；对应私钥由发布方在 CI Secret/离线保管，绝不进入仓库与安装包。
     * 可用环境变量 XIANYU_ASSISTANT_RELEASE_PUBLIC_KEY 覆盖（密钥轮换/社区渠道自管签名）。
     */
    static final String OFFICIAL_RELEASE_PUBLIC_KEY =
            "MCowBQYDK2VwAyEAsstW68X2ZMRu5o2ZVHwBiV3m4YezlHuScQy3Jo/Lie4=";

    @Value("${app.version:3.5.20}")
    private String currentVersion;

    @Value("${app.self-update.enabled:true}")
    private boolean enabled;

    @Value("${app.update-check.github-repository:yuchen0x1/Xianyu-Assistant}")
    private String githubRepository;

    /** 兼容历史配置字段；更新源始终以 GitHub 配置为准。 */
    @Value("${app.update-check.gitee-repository:}")
    private String giteeRepository;

    @Value("${app.self-update.restart-mode:exit}")
    private String restartMode;

    /** 可选镜像前缀（如反代加速地址），设置后按 {base}/{文件名} 下载 JAR，仍强制 SHA-256 校验。 */
    @Value("${app.self-update.jar-base-url:}")
    private String jarBaseUrl;

    /**
     * 发布物签名校验模式：auto（默认，官方渠道强制、社区渠道不强制）、true（总是强制）、false（总是关闭）。
     */
    @Value("${app.self-update.signature-required:auto}")
    private String signatureRequiredMode;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "self-update");
        thread.setDaemon(true);
        return thread;
    });

    /** 响应体停滞守护线程：仅在判定连接停滞时关闭对应响应体，平时不参与下载。 */
    private final ScheduledExecutorService stallGuard = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "self-update-stall-guard");
        thread.setDaemon(true);
        return thread;
    });

    private Path currentJarPath;
    private final String unavailableReason;

    private volatile Status status = new Status(PHASE_IDLE, 0, 0, 0, null, null);

    public SelfUpdateService() {
        String[] processArgs = ProcessHandle.current().info().arguments().orElse(new String[0]);
        // Windows 上 JVM 常取不到自身启动参数（arguments() 返回 empty），
        // 再叠加 ApplicationHome 从类加载来源解析实际运行的 JAR 作为最终兜底。
        java.io.File homeFile = new ApplicationHome(SelfUpdateService.class).getSource();
        Path homeSource = homeFile == null ? null : homeFile.toPath();
        Path detected = recoverFromBackupIfMissing(
                detectCurrentJar(processArgs, System.getenv("XIANYU_ASSISTANT_JAR_PATH"), homeSource));
        this.currentJarPath = detected;
        this.unavailableReason = detected == null
                ? "未以独立 JAR 方式运行（如 IDE 开发模式），在线更新不可用；请通过发布页手动下载"
                : null;
        if (detected != null) {
            log.info("在线自更新已就绪: jarPath={}", detected);
        }
    }

    /**
     * 从启动参数中定位当前 JAR：优先取 -jar 后的参数，其次任意以 .jar 结尾的参数。
     */
    static Path detectCurrentJar(String[] args, String envJarPath) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if ("-jar".equals(args[i]) && i + 1 < args.length && args[i + 1].endsWith(".jar")) {
                    return Path.of(args[i + 1]);
                }
            }
            for (String arg : args) {
                if (arg != null && arg.endsWith(".jar") && arg.indexOf(';') < 0 && arg.indexOf(':') < 0) {
                    return Path.of(arg);
                }
            }
        }
        if (envJarPath != null && !envJarPath.isBlank()) {
            return Path.of(envJarPath.trim());
        }
        return null;
    }

    /**
     * 带 ApplicationHome 兜底的 JAR 定位：参数与环境变量均不可得时，
     * 若 Spring Boot 解析出的运行来源是一个真实存在的 .jar 文件则采用之。
     * IDE 开发模式下来源是 classes 目录（非 .jar 文件），不会误判为可更新。
     */
    static Path detectCurrentJar(String[] args, String envJarPath, Path applicationHomeSource) {
        Path fromArgsOrEnv = detectCurrentJar(args, envJarPath);
        if (fromArgsOrEnv != null) {
            return fromArgsOrEnv;
        }
        if (applicationHomeSource != null
                && Files.isRegularFile(applicationHomeSource)
                && applicationHomeSource.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return applicationHomeSource;
        }
        return null;
    }

    // ============ 状态查询 ============

    public boolean isAvailable() {
        return enabled && currentJarPath != null;
    }

    public String getUnavailableReason() {
        if (enabled && currentJarPath == null) {
            return unavailableReason;
        }
        if (!enabled) {
            return "在线更新功能已在配置中关闭（app.self-update.enabled=false）";
        }
        return null;
    }

    public Status getStatus() {
        return status;
    }

    // ============ 触发更新 ============

    /**
     * 开始更新到目标版本。返回 false 表示未能启动（参数错误或已有更新在进行）。
     */
    public synchronized boolean apply(String targetVersion) {
        if (!isAvailable()) {
            return false;
        }
        if (targetVersion == null || targetVersion.isBlank()) {
            return false;
        }
        if (VersionUtils.compare(targetVersion, currentVersion) <= 0) {
            log.warn("拒绝在线更新：目标版本 {} 不高于当前版本 {}", targetVersion, currentVersion);
            return false;
        }
        String phase = status.phase();
        if (PHASE_DOWNLOADING.equals(phase) || PHASE_VERIFYING.equals(phase)
                || PHASE_SWAPPED.equals(phase) || PHASE_RESTARTING.equals(phase)) {
            log.warn("已有更新进行中（{}），忽略本次请求", phase);
            return false;
        }
        status = new Status(PHASE_DOWNLOADING, 0, 0, 0, targetVersion, null);
        worker.submit(() -> doUpdate(targetVersion));
        return true;
    }

    /**
     * 新包替换完成后执行重启。重启策略见类注释。
     */
    public synchronized boolean restartNow() {
        if (!PHASE_SWAPPED.equals(status.phase())) {
            return false;
        }
        status = new Status(PHASE_RESTARTING, status.percent(), status.downloadedBytes(),
                status.totalBytes(), status.targetVersion(), "服务正在重启，请稍候刷新页面");
        Thread restartThread = new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            String mode = restartMode == null ? "exit" : restartMode.trim().toLowerCase(Locale.ROOT);
            if ("manual".equals(mode)) {
                log.info("重启策略为 manual，等待人工重启服务");
                status = new Status(PHASE_SWAPPED, status.percent(), status.downloadedBytes(),
                        status.totalBytes(), status.targetVersion(), "新包已就绪，请按部署方式手动重启服务");
                return;
            }
            if ("self".equals(mode)) {
                // 进程自拉起：以相同命令行生成新进程（通过 waitForPid 握手等本进程退出后再绑定端口），
                // 本进程随即退出，由新进程加载新包继续服务。适用于无进程管理器的裸部署。
                if (spawnSelfRelaunch()) {
                    log.warn("在线更新完成，已拉起新进程接管服务，当前进程退出: target={}", status.targetVersion());
                    System.exit(0);
                }
                log.error("拉起新进程失败，保持当前进程继续运行（新包已替换，重启后生效）");
                status = new Status(PHASE_SWAPPED, status.percent(), status.downloadedBytes(),
                        status.totalBytes(), status.targetVersion(), "自动拉起新进程失败，新包已就绪，请手动重启服务");
                return;
            }
            if ("command".equals(mode)) {
                // 不执行配置驱动的 shell 命令，避免配置或环境变量被篡改后获得进程权限。
                // 兼容旧配置：按 exit 处理，由 systemd/Docker 等进程管理器重新拉起。
                log.warn("restart-mode=command 已禁用自定义 shell，按 exit 策略退出");
            }
            log.warn("在线更新完成，进程退出以加载新版本: target={}", status.targetVersion());
            System.exit(0);
        }, "self-update-restart");
        restartThread.setDaemon(false);
        restartThread.start();
        return true;
    }

    /** 子进程等待旧进程退出的握手系统属性名。 */
    static final String WAIT_FOR_PID_PROPERTY = "xianyu.restart.waitForPid";
    /** 握手最长等待：超时则继续启动（旧进程大概率已死，仅多等片刻）。 */
    private static final Duration RESTART_HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 以当前 JVM 命令行原样拉起新进程。新进程携带 {@link #WAIT_FOR_PID_PROPERTY}
     * 等待本进程退出后再启动，避免端口竞争；失败返回 false，调用方保持当前进程运行。
     */
    private boolean spawnSelfRelaunch() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String javaCommand = info.command().orElseGet(SelfUpdateService::currentJavaLauncher);
        String[] arguments = info.arguments().orElse(null);
        List<String> command = buildRelaunchCommand(javaCommand, arguments,
                currentJarPath, ProcessHandle.current().pid());
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            Path workDir = currentJarPath.toAbsolutePath().getParent();
            if (workDir != null) {
                builder.directory(workDir.toFile());
            }
            // 沿用当前进程的标准输出/错误（nohup 重定向到的日志文件继续生效）
            builder.inheritIO();
            Process child = builder.start();
            Thread.sleep(500);
            if (!child.isAlive()) {
                log.error("新进程启动后立即退出: exited={}", child.exitValue());
                child.destroyForcibly();
                return false;
            }
            log.warn("新进程已启动: pid={}, command={}", child.pid(), javaCommand);
            return true;
        } catch (Exception e) {
            log.error("拉起新进程失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 组装重启命令：复用当前 JVM 与全部原始参数，注入 waitForPid 握手属性。
     * 原始参数不可得时回退为 {@code -jar <当前JAR>}。
     */
    static List<String> buildRelaunchCommand(String javaCommand, String[] originalArgs,
                                             Path jarPath, long parentPid) {
        List<String> command = new ArrayList<>();
        command.add(javaCommand);
        command.add("-D" + WAIT_FOR_PID_PROPERTY + "=" + parentPid);
        boolean hasArgs = originalArgs != null && originalArgs.length > 0;
        if (hasArgs) {
            for (String arg : originalArgs) {
                command.add(arg);
            }
        } else {
            command.add("-jar");
            command.add(jarPath.toString());
        }
        return command;
    }

    /**
     * 当前 JVM 自身的启动器绝对路径（{@code $JAVA_HOME/bin/java[.exe]}）。
     * Windows 上 {@code ProcessHandle...command()} 也可能拿不到，
     * 回退到裸 {@code "java"} 会依赖 PATH 且可能解析到另一个版本的 JVM，
     * 用 java.home 拼接可保证拉起的就是当前正在运行的这份运行时。
     */
    static String currentJavaLauncher() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String executable = windows ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    /**
     * 自拉起握手：新进程启动最早期等待旧进程完全退出（释放端口）后再继续。
     * 在 main 中于 Spring 启动前调用；属性未设置时立即返回。
     * <p>注意：Windows JDK 的 {@code ProcessHandle.of(pid)} 对非子孙进程返回 empty，
     * 握手在 Windows 上可能形同虚设；但父进程 spawn 后立即退出、Spring 绑定端口在秒级之后，
     * 时序本身已保证安全。Linux（主要部署平台）基于 /proc，握手严格生效。</p>
     */
    public static void awaitRestartHandshake() {
        String pidValue = System.getProperty(WAIT_FOR_PID_PROPERTY);
        if (pidValue == null || pidValue.isBlank()) {
            return;
        }
        long parentPid;
        try {
            parentPid = Long.parseLong(pidValue.trim());
        } catch (NumberFormatException e) {
            return;
        }
        long deadline = System.nanoTime() + RESTART_HANDSHAKE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(parentPid).isEmpty()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.err.println("[restart] 等待旧进程(pid=" + parentPid + ")退出超时(15s)，继续启动");
    }

    // ============ 更新主流程 ============

    private void doUpdate(String targetVersion) {
        Path newJar = currentJarPath.resolveSibling(currentJarPath.getFileName() + ".new");
        try {
            ReleaseAssets assets = withProbedJarSizes(resolveAssets(targetVersion));
            status = withPhase(PHASE_DOWNLOADING);
            long downloaded;
            String actualDigest;
            if (assets.jar() != null) {
                log.info("开始下载新版本: version={}, jarAsset={}", targetVersion, assets.jar().name());
                DownloadResult result = downloadToFile(assets.jar(), newJar, 0, assets.jar().size());
                downloaded = result.bytes();
                actualDigest = result.sha256Hex();
            } else {
                log.info("开始下载新版本(分卷): version={}, parts={}",
                        targetVersion, assets.jarSplitParts().size());
                // 分卷场景：完整 JAR 的摘要按拼接后的文件重新计算（appendParts），
                // 分卷自身的下载通道回退不影响摘要。
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                downloaded = downloadSplitParts(assets.jarSplitParts(), newJar, digest);
                actualDigest = String.format("%064x", new BigInteger(1, digest.digest()));
            }
            log.info("新版本下载完成: bytes={}, sha256={}", downloaded, actualDigest);

            status = withPhase(PHASE_VERIFYING);
            String expected = fetchExpectedSha256(assets);
            if (!expected.equalsIgnoreCase(actualDigest)) {
                Files.deleteIfExists(newJar);
                log.error("新版本 SHA-256 校验失败: expected={}, actual={}", expected, actualDigest);
                status = failed("新版本校验失败，已放弃更新（下载内容与发布校验值不一致）");
                return;
            }

            verifyReleaseSignature(assets, newJar);

            swapJars(newJar);
            status = new Status(PHASE_SWAPPED, 100, downloaded, downloaded, targetVersion,
                    "新版本已就绪，可执行重启");
            log.info("新版本 JAR 替换完成: target={}", targetVersion);
        } catch (Throwable e) {
            // 用 Throwable 收口：OutOfMemoryError 等 Error 若逃出本方法，线程会带任务消亡，
            // 状态机将永久停在 DOWNLOADING/VERIFYING，后续更新全部被"已有更新进行中"拒绝。
            try {
                Files.deleteIfExists(newJar);
            } catch (Exception ignored) {
                // 清理失败不影响失败状态上报
            }
            log.error("在线更新失败: target={}, errorType={}", targetVersion, e.getClass().getSimpleName());
            status = failed(safeUpdateFailureMessage(e));
        }
    }

    /**
     * 分卷下载：逐卷取回独立临时文件，按序拼接进目标文件（同一 SHA-256 摘要流）。
     * 任一分卷失败即中止；临时分卷在结束后无论成败一律清理。
     */
    private long downloadSplitParts(List<ReleaseAsset> parts, Path target, MessageDigest digest) throws Exception {
        long grandTotal = parts.stream().mapToLong(ReleaseAsset::size).sum();
        List<Path> partFiles = new ArrayList<>(parts.size());
        try {
            long cumulative = 0;
            for (ReleaseAsset part : parts) {
                Path partFile = target.resolveSibling(
                        target.getFileName() + "." + part.name() + ".part");
                downloadToFile(part, partFile, cumulative, grandTotal);
                partFiles.add(partFile);
                cumulative += part.size();
            }
            long totalBytes = appendParts(target, partFiles, digest);
            updateProgress(totalBytes, grandTotal);
            return totalBytes;
        } finally {
            for (Path partFile : partFiles) {
                try {
                    Files.deleteIfExists(partFile);
                } catch (Exception ignored) {
                    // 临时分卷清理失败不影响主流程
                }
            }
        }
    }

    /**
     * 将分卷文件按序拼接写入目标文件，同时更新摘要流；返回总字节数。
     */
    static long appendParts(Path target, List<Path> partFiles, MessageDigest digest) throws Exception {
        long totalBytes = 0;
        try (var out = Files.newOutputStream(target)) {
            for (Path partFile : partFiles) {
                try (InputStream in = Files.newInputStream(partFile)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        totalBytes += read;
                    }
                }
            }
        }
        return totalBytes;
    }

    private void swapJars(Path newJar) throws Exception {
        Path backup = currentJarPath.resolveSibling(currentJarPath.getFileName() + ".bak");
        // 用复制而非移动保留当前 JAR：任意时刻 current 位置都有完整可用包，
        // 更新中途断电/进程被杀后进程管理器仍能拉起旧版本。
        Files.copy(currentJarPath, backup, StandardCopyOption.REPLACE_EXISTING);
        try {
            try {
                Files.move(newJar, currentJarPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(newJar, currentJarPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            // 替换失败：立即从备份还原原 JAR，保证服务还能用旧版本重启
            try {
                Files.copy(backup, currentJarPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception restoreError) {
                e.addSuppressed(restoreError);
            }
            throw e;
        }
    }

    /**
     * 启动兜底：历史版本更新时采用"先移走原 JAR"的替换策略，两步之间被杀会在
     * 磁盘上留下"current 缺失、.bak 存在"的状态。此时把备份移回原位，避免服务无法启动。
     */
    private Path recoverFromBackupIfMissing(Path jarPath) {
        if (jarPath == null || Files.exists(jarPath)) {
            return jarPath;
        }
        Path backup = jarPath.resolveSibling(jarPath.getFileName() + ".bak");
        if (Files.exists(backup)) {
            try {
                Files.move(backup, jarPath);
                log.warn("检测到 JAR 缺失，已从上次更新的备份恢复: {}", jarPath);
            } catch (Exception e) {
                log.error("从备份恢复 JAR 失败，请手动将 {} 还原为 {}", backup, jarPath, e);
            }
        }
        return jarPath;
    }

    // ============ 发布资产解析 ============

    record ReleaseAsset(String name, long size, String browserUrl, String apiUrl) {
    }

    /**
     * JAR 分卷附件名后缀（如 {@code xxx.jar.split-00}），按编号升序拼接后等价于完整 JAR。
     * 用于发布超大 JAR 时拆分附件：完整 JAR 的 SHA-256 仍由 {@code xxx.jar.sha256} 承载。
     */
    private static final Pattern SPLIT_PART_PATTERN = Pattern.compile("\\.jar\\.split-(\\d{2,})$");

    private record ReleaseAssets(ReleaseAsset jar, List<ReleaseAsset> jarSplitParts,
                                 ReleaseAsset sha256, ReleaseAsset signature) {
    }

    /**
     * 发布物签名校验：SHA-256 通过后执行。
     * <ul>
     *   <li>强制模式（官方渠道或显式配置）：缺签名/公钥缺失/验签失败一律拒绝更新；</li>
     *   <li>非强制模式（社区渠道）：提供了签名则同样校验，失败也拒绝；未提供则按 SHA-256 放行。</li>
     * </ul>
     */
    private void verifyReleaseSignature(ReleaseAssets assets, Path newJar) throws Exception {
        boolean required = isSignatureRequired();
        if (assets.signature() == null) {
            if (required) {
                throw new SecurityException("官方渠道更新缺少发布物签名文件（.jar.sig），拒绝更新");
            }
            log.warn("目标版本未提供发布物签名（社区渠道），仅按 SHA-256 校验");
            return;
        }
        String publicKey = resolveReleasePublicKey();
        if (publicKey == null) {
            if (required) {
                throw new IllegalStateException("未配置发布签名验证公钥，无法完成官方渠道验签");
            }
            log.warn("未配置发布签名验证公钥，跳过签名校验");
            return;
        }
        Path sigFile = newJar.resolveSibling(newJar.getFileName() + ".sig.download");
        try {
            downloadToFile(assets.signature(), sigFile, 0, 0);
            String signature = ReleaseSignatureVerifier.readSignatureFile(sigFile);
            if (signature == null || !ReleaseSignatureVerifier.verify(newJar, signature, publicKey)) {
                throw new SecurityException("发布物签名校验失败，安装包可能被篡改，已拒绝更新");
            }
            log.info("发布物签名校验通过: asset={}", assets.signature().name());
        } finally {
            Files.deleteIfExists(sigFile);
        }
    }

    boolean isSignatureRequired() {
        String mode = signatureRequiredMode == null ? "auto" : signatureRequiredMode.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(mode)) {
            return true;
        }
        if ("false".equals(mode)) {
            return false;
        }
        String slug = trimGithubSlug(githubRepository);
        return OFFICIAL_UPDATE_SLUG.equalsIgnoreCase(slug);
    }

    private String resolveReleasePublicKey() {
        String env = System.getenv("XIANYU_ASSISTANT_RELEASE_PUBLIC_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return OFFICIAL_RELEASE_PUBLIC_KEY;
    }

    private ReleaseAssets resolveAssets(String targetVersion) throws Exception {
        String tag = "v" + VersionUtils.normalize(targetVersion);
        String slug = trimGithubSlug(githubRepository);
        if (!hasText(slug)) {
            throw new IllegalStateException("未配置 GitHub 更新仓库（app.update-check.github-repository），无法在线更新");
        }
        String apiUrl = GITHUB_API_PREFIX + slug + "/releases/tags/" + tag;
        return requireReleaseAssets(parseAssets(fetchJson(apiUrl, "GitHub")), "GitHub");
    }

    private ReleaseAssets requireReleaseAssets(List<ReleaseAsset> assets, String channel) {
        ReleaseAsset jar = pickAsset(assets, "\\.jar$");
        List<ReleaseAsset> splitParts = jar == null ? pickSplitParts(assets) : null;
        if (jar == null && (splitParts == null || splitParts.isEmpty())) {
            throw new IllegalStateException(channel + " Release 未找到 JAR 发布资产，请确认该标签已上传安装包");
        }
        ReleaseAsset sha256 = pickAsset(assets, "\\.jar\\.sha256$");
        if (sha256 == null) throw new IllegalStateException(channel + " Release 未找到 SHA-256 校验文件，拒绝在线更新");
        ReleaseAsset signature = pickAsset(assets, "\\.jar\\.sig$");
        return new ReleaseAssets(jar, splitParts, sha256, signature);
    }

    /**
     * 发布 API 返回的 assets 可能不含 size 字段，资产 size 为 0 会让下载进度分母为 0（进度条恒 0%）。下载前用 HEAD 请求
     * 跟随重定向探测真实大小；探测失败不阻断更新，仅退化为无百分比进度。
     */
    ReleaseAssets withProbedJarSizes(ReleaseAssets assets) {
        try {
            if (assets.jar() != null) {
                return new ReleaseAssets(withProbedSize(assets.jar()), null, assets.sha256(), assets.signature());
            }
            List<ReleaseAsset> parts = assets.jarSplitParts();
            if (parts == null) {
                return assets;
            }
            List<ReleaseAsset> probed = new ArrayList<>(parts.size());
            for (ReleaseAsset part : parts) {
                probed.add(withProbedSize(part));
            }
            return new ReleaseAssets(null, probed, assets.sha256(), assets.signature());
        } catch (Exception e) {
            log.warn("探测发布资产大小失败，下载进度将无法显示百分比: {}", e.getMessage());
            return assets;
        }
    }

    ReleaseAsset withProbedSize(ReleaseAsset asset) {
        if (asset.size() > 0) {
            return asset;
        }
        try {
            long size = probeContentLength(URI.create(asset.browserUrl()));
            if (size > 0) {
                log.info("发布资产大小探测成功: name={}, size={}", asset.name(), size);
                return new ReleaseAsset(asset.name(), size, asset.browserUrl(), asset.apiUrl());
            }
            log.warn("发布资产大小探测未返回Content-Length: name={}", asset.name());
        } catch (Exception e) {
            log.warn("发布资产大小探测失败: name={}, error={}", asset.name(), e.getMessage());
        }
        return asset;
    }

    private long probeContentLength(URI uri) throws Exception {
        HttpResponse<InputStream> response = openWithRedirects(
                uri, Map.of("Accept", "application/octet-stream"), "HEAD", PROBE_TIMEOUT);
        try (InputStream ignored = response.body()) {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return parseContentLength(response).orElse(0);
        }
    }

    /**
     * 从发布资产中选取完整的 JAR 分卷序列：要求编号从 00 起连续无缺口，
     * 任一通道缺卷即视为不完整而放弃（宁可不更新，也不拼出不完整的 JAR）。
     */
    static List<ReleaseAsset> pickSplitParts(List<ReleaseAsset> assets) {
        if (assets == null || assets.isEmpty()) {
            return null;
        }
        Map<Integer, ReleaseAsset> byIndex = new LinkedHashMap<>();
        for (ReleaseAsset asset : assets) {
            Matcher matcher = SPLIT_PART_PATTERN.matcher(asset.name().toLowerCase(Locale.ROOT));
            if (matcher.find()) {
                byIndex.put(Integer.parseInt(matcher.group(1)), asset);
            }
        }
        if (byIndex.isEmpty() || !byIndex.containsKey(0)) {
            return null;
        }
        List<ReleaseAsset> ordered = new ArrayList<>();
        int index = 0;
        while (byIndex.containsKey(index)) {
            ordered.add(byIndex.get(index));
            index++;
        }
        if (ordered.size() != byIndex.size()) {
            return null;
        }
        return ordered;
    }

    private List<ReleaseAsset> parseAssets(JsonNode release) {
        List<ReleaseAsset> assets = new ArrayList<>();
        JsonNode nodes = release.path("assets");
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                String name = node.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                String browserUrl = node.path("browser_download_url").asText("");
                if (!hasText(browserUrl)) browserUrl = node.path("download_url").asText("");
                assets.add(new ReleaseAsset(
                        name,
                        node.path("size").asLong(0),
                        browserUrl,
                        node.path("url").asText("")));
            }
        }
        return assets;
    }

    private ReleaseAsset pickAsset(List<ReleaseAsset> assets, String nameSuffixRegex) {
        ReleaseAsset best = null;
        for (ReleaseAsset asset : assets) {
            if (asset.name().toLowerCase(Locale.ROOT).matches(".*" + nameSuffixRegex)) {
                if (best == null || asset.size() > best.size()) {
                    best = asset;
                }
            }
        }
        return best;
    }

    // ============ 下载 ============

    /** 下载结果：字节数与本次下载内容（仅本次尝试）的 SHA-256 十六进制值。 */
    private record DownloadResult(long bytes, String sha256Hex) {}

    private DownloadResult downloadToFile(ReleaseAsset asset, Path target,
                                          long progressOffset, long progressTotal) throws Exception {
        // 每次尝试使用全新摘要：镜像/资产 API 中途失败回退时，不能让部分字节
        // 混入下一次尝试的校验值（否则 SHA-256 必然失败，回退机制失效）。
        if (hasText(jarBaseUrl)) {
            String mirrored = jarBaseUrl.trim().replaceAll("/+$", "") + "/" + asset.name();
            try {
                return downloadUri(URI.create(mirrored), defaultHeaders(), target,
                        asset.size(), progressOffset, progressTotal);
            } catch (Exception e) {
                log.warn("镜像地址下载失败，回退官方发布源: {}", e.getMessage());
            }
        }
        // GitHub 公开仓库直接用 browser_download_url 匿名下载
        return downloadUri(URI.create(asset.browserUrl()), defaultHeaders(), target,
                asset.size(), progressOffset, progressTotal);
    }

    private DownloadResult downloadUri(URI startUri, Map<String, String> headers, Path target,
                                       long expectedSize,
                                       long progressOffset, long progressTotal) throws Exception {
        HttpResponse<InputStream> response = openWithRedirects(startUri, headers);
        try (InputStream body = withStallGuard(response, startUri.toString())) {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("下载失败，HTTP " + response.statusCode());
            }
            long total = parseContentLength(response)
                    .orElse(expectedSize > 0 ? expectedSize : 0);
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            long downloaded = 0;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (var out = Files.newOutputStream(tmp)) {
                int read;
                while ((read = body.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    downloaded += read;
                    updateProgress(progressOffset + downloaded, progressTotal);
                }
            }
            if (total > 0 && downloaded != total) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("下载不完整: 期望 " + total + " 字节，实际 " + downloaded);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return new DownloadResult(downloaded, String.format("%064x", new BigInteger(1, digest.digest())));
        }
    }

    /**
     * 给响应体加停滞守护：每次成功读取刷新时间戳，守护线程发现超过
     * {@link #BODY_STALL_TIMEOUT} 无新字节即关闭流，阻塞中的 read 以 IOException 结束，
     * 更新流程得以进入失败清理而不是永久卡死。
     */
    private InputStream withStallGuard(HttpResponse<InputStream> response, String what) {
        StallGuardStream guarded = new StallGuardStream(response.body(), what);
        guarded.startWatching();
        return guarded;
    }

    private final class StallGuardStream extends FilterInputStream {
        private final AtomicLong lastByteAt = new AtomicLong(System.nanoTime());
        private final String what;
        private volatile ScheduledFuture<?> watchdog;

        StallGuardStream(InputStream in, String what) {
            super(in);
            this.what = what;
        }

        void startWatching() {
            watchdog = stallGuard.scheduleWithFixedDelay(this::checkStall, 5, 5, TimeUnit.SECONDS);
        }

        private void checkStall() {
            if (System.nanoTime() - lastByteAt.get() > BODY_STALL_TIMEOUT.toNanos()) {
                ScheduledFuture<?> self = watchdog;
                if (self != null) {
                    self.cancel(false);
                }
                log.error("下载通道无新字节超过 {}s，判定连接停滞并中止: {}",
                        BODY_STALL_TIMEOUT.toSeconds(), what);
                try {
                    close();
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        public int read() throws IOException {
            int read = super.read();
            if (read >= 0) {
                lastByteAt.set(System.nanoTime());
            }
            return read;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) {
                lastByteAt.set(System.nanoTime());
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            ScheduledFuture<?> self = watchdog;
            if (self != null) {
                self.cancel(false);
            }
            super.close();
        }
    }

    private void updateProgress(long downloaded, long total) {
        Status current = status;
        if (!PHASE_DOWNLOADING.equals(current.phase())) {
            return;
        }
        // 每 2MB 或整段结束时刷新一次状态，避免高频可见写
        if (downloaded - current.downloadedBytes() < 2L * 1024 * 1024 && (total <= 0 || downloaded < total)) {
            return;
        }
        int percent = total > 0 ? (int) Math.min(99, downloaded * 100 / total) : 0;
        status = new Status(PHASE_DOWNLOADING, percent, downloaded, total, current.targetVersion(), null);
    }

    /**
     * 手动逐跳跟随重定向：仅允许 HTTPS 与受信下载主机，跨主机时剥离 Authorization。
     */
    private HttpResponse<InputStream> openWithRedirects(URI uri, Map<String, String> headers) throws Exception {
        return openWithRedirects(uri, headers, "GET", DOWNLOAD_TIMEOUT);
    }

    private HttpResponse<InputStream> openWithRedirects(
            URI uri, Map<String, String> headers, String httpMethod, Duration timeout) throws Exception {
        Map<String, String> safeHeaders = new LinkedHashMap<>(headers);
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            requireAllowedDownloadUri(current);
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(timeout)
                    .header("User-Agent", "XianYuAssistant")
                    .method(httpMethod, HttpRequest.BodyPublishers.noBody());
            safeHeaders.forEach(builder::header);
            HttpResponse<InputStream> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int code = response.statusCode();
            if (code >= 300 && code < 400) {
                String location = response.headers().firstValue("Location").orElse("");
                try (InputStream ignored = response.body()) {
                    // 关闭中间响应体
                }
                if (location.isBlank()) {
                    throw new IllegalStateException("重定向缺少 Location 头");
                }
                URI next = current.resolve(location);
                if (!"https".equalsIgnoreCase(next.getScheme())) {
                    throw new IllegalStateException("重定向目标非 HTTPS，已中止");
                }
                if (!sameHost(current, next)) {
                    safeHeaders.remove("Authorization");
                }
                current = next;
                continue;
            }
            return response;
        }
        throw new IllegalStateException("重定向次数过多，已中止下载");
    }

    static void requireAllowedDownloadUri(URI uri) {
        if (uri.getScheme() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new SecurityException("下载地址必须为 HTTPS: " + uri);
        }
        if (uri.getUserInfo() != null) {
            throw new SecurityException("下载地址不允许携带用户信息: " + uri.getHost());
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        // Release 附件可能 302 到 GitHub 官方附件存储网关（如 release-assets.githubusercontent.com），
        // 信任边界为 GitHub 自有域名（含子域，严格标签边界，防 github.com.evil.com 绕过）。
        if (!isTrustedDownloadHost(host)) {
            throw new SecurityException("下载地址主机不受信任: " + host);
        }
    }

    private static boolean isTrustedDownloadHost(String host) {
        return host.equals("github.com") || host.endsWith(".github.com")
                || host.equals("release-assets.githubusercontent.com")
                || host.equals("githubusercontent.com") || host.endsWith(".githubusercontent.com");
    }

    private static void requireAllowedApiUri(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || !"api.github.com".equalsIgnoreCase(uri.getHost())
                || uri.getPath() == null || !uri.getPath().matches("/repos/[^/\\s]+/[^/\\s]+/releases(?:/latest|/tags/[^/\\s]+)")) {
            throw new SecurityException("GitHub API 地址不受信任");
        }
    }


    private static boolean sameHost(URI a, URI b) {
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost());
    }

    private Map<String, String> defaultHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/octet-stream");
        return headers;
    }

    // ============ SHA-256 与 JSON ============

    private String fetchExpectedSha256(ReleaseAssets assets) throws Exception {
        // 优先从 SHA256 资产的文本内容解析；公开 GitHub 资产直接匿名下载
        String url = hasText(assets.sha256().browserUrl())
                ? assets.sha256().browserUrl() : assets.sha256().apiUrl();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/plain, */*");
        HttpResponse<InputStream> response = openWithRedirects(URI.create(url), headers);
        try (InputStream body = withStallGuard(response, "SHA-256 校验文件 " + url)) {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("校验文件下载失败，HTTP " + response.statusCode());
            }
            String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            return parseExpectedSha256(text);
        }
    }

    static String parseExpectedSha256(String text) {
        if (text == null) {
            throw new IllegalStateException("校验文件内容为空");
        }
        Matcher matcher = SHA256_LINE.matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("校验文件中未找到 SHA-256 值");
        }
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    private JsonNode fetchJson(String url, String channel) throws Exception {
        return fetchReleaseJson(url, channel);
    }

    private JsonNode fetchReleaseJson(String url, String channel) throws Exception {
        URI uri = URI.create(url);
        requireAllowedApiUri(uri);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", GITHUB_API_ACCEPT)
                .header("User-Agent", "XianYuAssistant")
                .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .GET();
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(channel + " Release 查询失败，HTTP " + response.statusCode()
                    + (response.statusCode() == 401 || response.statusCode() == 403 ? "（仓库权限或 Release 可见性不足）"
                    : response.statusCode() == 404 ? "（该版本未发布 Release 或仓库不可访问）" : ""));
        }
        return new ObjectMapper().readTree(response.body());
    }

    // ============ 辅助 ============

    private Status withPhase(String phase) {
        Status current = status;
        return new Status(phase, current.percent(), current.downloadedBytes(),
                current.totalBytes(), current.targetVersion(), null);
    }

    private Status failed(String message) {
        return new Status(PHASE_FAILED, 0, 0, 0, status.targetVersion(), message);
    }

    private String safeUpdateFailureMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String detail = cause.getMessage() == null ? "" : cause.getMessage();
        if (detail.contains("权限") || detail.contains("HTTP 401") || detail.contains("HTTP 403")) {
            return "GitHub Release 访问被拒绝，请确认仓库权限或 Release 可见性";
        }
        if (detail.contains("HTTP 404")) return "GitHub Release 或发布资产不存在，请确认目标标签和资产已发布";
        if (detail.contains("JAR 发布资产")) return "GitHub Release 未找到安装包资产，请确认已上传 JAR";
        if (detail.contains("签名") || detail.contains("验签")) return "GitHub Release 发布物签名校验失败，已拒绝更新";
        if (detail.contains("SHA-256") || detail.contains("校验")) return "GitHub Release 校验文件不可用，无法验证安装包完整性";
        if (detail.contains("下载") || detail.contains("连接") || detail.contains("超时")) return "GitHub 安装包下载失败，请检查网络后重试";
        return "在线更新失败，请检查 GitHub Release、资产和网络后重试";
    }

    private java.util.OptionalLong parseContentLength(HttpResponse<?> response) {
        return response.headers().firstValueAsLong("Content-Length");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimGithubSlug(String repository) {
        if (!hasText(repository)) return null;
        String slug = repository.trim()
                .replaceFirst("(?i)^https?://github\\.com/", "")
                .replaceFirst("^/+", "")
                .replaceFirst("\\.git$", "")
                .replaceAll("/+$", "");
        return slug.matches("[^/\\s]+/[^/\\s]+") ? slug : null;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
        stallGuard.shutdownNow();
    }

    /** 不可变状态快照。 */
    public record Status(String phase, int percent, long downloadedBytes, long totalBytes,
                         String targetVersion, String message) {
    }
}
