package com.feijimiao.xianyuassistant.backup.handler;

import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoDeliveryBackupHandlerTest {

    @TempDir
    Path tempDir;

    @Mock
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    @Mock
    private XianyuAccountMapper accountMapper;
    @Mock
    private XianyuKamiConfigMapper kamiConfigMapper;

    private JdbcTemplate jdbc;
    private AutoDeliveryBackupHandler handler;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("auto-delivery-backup.db"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE xianyu_account (id INTEGER PRIMARY KEY, unb TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE xianyu_goods_config (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    xianyu_account_id BIGINT NOT NULL,
                    xy_goods_id TEXT NOT NULL,
                    xianyu_auto_delivery_on INTEGER,
                    UNIQUE(xianyu_account_id, xy_goods_id)
                )
                """);
        jdbc.update("INSERT INTO xianyu_account(id, unb) VALUES (7, 'account-1')");
        jdbc.update("""
                INSERT INTO xianyu_goods_config
                    (xianyu_account_id, xy_goods_id, xianyu_auto_delivery_on)
                VALUES (7, 'goods-1', 1)
                """);

        handler = new AutoDeliveryBackupHandler();
        ReflectionTestUtils.setField(handler, "autoDeliveryConfigMapper", autoDeliveryConfigMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(handler, "kamiConfigMapper", kamiConfigMapper);
        ReflectionTestUtils.setField(handler, "jdbcTemplate", jdbc);
    }

    @Test
    void versionTwoRoundTripRestoresGlobalSwitchAndRagDelay() {
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        account.setUnb("account-1");
        XianyuGoodsAutoDeliveryConfig existing = new XianyuGoodsAutoDeliveryConfig();
        existing.setId(50L);
        existing.setXianyuAccountId(7L);
        existing.setXyGoodsId("goods-1");
        existing.setRagDelaySeconds(37);
        when(autoDeliveryConfigMapper.selectList(null)).thenReturn(List.of(existing));
        when(accountMapper.selectById(7L)).thenReturn(account);

        Map<String, Object> exported = handler.exportData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configs =
                (List<Map<String, Object>>) exported.get("autoDeliveryConfigs");
        assertThat(configs).singleElement().satisfies(config ->
                assertThat(config.get("ragDelaySeconds")).isEqualTo(37));

        jdbc.update("UPDATE xianyu_goods_config SET xianyu_auto_delivery_on = 0");
        when(autoDeliveryConfigMapper.selectOne(any())).thenReturn(existing);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("account-1", 7L));
        context.put(DataBackupHandler.BACKUP_VERSION_CONTEXT_KEY, "2.0");
        context.put(DataBackupHandler.SELECTED_MODULES_CONTEXT_KEY, java.util.Set.of("autoDelivery"));
        handler.importData(exported, context);

        assertThat(jdbc.queryForObject(
                "SELECT xianyu_auto_delivery_on FROM xianyu_goods_config", Integer.class)).isEqualTo(1);
        ArgumentCaptor<XianyuGoodsAutoDeliveryConfig> captor =
                ArgumentCaptor.forClass(XianyuGoodsAutoDeliveryConfig.class);
        verify(autoDeliveryConfigMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRagDelaySeconds()).isEqualTo(37);
    }
}
