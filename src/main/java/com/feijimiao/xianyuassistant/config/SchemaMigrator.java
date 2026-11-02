package com.feijimiao.xianyuassistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据库 Schema 版本化迁移执行器。
 *
 * <p>迁移脚本约定：{@code db/migration/V{版本}__{描述}.sql}（如 {@code V3_1__publish_history.sql}）。
 * 启动时扫描 classpath 下的迁移脚本，对照 {@code schema_version} 表按版本升序应用尚未执行的部分，
 * 每个迁移在一个事务内完成并记录版本与内容校验和。</p>
 *
 * <p>回退约定：回退代码后迁移器只会应用“尚未记录”的脚本；已记录的版本会被跳过，
 * 因此回退时需配合数据库备份还原（见 docs/升级方案-v3.1）。</p>
 */
@Slf4j
@Component
public class SchemaMigrator {

    private static final String MIGRATION_LOCATION = "classpath:db/migration/*.sql";
    private static final String VERSION_PATTERN = "^V(\\d+_\\d+)__(.+)\\.sql$";

    @Autowired
    private DataSource dataSource;

    /**
     * 应用所有未执行的迁移脚本。幂等，可在每次启动时安全调用。
     */
    public void migrate() {
        try (var conn = dataSource.getConnection()) {
            ensureVersionTable(conn);
            List<MigrationScript> scripts = loadScripts();
            if (scripts.isEmpty()) {
                log.info("未发现数据库迁移脚本");
                return;
            }
            Map<String, String> applied = loadAppliedMigrations(conn);
            int appliedCount = 0;
            for (MigrationScript script : scripts) {
                if (applied.containsKey(script.version)) {
                    String storedChecksum = applied.get(script.version);
                    if (storedChecksum == null || !storedChecksum.equalsIgnoreCase(script.checksum)) {
                        throw new IllegalStateException("迁移校验和不一致: V" + script.version
                                + "（数据库记录与当前脚本不同，已阻止启动）");
                    }
                    log.debug("迁移已应用且校验通过，跳过: V{}__{}", script.version, script.description);
                    continue;
                }
                log.info("执行数据库迁移: V{}__{}", script.version, script.description);
                conn.setAutoCommit(false);
                try (var stmt = conn.createStatement()) {
                    for (String sql : splitStatements(script.content)) {
                        executeStatement(conn, stmt, sql);
                    }
                    try (var ps = conn.prepareStatement(
                            "INSERT INTO schema_version(version, description, checksum) VALUES(?, ?, ?)")) {
                        ps.setString(1, script.version);
                        ps.setString(2, script.description);
                        ps.setString(3, script.checksum);
                        ps.executeUpdate();
                    }
                    conn.commit();
                    appliedCount++;
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
            log.info("数据库迁移完成，本次应用 {} 个迁移", appliedCount);
        } catch (Exception e) {
            throw new IllegalStateException("数据库迁移失败", e);
        }
    }

    /**
     * SQLite 不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS。
     * 对迁移重试时已经存在的字段做精确兼容，其他 SQL 错误继续抛出。
     */
    private void executeStatement(java.sql.Connection conn, java.sql.Statement stmt, String sql) throws Exception {
        try {
            stmt.execute(sql);
        } catch (SQLException e) {
            AddColumnStatement addColumn = parseAddColumnStatement(sql);
            if (!isDuplicateColumnError(e) || addColumn == null
                    || !columnExists(conn, addColumn.tableName(), addColumn.columnName())) {
                throw e;
            }
            log.warn("迁移字段已存在，跳过重复添加: table={}, column={}",
                    addColumn.tableName(), addColumn.columnName());
        }
    }

    private boolean isDuplicateColumnError(SQLException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("duplicate column name");
    }

    private AddColumnStatement parseAddColumnStatement(String sql) {
        String normalized = sql == null ? "" : sql.strip().replaceAll(";+$", "").replaceAll("\\s+", " ");
        Matcher matcher = Pattern.compile(
                "(?i)^ALTER TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+ADD COLUMN\\s+([A-Za-z_][A-Za-z0-9_]*)\\b")
                .matcher(normalized);
        return matcher.find() ? new AddColumnStatement(matcher.group(1), matcher.group(2)) : null;
    }

    private boolean columnExists(java.sql.Connection conn, String tableName, String columnName) throws SQLException {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("PRAGMA table_info(\"" + tableName + "\")")) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private record AddColumnStatement(String tableName, String columnName) {
    }

    private void ensureVersionTable(java.sql.Connection conn) throws Exception {
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version TEXT PRIMARY KEY,
                        description TEXT,
                        checksum TEXT,
                        applied_at DATETIME DEFAULT (datetime('now', 'localtime'))
                    )
                    """);
        }
    }

    private Map<String, String> loadAppliedMigrations(java.sql.Connection conn) throws Exception {
        Map<String, String> applied = new LinkedHashMap<>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT version, checksum FROM schema_version")) {
            while (rs.next()) {
                applied.put(rs.getString("version"), rs.getString("checksum"));
            }
        }
        return applied;
    }

    private List<MigrationScript> loadScripts() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(MIGRATION_LOCATION);
        List<MigrationScript> scripts = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(VERSION_PATTERN);
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) {
                continue;
            }
            java.util.regex.Matcher matcher = pattern.matcher(filename);
            if (!matcher.matches()) {
                log.warn("忽略不符合命名规范的迁移脚本: {}", filename);
                continue;
            }
            String content;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                content = sb.toString();
            }
            scripts.add(new MigrationScript(matcher.group(1), matcher.group(2), content, checksum(content)));
        }
        scripts.sort(Comparator.comparing(MigrationScript::version, SchemaMigrator::compareVersion));
        return scripts;
    }

    private static int compareVersion(String v1, String v2) {
        String[] p1 = v1.split("_");
        String[] p2 = v2.split("_");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < p1.length ? Integer.parseInt(p1[i]) : 0;
            int n2 = i < p2.length ? Integer.parseInt(p2[i]) : 0;
            if (n1 != n2) {
                return Integer.compare(n1, n2);
            }
        }
        return 0;
    }

    /**
     * 按分号拆分 SQL，忽略行注释与空语句；识别 CREATE TRIGGER ... BEGIN ... END 块，避免块内分号被误切。
     */
    private List<String> splitStatements(String content) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inBlock = false;
        for (String line : content.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.equals("BEGIN")) {
                inBlock = true;
            } else if (inBlock && (upper.equals("END") || upper.equals("END;") || upper.startsWith("END "))) {
                inBlock = false;
            }
            current.append(line).append('\n');
            if (!inBlock && trimmed.endsWith(";")) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        String rest = current.toString().strip();
        if (!rest.isEmpty()) {
            statements.add(rest + ";");
        }
        return statements;
    }

    private String checksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private record MigrationScript(String version, String description, String content, String checksum) {
    }
}
