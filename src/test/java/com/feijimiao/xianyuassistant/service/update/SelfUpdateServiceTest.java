package com.feijimiao.xianyuassistant.service.update;

import com.feijimiao.xianyuassistant.common.VersionUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 在线自更新的纯逻辑单元测试：JAR 定位、校验文件解析、下载主机白名单、状态机守卫。
 */
class SelfUpdateServiceTest {

    private SelfUpdateService newService() {
        return new SelfUpdateService();
    }

    // ============ JAR 定位 ============

    @Test
    void 从jar参数定位当前包() {
        String[] args = {"-Xmx512m", "-jar", "/opt/app/console.jar"};
        assertThat(SelfUpdateService.detectCurrentJar(args, null))
                .isEqualTo(Path.of("/opt/app/console.jar"));
    }

    @Test
    void 无jar参数时回退环境变量() {
        assertThat(SelfUpdateService.detectCurrentJar(new String[]{"-version"}, "/data/app.jar"))
                .isEqualTo(Path.of("/data/app.jar"));
        assertThat(SelfUpdateService.detectCurrentJar(null, "/data/app.jar"))
                .isEqualTo(Path.of("/data/app.jar"));
    }

    @Test
    void 开发模式类路径不误判为可更新() {
        // IDE 运行时 classpath 含分隔符，不能当作单 JAR 路径
        String[] args = {"-cp", "target/classes:/libs/some.jar", "Main"};
        assertThat(SelfUpdateService.detectCurrentJar(args, null)).isNull();
        assertThat(SelfUpdateService.detectCurrentJar(new String[0], null)).isNull();
    }

    @Test
    void 参数与环境变量不可得时回退ApplicationHome来源(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // Windows 上 JVM 常取不到自身启动参数：ApplicationHome 解析出的真实 JAR 文件应被采用
        Path jar = dir.resolve("XianYuSellerConsole-3.5.18.jar");
        java.nio.file.Files.write(jar, new byte[]{1});
        assertThat(SelfUpdateService.detectCurrentJar(new String[0], null, jar)).isEqualTo(jar);
        assertThat(SelfUpdateService.detectCurrentJar(null, null, jar)).isEqualTo(jar);
    }

    @Test
    void ApplicationHome来源为目录或非jar文件时不误判(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // IDE 开发模式：来源是 classes 目录 → 不可更新
        assertThat(SelfUpdateService.detectCurrentJar(new String[0], null, dir)).isNull();
        // 存在的其他非 jar 文件同样不采用
        Path txt = dir.resolve("readme.txt");
        java.nio.file.Files.write(txt, new byte[]{1});
        assertThat(SelfUpdateService.detectCurrentJar(new String[0], null, txt)).isNull();
        assertThat(SelfUpdateService.detectCurrentJar(new String[0], null, null)).isNull();
    }

    @Test
    void 启动参数优先于ApplicationHome来源(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Path homeJar = dir.resolve("from-home.jar");
        java.nio.file.Files.write(homeJar, new byte[]{1});
        String[] args = {"-jar", "/opt/app/console.jar"};
        assertThat(SelfUpdateService.detectCurrentJar(args, null, homeJar))
                .isEqualTo(Path.of("/opt/app/console.jar"));
    }

    @Test
    void 自拉起java命令回退当前运行时() {
        String launcher = SelfUpdateService.currentJavaLauncher();
        assertThat(launcher).isNotBlank();
        // 必须是 java.home/bin 下的 java 可执行文件，而非依赖 PATH 的裸 "java"
        assertThat(launcher).endsWith(System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        assertThat(launcher).contains("bin");
    }

    // ============ restart-mode=self 自拉起 ============

    @Test
    void 自拉起命令复用原始参数并注入握手属性() {
        String[] args = {"-Xms256m", "-Xmx512m", "-jar", "/opt/app/console.jar"};
        var command = SelfUpdateService.buildRelaunchCommand(
                "/usr/bin/java", args, Path.of("/opt/app/console.jar"), 4242);
        assertThat(command).containsExactly(
                "/usr/bin/java",
                "-D" + SelfUpdateService.WAIT_FOR_PID_PROPERTY + "=4242",
                "-Xms256m", "-Xmx512m", "-jar", "/opt/app/console.jar");
    }

    @Test
    void 自拉起命令在原始参数缺失时回退当前jar() {
        Path jarPath = Path.of("/data", "xianyu-seller-console.jar");
        var command = SelfUpdateService.buildRelaunchCommand("java", null, jarPath, 7);
        assertThat(command).containsExactly(
                "java",
                "-D" + SelfUpdateService.WAIT_FOR_PID_PROPERTY + "=7",
                "-jar", jarPath.toString());
    }

    @Test
    void 握手在属性未设置或父进程不存在时立即返回() {
        // 属性未设置：快速通过
        long start = System.nanoTime();
        SelfUpdateService.awaitRestartHandshake();
        assertThat(System.nanoTime() - start).isLessThan(1_000_000_000L);
        // 属性指向不存在的进程：检测到后立即返回
        System.setProperty(SelfUpdateService.WAIT_FOR_PID_PROPERTY, "999999999");
        try {
            start = System.nanoTime();
            SelfUpdateService.awaitRestartHandshake();
            assertThat(System.nanoTime() - start).isLessThan(1_000_000_000L);
        } finally {
            System.clearProperty(SelfUpdateService.WAIT_FOR_PID_PROPERTY);
        }
    }

    // ============ SHA-256 校验文件解析 ============

    @Test
    void 解析标准校验文件内容() {
        String text = "d449fc6ec2aefddb6c8e354c7e2316d146f3718e54714f1dec1f7a615fc75ecc  XianYuSellerConsole-3.3.20.jar";
        assertThat(SelfUpdateService.parseExpectedSha256(text))
                .isEqualTo("d449fc6ec2aefddb6c8e354c7e2316d146f3718e54714f1dec1f7a615fc75ecc");
    }

    @Test
    void 解析带星号前缀的校验内容() {
        String text = "D449FC6EC2AEF DDB6C8E354C7E2316D146F3718E54714F1DEC1F7A615FC75ECC".replace(" ", "")
                + " *app.jar\n其他行";
        String expected = "d449fc6ec2aefddb6c8e354c7e2316d146f3718e54714f1dec1f7a615fc75ecc";
        assertThat(SelfUpdateService.parseExpectedSha256(text)).isEqualTo(expected);
    }

    @Test
    void 非法校验内容应报错() {
        assertThatThrownBy(() -> SelfUpdateService.parseExpectedSha256("no hash here"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SelfUpdateService.parseExpectedSha256(null))
                .isInstanceOf(IllegalStateException.class);
    }

    // ============ 下载主机白名单 ============

    @Test
    void 官方发布主机受信任() {
        SelfUpdateService.requireAllowedDownloadUri(URI.create("https://github.com/a/b/releases/download/v1/x.jar"));
        SelfUpdateService.requireAllowedDownloadUri(URI.create("https://github.com/a/b/releases/download/v1/x.sha256"));
        SelfUpdateService.requireAllowedDownloadUri(URI.create(
                "https://release-assets.githubusercontent.com/github-production-release-asset/123/x.jar"));
        SelfUpdateService.requireAllowedDownloadUri(URI.create(
                "https://objects.githubusercontent.com/github-production-release-asset/123/x.jar"));
    }

    @Test
    void 非https或陌生主机应拒绝() {
        assertThatThrownBy(() -> SelfUpdateService.requireAllowedDownloadUri(
                URI.create("http://downloads.example.com/a/x.jar"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> SelfUpdateService.requireAllowedDownloadUri(
                URI.create("https://evil.example.com/x.jar"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> SelfUpdateService.requireAllowedDownloadUri(
                URI.create("https://user:pass@github.com/x.jar"))).isInstanceOf(SecurityException.class);
        // 子域信任必须是严格标签边界，伪造后缀不得绕过
        assertThatThrownBy(() -> SelfUpdateService.requireAllowedDownloadUri(
                URI.create("https://github.com.evil.com/x.jar"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> SelfUpdateService.requireAllowedDownloadUri(
                URI.create("https://githubusercontent.com.evil.com/x.jar"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> SelfUpdateService.requireAllowedDownloadUri(
                URI.create("https://release-assets.githubusercontent.com.evil.com/signed/x.jar"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> SelfUpdateService.requireAllowedDownloadUri(
                URI.create("https://downloads.github.com.evil.com/signed/x.jar"))).isInstanceOf(SecurityException.class);
    }

    // ============ 状态机守卫 ============

    @Test
    void 非jar部署不可用() {
        // 测试环境（IDE 类进程参数）中未检测到 JAR，且未注入环境变量
        SelfUpdateService service = newService();
        assertThat(service.isAvailable()).isFalse();
        assertThat(service.getUnavailableReason()).isNotBlank();
        assertThat(service.apply("9.9.9")).isFalse();
    }

    @Test
    void 目标版本不高于当前版本应拒绝() {
        SelfUpdateService service = newService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "currentVersion", "3.3.21");
        ReflectionTestUtils.setField(service, "currentJarPath", Path.of("/tmp/console.jar"));

        assertThat(service.isAvailable()).isTrue();
        assertThat(service.apply(null)).isFalse();
        assertThat(service.apply(" ")).isFalse();
        assertThat(service.apply("3.3.20")).isFalse();
        assertThat(service.apply("3.3.21")).isFalse();
    }

    @Test
    void GitHub更新失败原因应可操作() {
        SelfUpdateService service = newService();

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "safeUpdateFailureMessage",
                new IllegalStateException("GitHub Release 查询失败，HTTP 404（该版本未发布 Release 或仓库不可访问）")))
                .isEqualTo("GitHub Release 或发布资产不存在，请确认目标标签和资产已发布");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "safeUpdateFailureMessage",
                new IllegalStateException("GitHub Release 未找到 JAR 发布资产，请确认该标签已上传安装包")))
                .isEqualTo("GitHub Release 未找到安装包资产，请确认已上传 JAR");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "safeUpdateFailureMessage",
                new IllegalStateException("GitHub Release 未找到 SHA-256 校验文件，拒绝在线更新")))
                .isEqualTo("GitHub Release 校验文件不可用，无法验证安装包完整性");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "safeUpdateFailureMessage",
                new SecurityException("发布物签名校验失败，安装包可能被篡改，已拒绝更新")))
                .isEqualTo("GitHub Release 发布物签名校验失败，已拒绝更新");
    }

    @Test
    void 更新进行中应拒绝重复触发() {
        SelfUpdateService service = newService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "currentVersion", "3.3.20");
        ReflectionTestUtils.setField(service, "currentJarPath", Path.of("/tmp/console.jar"));
        ReflectionTestUtils.setField(service, "status",
                new SelfUpdateService.Status(SelfUpdateService.PHASE_DOWNLOADING, 10, 100, 1000, "3.3.21", null));

        assertThat(service.apply("3.3.21")).isFalse();
        assertThat(service.restartNow()).isFalse();
    }

    // ============ 资产大小探测（发布 API 可能无 size 字段） ============

    @Test
    void 已有大小的资产不重复探测() {
        SelfUpdateService service = newService();
        var asset = new SelfUpdateService.ReleaseAsset("app.jar", 99614720L,
                "https://github.com/a/b/releases/download/v1/app.jar", "");
        assertThat(service.withProbedSize(asset)).isSameAs(asset);
    }

    @Test
    void 探测失败时保留原资产且不抛异常() {
        // 127.0.0.1 不在下载主机白名单，探测在发起请求前即被拒绝，必须优雅降级
        SelfUpdateService service = newService();
        var asset = new SelfUpdateService.ReleaseAsset("app.jar", 0,
                "https://127.0.0.1/a/app.jar", "");
        var probed = service.withProbedSize(asset);
        assertThat(probed.size()).isZero();
    }

    /**
     * 真实链路 E2E（需显式开启：-Dselfupdate.e2e=true，CI 与日常测试不执行）：
     * 使用真实 GitHub Release 资产验证「size 探测 → 分卷下载 → SHA-256 校验 → 换包」全流程，
     * 并断言进度分母（totalBytes）非 0 —— 回归 v3.4.7 的「进度条恒 0%」缺陷。
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "selfupdate.e2e", matches = "true")
    void 真实GitHub资产探测下载校验换包全链路(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Path fakeJar = dir.resolve("console.jar");
        java.nio.file.Files.write(fakeJar, new byte[]{0});
        SelfUpdateService service = newService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "currentVersion", "3.4.6");
        ReflectionTestUtils.setField(service, "currentJarPath", fakeJar);
        ReflectionTestUtils.setField(service, "githubRepository", "yuchen0x1/Xianyu-Assistant");
        // 该链路测试下载/校验/换包机制，历史 Release 无签名文件，显式关闭签名强制
        ReflectionTestUtils.setField(service, "signatureRequiredMode", "false");

        assertThat(service.apply("3.4.7")).isTrue();
        long deadline = System.currentTimeMillis() + 15 * 60_000L;
        SelfUpdateService.Status status = service.getStatus();
        while ((SelfUpdateService.PHASE_DOWNLOADING.equals(status.phase())
                || SelfUpdateService.PHASE_VERIFYING.equals(status.phase()))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(2000);
            status = service.getStatus();
        }
        assertThat(status.phase()).isEqualTo(SelfUpdateService.PHASE_SWAPPED);
        assertThat(status.totalBytes()).isPositive();
        assertThat(status.downloadedBytes()).isEqualTo(status.totalBytes());
    }

    // ============ 版本工具 ============

    @Test
    void 版本号归一化与比较() {
        assertThat(VersionUtils.normalize("v3.3.20")).isEqualTo("3.3.20");
        assertThat(VersionUtils.compare("3.3.20", "3.3.19")).isPositive();
        assertThat(VersionUtils.compare("3.10.0", "3.9.9")).isPositive();
        assertThat(VersionUtils.compare("3.3.20", "v3.3.20")).isZero();
        assertThat(VersionUtils.compare("3.3", "3.3.0")).isZero();
        assertThat(VersionUtils.normalize("v3.6.0-beta.2")).isEqualTo("3.6.0-beta.2");
        assertThat(VersionUtils.compare("3.6.0-beta.2", "3.6.0-beta.1")).isPositive();
        assertThat(VersionUtils.compare("3.6.0", "3.6.0-rc.1")).isPositive();
    }

    // ============ JAR 分卷序列选择 ============

    private SelfUpdateService.ReleaseAsset part(String name) {
        return new SelfUpdateService.ReleaseAsset(name, 100, "https://github.com/a/b/releases/download/v1/" + name, "");
    }

    // ============ 发布物签名强制模式 ============

    @Test
    void 签名强制模式按配置与渠道判定() {
        SelfUpdateService service = newService();
        ReflectionTestUtils.setField(service, "githubRepository", "yuchen0x1/Xianyu-Assistant");
        // auto：官方渠道强制
        assertThat(service.isSignatureRequired()).isTrue();
        ReflectionTestUtils.setField(service, "signatureRequiredMode", "false");
        assertThat(service.isSignatureRequired()).isFalse();
        ReflectionTestUtils.setField(service, "signatureRequiredMode", "true");
        assertThat(service.isSignatureRequired()).isTrue();

        // auto：社区渠道（Fork 自建发布源）不强制，保持社区版开箱可用
        ReflectionTestUtils.setField(service, "signatureRequiredMode", "auto");
        ReflectionTestUtils.setField(service, "githubRepository", "someoneElse/xianyu-seller-console");
        assertThat(service.isSignatureRequired()).isFalse();
    }

    @Test
    void 分卷连续时按编号升序返回() {
        var parts = SelfUpdateService.pickSplitParts(java.util.List.of(
                part("app.jar.split-02"), part("app.jar.split-00"),
                part("app.jar.sha256"), part("app.jar.split-01")));
        assertThat(parts).extracting(SelfUpdateService.ReleaseAsset::name)
                .containsExactly("app.jar.split-00", "app.jar.split-01", "app.jar.split-02");
    }

    @Test
    void 分卷缺少起始卷或中间卷视为不完整() {
        assertThat(SelfUpdateService.pickSplitParts(java.util.List.of(
                part("app.jar.split-01"), part("app.jar.split-02")))).isNull();
        assertThat(SelfUpdateService.pickSplitParts(java.util.List.of(
                part("app.jar.split-00"), part("app.jar.split-02")))).isNull();
    }

    @Test
    void 无分卷资产时返回null() {
        assertThat(SelfUpdateService.pickSplitParts(java.util.List.of())).isNull();
        assertThat(SelfUpdateService.pickSplitParts(java.util.List.of(
                part("app.jar"), part("app.jar.sha256")))).isNull();
    }

    // ============ 分卷拼接 ============

    @Test
    void 分卷按序拼接且摘要等于整文件摘要(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        byte[] partA = "hello-".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] partB = "split-world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path fileA = dir.resolve("a.part");
        Path fileB = dir.resolve("b.part");
        java.nio.file.Files.write(fileA, partA);
        java.nio.file.Files.write(fileB, partB);

        java.security.MessageDigest concatDigest = java.security.MessageDigest.getInstance("SHA-256");
        Path target = dir.resolve("app.jar");
        long total = SelfUpdateService.appendParts(target, java.util.List.of(fileA, fileB), concatDigest);

        assertThat(total).isEqualTo(partA.length + partB.length);
        assertThat(java.nio.file.Files.readAllBytes(target))
                .isEqualTo("hello-split-world".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        java.security.MessageDigest wholeDigest = java.security.MessageDigest.getInstance("SHA-256");
        wholeDigest.update("hello-split-world".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(String.format("%064x", new java.math.BigInteger(1, concatDigest.digest())))
                .isEqualTo(String.format("%064x", new java.math.BigInteger(1, wholeDigest.digest())));
    }
}
