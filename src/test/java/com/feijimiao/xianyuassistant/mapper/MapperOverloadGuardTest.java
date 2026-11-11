package com.feijimiao.xianyuassistant.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis Mapper 方法重载防腐测试。
 *
 * <p>MyBatis 的语句 ID 是「接口全限定名 + 方法名」，不含参数签名。因此同名不同参的方法
 * 会互相覆盖：后注册的那个被 {@code MybatisConfiguration} 静默丢弃，启动日志只留一行
 * {@code is ignored, because it exists}，业务侧照常调用、照常返回数据 —— 但执行的可能是
 * 另一条 SQL。历史缺陷正是如此：带 {@code DataPanelAccountScope} 的账号范围过滤版本被
 * 无范围版本覆盖，导致非管理员看到全部账号的数据。</p>
 *
 * <p>本测试从源码目录扫描 {@code *Mapper.java} 再反射加载，避免组件扫描找不到接口的问题；
 * 不依赖数据库，是可最早发现该问题的关卡。</p>
 */
class MapperOverloadGuardTest {

    private static final Path MAPPER_SOURCE_DIR =
            Path.of("src", "main", "java", "com", "feijimiao", "xianyuassistant", "mapper");

    @Test
    void mapperInterfacesMustNotOverloadMethodNames() throws Exception {
        assertThat(MAPPER_SOURCE_DIR)
                .as("Mapper 源码目录应存在（本测试依赖工作目录为项目根）")
                .exists();

        List<Class<?>> mapperInterfaces = new ArrayList<>();
        try (Stream<Path> files = Files.list(MAPPER_SOURCE_DIR)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("Mapper.java")).toList()) {
                String fileName = file.getFileName().toString();
                String simpleName = fileName.substring(0, fileName.length() - ".java".length());
                Class<?> type = Class.forName("com.feijimiao.xianyuassistant.mapper." + simpleName);
                assertThat(type.isInterface())
                        .as(simpleName + " 应为接口")
                        .isTrue();
                mapperInterfaces.add(type);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法扫描 Mapper 源码目录", e);
        }

        assertThat(mapperInterfaces)
                .as("应能扫描到 Mapper 接口（否则本测试失去意义）")
                .hasSizeGreaterThan(10);

        List<String> violations = new ArrayList<>();
        for (Class<?> mapper : mapperInterfaces) {
            Map<String, Integer> seen = new HashMap<>();
            for (Method method : mapper.getDeclaredMethods()) {
                // 桥接/合成方法由编译器生成，不参与 MyBatis 语句注册
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                seen.merge(method.getName(), 1, Integer::sum);
            }
            seen.forEach((name, count) -> {
                if (count > 1) {
                    violations.add(mapper.getSimpleName() + "#" + name + " 重载 " + count + " 次");
                }
            });
        }

        assertThat(violations)
                .as("""
                        Mapper 方法名重复会让 MyBatis 静默丢弃后注册的语句（含账号范围过滤），
                        请改为不同方法名，例如带范围的查询用 ...Scoped 后缀：
                        """)
                .isEmpty();
    }
}
