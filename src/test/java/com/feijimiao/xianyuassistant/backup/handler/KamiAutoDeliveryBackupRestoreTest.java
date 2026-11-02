package com.feijimiao.xianyuassistant.backup.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig;
import com.feijimiao.xianyuassistant.entity.XianyuKamiConfig;
import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiItemMapper;
import com.feijimiao.xianyuassistant.service.bo.BackupImportReqBO;
import com.feijimiao.xianyuassistant.service.impl.DataBackupServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KamiAutoDeliveryBackupRestoreTest {

    @Mock
    private XianyuKamiConfigMapper kamiConfigMapper;

    @Mock
    private XianyuKamiItemMapper kamiItemMapper;

    @Mock
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;

    @Mock
    private XianyuAccountMapper accountMapper;

    private KamiBackupHandler kamiHandler;
    private AutoDeliveryBackupHandler autoDeliveryHandler;

    @BeforeEach
    void setUp() {
        kamiHandler = new KamiBackupHandler();
        ReflectionTestUtils.setField(kamiHandler, "kamiConfigMapper", kamiConfigMapper);
        ReflectionTestUtils.setField(kamiHandler, "kamiItemMapper", kamiItemMapper);
        ReflectionTestUtils.setField(kamiHandler, "accountMapper", accountMapper);

        autoDeliveryHandler = new AutoDeliveryBackupHandler();
        ReflectionTestUtils.setField(autoDeliveryHandler, "autoDeliveryConfigMapper", autoDeliveryConfigMapper);
        ReflectionTestUtils.setField(autoDeliveryHandler, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(autoDeliveryHandler, "kamiConfigMapper", kamiConfigMapper);
    }

    @Test
    void serviceRestoresKamiBeforeAutoDeliveryAndRemapsDriftedIds() throws Exception {
        XianyuAccount account = account(20L, "account-1");
        when(accountMapper.selectList(null)).thenReturn(List.of(account));
        when(kamiConfigMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            XianyuKamiConfig config = invocation.getArgument(0);
            config.setId(101L);
            return 1;
        }).when(kamiConfigMapper).insert(any(XianyuKamiConfig.class));
        when(kamiConfigMapper.selectById(101L)).thenReturn(kamiConfig(101L));
        when(autoDeliveryConfigMapper.selectOne(any())).thenReturn(null);

        DataBackupServiceImpl service = new DataBackupServiceImpl();
        ReflectionTestUtils.setField(service, "handlers", List.of(autoDeliveryHandler, kamiHandler));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);

        Map<String, Object> modules = new LinkedHashMap<>();
        modules.put("autoDelivery", Map.of("autoDeliveryConfigs", List.of(Map.of(
                "unb", "account-1",
                "xyGoodsId", "goods-1",
                "kamiConfigIds", "7"
        ))));
        modules.put("kami", Map.of(
                "kamiConfigs", List.of(Map.of(
                        "configId", 7,
                        "unb", "account-1",
                        "aliasName", "warehouse-1"
                )),
                "kamiItems", List.of()
        ));

        service.importData(importRequest(modules));

        ArgumentCaptor<XianyuGoodsAutoDeliveryConfig> deliveryCaptor =
                ArgumentCaptor.forClass(XianyuGoodsAutoDeliveryConfig.class);
        verify(autoDeliveryConfigMapper).insert(deliveryCaptor.capture());
        assertEquals("101", deliveryCaptor.getValue().getKamiConfigIds());

        InOrder importOrder = inOrder(kamiConfigMapper, autoDeliveryConfigMapper);
        importOrder.verify(kamiConfigMapper).insert(any(XianyuKamiConfig.class));
        importOrder.verify(autoDeliveryConfigMapper).insert(any(XianyuGoodsAutoDeliveryConfig.class));
    }

    @Test
    void autoDeliveryFailsWhenAnyKamiConfigIdHasNoMapping() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("account-1", 20L));
        context.put(KamiBackupHandler.CONFIG_ID_MAPPING_CONTEXT_KEY, Map.of(8L, 108L));
        Map<String, Object> data = Map.of("autoDeliveryConfigs", List.of(Map.of(
                "unb", "account-1",
                "xyGoodsId", "goods-1",
                "kamiConfigIds", "7,8"
        )));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> autoDeliveryHandler.importData(data, context));

        assertTrue(error.getCause().getMessage().contains("卡密配置 7"));
        assertTrue(error.getCause().getMessage().contains("请同时导入卡密仓库模块"));
        verify(autoDeliveryConfigMapper, never()).insert(any(XianyuGoodsAutoDeliveryConfig.class));
        verify(autoDeliveryConfigMapper, never()).updateById(any(XianyuGoodsAutoDeliveryConfig.class));
    }

    @Test
    void autoDeliveryOnlyRestoreKeepsAnExactConfigIdOwnedByTheSameAccount() {
        XianyuKamiConfig existingConfig = kamiConfig(7L);
        existingConfig.setXianyuAccountId(20L);
        when(kamiConfigMapper.selectById(7L)).thenReturn(existingConfig);
        when(autoDeliveryConfigMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("account-1", 20L));
        context.put(DataBackupHandler.BACKUP_VERSION_CONTEXT_KEY, "2.0");
        context.put(DataBackupHandler.SELECTED_MODULES_CONTEXT_KEY, Set.of("autoDelivery"));
        Map<String, Object> data = Map.of("autoDeliveryConfigs", List.of(mapWithNullValues(
                "unb", "account-1",
                "xyGoodsId", "goods-1",
                "kamiConfigIds", "7",
                "ragDelaySeconds", 18
        )));

        autoDeliveryHandler.importData(data, context);

        ArgumentCaptor<XianyuGoodsAutoDeliveryConfig> captor =
                ArgumentCaptor.forClass(XianyuGoodsAutoDeliveryConfig.class);
        verify(autoDeliveryConfigMapper).insert(captor.capture());
        assertEquals("7", captor.getValue().getKamiConfigIds());
        assertEquals(18, captor.getValue().getRagDelaySeconds());
    }

    @Test
    void autoDeliveryOnlyRestoreRejectsAnExactConfigIdOwnedByAnotherAccount() {
        XianyuKamiConfig foreignConfig = kamiConfig(7L);
        foreignConfig.setXianyuAccountId(99L);
        when(kamiConfigMapper.selectById(7L)).thenReturn(foreignConfig);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("account-1", 20L));
        context.put(DataBackupHandler.BACKUP_VERSION_CONTEXT_KEY, "2.0");
        context.put(DataBackupHandler.SELECTED_MODULES_CONTEXT_KEY, Set.of("autoDelivery"));
        Map<String, Object> data = Map.of("autoDeliveryConfigs", List.of(mapWithNullValues(
                "unb", "account-1",
                "xyGoodsId", "goods-1",
                "kamiConfigIds", "7",
                "ragDelaySeconds", 18
        )));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> autoDeliveryHandler.importData(data, context));

        assertTrue(error.getCause().getMessage().contains("不属于当前账号"));
        verify(autoDeliveryConfigMapper, never()).insert(any(XianyuGoodsAutoDeliveryConfig.class));
        verify(autoDeliveryConfigMapper, never()).updateById(any(XianyuGoodsAutoDeliveryConfig.class));
    }

    @Test
    void versionOneExplainsWhyKamiReferencesCannotBeGuessed() {
        XianyuKamiConfig coincidentalConfig = kamiConfig(7L);
        coincidentalConfig.setXianyuAccountId(20L);
        when(kamiConfigMapper.selectById(7L)).thenReturn(coincidentalConfig);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("account-1", 20L));
        context.put(DataBackupHandler.BACKUP_VERSION_CONTEXT_KEY, "1.0");
        context.put(DataBackupHandler.SELECTED_MODULES_CONTEXT_KEY, Set.of("kami", "autoDelivery"));
        context.put(KamiBackupHandler.CONFIG_ID_MAPPING_CONTEXT_KEY, Map.of());
        Map<String, Object> data = Map.of("autoDeliveryConfigs", List.of(Map.of(
                "unb", "account-1",
                "xyGoodsId", "goods-1",
                "kamiConfigIds", "7"
        )));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> autoDeliveryHandler.importData(data, context));

        assertTrue(error.getCause().getMessage().contains("旧版1.0"));
        assertTrue(error.getCause().getMessage().contains("重新导出2.0"));
    }

    @Test
    void versionOneMayKeepAnExactOwnedConfigThatExistedBeforeKamiImport() {
        XianyuKamiConfig existingConfig = kamiConfig(7L);
        existingConfig.setXianyuAccountId(20L);
        when(kamiConfigMapper.selectById(7L)).thenReturn(existingConfig);
        when(autoDeliveryConfigMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("account-1", 20L));
        context.put(DataBackupHandler.BACKUP_VERSION_CONTEXT_KEY, "1.0");
        context.put(DataBackupHandler.SELECTED_MODULES_CONTEXT_KEY, Set.of("kami", "autoDelivery"));
        context.put(KamiBackupHandler.CONFIG_ID_MAPPING_CONTEXT_KEY, Map.of());
        context.put(KamiBackupHandler.PREEXISTING_CONFIG_IDS_CONTEXT_KEY, Set.of(7L));
        Map<String, Object> data = Map.of("autoDeliveryConfigs", List.of(Map.of(
                "unb", "account-1",
                "xyGoodsId", "goods-1",
                "kamiConfigIds", "7"
        )));

        autoDeliveryHandler.importData(data, context);

        ArgumentCaptor<XianyuGoodsAutoDeliveryConfig> captor =
                ArgumentCaptor.forClass(XianyuGoodsAutoDeliveryConfig.class);
        verify(autoDeliveryConfigMapper).insert(captor.capture());
        assertEquals("7", captor.getValue().getKamiConfigIds());
    }

    private Map<String, Object> mapWithNullValues(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            map.put((String) values[index], values[index + 1]);
        }
        return map;
    }

    @Test
    void kamiImportRefreshesCountsFromDatabaseAfterItemsAreWritten() {
        when(kamiConfigMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            XianyuKamiConfig config = invocation.getArgument(0);
            config.setId(101L);
            return 1;
        }).when(kamiConfigMapper).insert(any(XianyuKamiConfig.class));
        when(kamiItemMapper.selectOne(any())).thenReturn(null);
        when(kamiConfigMapper.selectById(101L)).thenReturn(kamiConfig(101L));
        when(kamiItemMapper.countByConfigId(101L)).thenReturn(4);
        when(kamiItemMapper.countUsed(101L)).thenReturn(2);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("account-1", 20L));
        Map<String, Object> data = Map.of(
                "kamiConfigs", List.of(Map.of(
                        "configId", 7,
                        "unb", "account-1",
                        "aliasName", "warehouse-1"
                )),
                "kamiItems", List.of(Map.of(
                        "unb", "account-1",
                        "aliasName", "warehouse-1",
                        "kamiContent", "CARD-001"
                ))
        );

        kamiHandler.importData(data, context);

        InOrder countOrder = inOrder(kamiItemMapper);
        countOrder.verify(kamiItemMapper).insert(any(XianyuKamiItem.class));
        countOrder.verify(kamiItemMapper).countByConfigId(101L);
        countOrder.verify(kamiItemMapper).countUsed(101L);

        ArgumentCaptor<XianyuKamiConfig> configCaptor = ArgumentCaptor.forClass(XianyuKamiConfig.class);
        verify(kamiConfigMapper).updateById(configCaptor.capture());
        assertEquals(4, configCaptor.getValue().getTotalCount());
        assertEquals(2, configCaptor.getValue().getUsedCount());

        @SuppressWarnings("unchecked")
        Map<Long, Long> mapping = (Map<Long, Long>) context.get(KamiBackupHandler.CONFIG_ID_MAPPING_CONTEXT_KEY);
        assertEquals(101L, mapping.get(7L));
    }

    @Test
    void kamiExportIncludesSourceConfigIdNeededForReferenceRemapping() {
        XianyuKamiConfig config = kamiConfig(7L);
        config.setXianyuAccountId(20L);
        config.setAliasName("warehouse-1");
        when(kamiConfigMapper.selectList(null)).thenReturn(List.of(config));
        when(accountMapper.selectById(20L)).thenReturn(account(20L, "account-1"));
        when(kamiItemMapper.findByConfigIdAndStatus(7L, 0)).thenReturn(List.of());

        Map<String, Object> exported = kamiHandler.exportData();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configs = (List<Map<String, Object>>) exported.get("kamiConfigs");
        assertEquals(7L, configs.get(0).get("configId"));
    }

    @Test
    void versionTwoKamiImportRejectsMissingSourceConfigId() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("account-1", 20L));
        context.put(DataBackupHandler.BACKUP_VERSION_CONTEXT_KEY, "2.0");
        Map<String, Object> data = Map.of(
                "kamiConfigs", List.of(Map.of(
                        "unb", "account-1",
                        "aliasName", "warehouse-1"
                )),
                "kamiItems", List.of()
        );

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> kamiHandler.importData(data, context));

        assertTrue(error.getCause().getMessage().contains("缺少configId"));
        verify(kamiConfigMapper, never()).insert(any(XianyuKamiConfig.class));
    }

    private BackupImportReqBO importRequest(Map<String, Object> modules) throws Exception {
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("version", "1.0");
        backup.put("modules", modules);

        BackupImportReqBO request = new BackupImportReqBO();
        request.setJsonData(new ObjectMapper().writeValueAsString(backup));
        return request;
    }

    private XianyuAccount account(Long id, String unb) {
        XianyuAccount account = new XianyuAccount();
        account.setId(id);
        account.setUnb(unb);
        return account;
    }

    private XianyuKamiConfig kamiConfig(Long id) {
        XianyuKamiConfig config = new XianyuKamiConfig();
        config.setId(id);
        config.setTotalCount(0);
        config.setUsedCount(0);
        return config;
    }
}
