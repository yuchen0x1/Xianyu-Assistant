package com.feijimiao.xianyuassistant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web 前端与服务端版本号一致性测试。
 *
 * <p>历史上出现过两次版本漂移事故：`pom.xml` 升版本后，`application.yaml`、
 * `@Value("${app.version:...}")` 兜底值、前端 `__APP_VERSION__` 兜底值仍是旧版本，
 * 导致在线更新检查把「已是最新」误判成「有新版本」并反复提示，或自更新下载了错误产物。</p>
 *
 * <p>本测试以 {@code pom.xml} 为唯一版本源，逐项比对所有硬编码兜底值。</p>
 */
class VersionConsistencyTest {

    private static final Path PROJECT_ROOT = Path.of("");

    @Test
    void javaVersionFallbacksMatchPomVersion() throws IOException {
        String pomVersion = projectVersion();

        List<String> mismatches = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PROJECT_ROOT.resolve("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = Pattern.compile("\\$\\{app\\.version:([^}]+)}").matcher(content);
                while (matcher.find()) {
                    String fallback = matcher.group(1).trim();
                    if (!fallback.equals(pomVersion)) {
                        mismatches.add(file.getFileName() + " 兜底值=" + fallback);
                    }
                }
            }
        }

        assertThat(mismatches)
                .as("Java @Value 的 app.version 兜底值必须与 pom.xml 版本(%s)一致", pomVersion)
                .isEmpty();
    }

    @Test
    void applicationYamlVersionFallbackMatchesPomVersion() throws IOException {
        String pomVersion = projectVersion();
        Path yaml = PROJECT_ROOT.resolve("src/main/resources/application.yaml");
        String content = Files.readString(yaml, StandardCharsets.UTF_8);

        Matcher matcher = Pattern.compile("version:\\s*\\$\\{app\\.version:([^}]+)}").matcher(content);
        assertThat(matcher.find())
                .as("application.yaml 应声明 app.version 兜底值")
                .isTrue();
        assertThat(matcher.group(1).trim())
                .as("application.yaml 的版本兜底值")
                .isEqualTo(pomVersion);
    }

    @Test
    void frontendVersionFallbacksMatchPomVersion() throws IOException {
        String pomVersion = projectVersion();
        Path frontendRoot = PROJECT_ROOT.resolve("vue-code/src");

        List<String> mismatches = new ArrayList<>();
        try (Stream<Path> files = Files.walk(frontendRoot)) {
            for (Path file : files
                    .filter(p -> p.toString().endsWith(".vue") || p.toString().endsWith(".ts"))
                    .toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                // 形如 __APP_VERSION__ || '3.5.x' 或 __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : '3.5.x'
                Matcher matcher = Pattern.compile("__APP_VERSION__[^\\n]{0,80}?'(\\d+\\.\\d+\\.\\d+)'")
                        .matcher(content);
                while (matcher.find()) {
                    if (!matcher.group(1).equals(pomVersion)) {
                        mismatches.add(file.getFileName() + " 兜底值=" + matcher.group(1));
                    }
                }
            }
        }

        assertThat(mismatches)
                .as("前端 __APP_VERSION__ 兜底值必须与 pom.xml 版本(%s)一致", pomVersion)
                .isEmpty();
    }

    /** 读取 pom.xml 的项目版本（不是 parent 版本）。 */
    private static String projectVersion() throws IOException {
        String pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"), StandardCharsets.UTF_8);
        // 项目版本位于 parent 之后的第一个 <version>，parent 块内也有一个 <version>
        Matcher matcher = Pattern.compile(
                "</parent>.*?<version>([^<]+)</version>", Pattern.DOTALL).matcher(pom);
        assertThat(matcher.find()).as("pom.xml 应能解析出项目版本").isTrue();
        return matcher.group(1).trim();
    }

    @Test
    void pomVersionLooksLikeReleaseVersion() throws IOException {
        assertThat(projectVersion()).matches("\\d+\\.\\d+\\.\\d+");
    }
}
