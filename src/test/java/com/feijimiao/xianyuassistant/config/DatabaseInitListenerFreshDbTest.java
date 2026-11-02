package com.feijimiao.xianyuassistant.config;

import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 全新空库端到端初始化验证：真实 DatabaseInitListener 流程
 * （解析 schema.sql → 先建基础表 → 应用全部版本迁移 → 补字段/索引/触发器）。
 *
 * <p>历史缺陷：旧顺序"先迁移后建表"在全新库上会因迁移语句依赖 schema.sql 的基础表
 * （如 V3_3 写入 xianyu_sys_setting）而中断，导致新装部署首次启动无表可用。
 * 本测试固定新顺序下全新库一次启动即可完成全部初始化，且二次启动幂等。</p>
 */
class DatabaseInitListenerFreshDbTest {

    @TempDir
    Path tempDir;

    private PooledDataSource dataSource;
    private DatabaseInitListener listener;

    @BeforeEach
    void setUp() {
        dataSource = new PooledDataSource();
        dataSource.setDriver("org.sqlite.JDBC");
        // 与生产 JDBC URL 一致启用外键约束
        dataSource.setUrl(String.format("jdbc:sqlite:%s?foreign_keys=on", tempDir.resolve("fresh-init.db")));

        SchemaMigrator schemaMigrator = new SchemaMigrator();
        ReflectionTestUtils.setField(schemaMigrator, "dataSource", dataSource);

        listener = new DatabaseInitListener();
        ReflectionTestUtils.setField(listener, "dataSource", dataSource);
        ReflectionTestUtils.setField(listener, "schemaParser", new SqlSchemaParser());
        ReflectionTestUtils.setField(listener, "schemaMigrator", schemaMigrator);
        ReflectionTestUtils.setField(listener, "environment", new MockEnvironment());
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    private int countSchemaVersions() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM schema_version");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void 全新库一次启动完成全部初始化() throws Exception {
        runInit();

        // 全部迁移版本已记录（首个 V3_1 与最新 V3_22 都在）
        assertThat(countSchemaVersions()).isGreaterThanOrEqualTo(22);
        assertThat(countWhereVersionIs("3_22")).isEqualTo(1);

        // schema.sql 的业务基础表与迁移创建的表都存在
        assertThat(countTablesNamed("xianyu_goods_config")).isEqualTo(1);
        assertThat(countTablesNamed("xianyu_sys_setting")).isEqualTo(1);
        assertThat(countTablesNamed("bargain_session")).isEqualTo(1);

        // V3_22 的议价字段已就位（新库由 schema.sql 建全量列，迁移容错跳过重复列）
        assertThat(countBargainColumns()).isEqualTo(1);

        // V3_6 RBAC 种子数据已写入
        assertThat(countSysRoles()).isGreaterThanOrEqualTo(3);
        assertThat(countSysPermissions()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void 二次启动幂等不重复记版本不报错() throws Exception {
        runInit();
        int versionsAfterFirst = countSchemaVersions();
        assertThat(versionsAfterFirst).isGreaterThanOrEqualTo(22);

        runInit();
        assertThat(countSchemaVersions()).isEqualTo(versionsAfterFirst);
        assertThat(countSysRoles()).isGreaterThanOrEqualTo(3);
    }

    private void runInit() {
        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));
    }

    private int countWhereVersionIs(String version) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM schema_version WHERE version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countTablesNamed(String tableName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countBargainColumns() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM pragma_table_info('xianyu_goods_config') WHERE name = ?")) {
            ps.setString(1, "bargain_on");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countSysRoles() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM sys_role");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int countSysPermissions() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM sys_permission");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
