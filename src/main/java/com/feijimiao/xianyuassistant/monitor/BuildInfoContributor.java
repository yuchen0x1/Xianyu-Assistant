package com.feijimiao.xianyuassistant.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 把构建期生成的 {@code version.json} 暴露到 {@code /actuator/info}。
 *
 * <p>{@code version.json} 由 Maven 资源过滤生成（{@code version} + {@code buildTime}），
 * 但此前没有任何代码读取它，等于构建元数据白打进了包。运维排查「当前跑的是哪次构建」
 * 需要 {@code buildTime}，而 {@code info.app.version} 只有版本号。</p>
 *
 * <p>该端点无需登录即可访问，因此只输出版本与构建时间两个字段，不涉及任何运行配置。</p>
 */
@Slf4j
@Component
public class BuildInfoContributor implements InfoContributor {

    private static final String RESOURCE = "version.json";

    private final String version;
    private final String buildTime;

    public BuildInfoContributor() {
        String resolvedVersion = "unknown";
        String resolvedBuildTime = "unknown";
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String parsedVersion = extract(json, "version");
            String parsedBuildTime = extract(json, "buildTime");
            if (parsedVersion != null) resolvedVersion = parsedVersion;
            if (parsedBuildTime != null) resolvedBuildTime = parsedBuildTime;
        } catch (Exception e) {
            // 资源缺失或未经过滤（如 IDE 直跑）时降级，不影响启动
            log.debug("读取 {} 失败，构建信息降级为 unknown: {}", RESOURCE, e.getMessage());
        }
        this.version = resolvedVersion;
        this.buildTime = resolvedBuildTime;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("build", java.util.Map.of(
                "version", version,
                "time", buildTime));
    }

    public String getVersion() {
        return version;
    }

    public String getBuildTime() {
        return buildTime;
    }

    /** 极简 JSON 字符串取值：该文件只有两个扁平字符串字段，不引入额外依赖。 */
    private static String extract(String json, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}