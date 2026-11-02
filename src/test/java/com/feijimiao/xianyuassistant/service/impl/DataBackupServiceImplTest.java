package com.feijimiao.xianyuassistant.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.service.DataBackupService;
import com.feijimiao.xianyuassistant.service.bo.BackupExportReqBO;
import com.feijimiao.xianyuassistant.service.bo.BackupImportReqBO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataBackupServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    private DataBackupHandler accountHandler;

    @Mock
    private DataBackupHandler goodsHandler;

    @Mock
    private XianyuAccountMapper accountMapper;

    private DataBackupServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DataBackupServiceImpl();
        ReflectionTestUtils.setField(service, "handlers", List.of(accountHandler));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
    }

    @Test
    void importDataPropagatesModuleFailureSoTransactionCanRollBack() {
        when(accountHandler.getModuleKey()).thenReturn("account");
        when(accountHandler.getRequiredImportListKeys()).thenReturn(Set.of("accounts", "cookies"));
        BackupImportReqBO request = request("1.0", Map.of("account", Map.of(
                "accounts", List.of(),
                "cookies", List.of()
        )));
        doThrow(new IllegalStateException("write failed"))
                .when(accountHandler).importData(anyMap(), anyMap());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.importData(request));

        assertTrue(error.getMessage().contains("account"));
    }

    @Test
    void exportDataFailsAsAWholeWhenAnySelectedModuleFails() {
        when(accountHandler.getModuleKey()).thenReturn("account");
        when(accountHandler.exportData(anyMap())).thenThrow(new IllegalStateException("database unavailable"));
        BackupExportReqBO request = new BackupExportReqBO();
        request.setModules(List.of("account"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.exportData(request));

        assertTrue(error.getMessage().contains("account"));
        assertEquals("database unavailable", error.getCause().getMessage());
    }

    @Test
    void exportDataDeclaresVersionTwoAndCapabilities() throws Exception {
        when(accountHandler.getModuleKey()).thenReturn("account");
        when(accountHandler.exportData(anyMap())).thenReturn(Map.of("accounts", List.of()));
        BackupExportReqBO request = new BackupExportReqBO();
        request.setModules(List.of("account"));

        Map<String, Object> exported = new ObjectMapper().readValue(
                service.exportData(request).getJsonData(), Map.class);

        assertEquals("2.0", exported.get("version"));
        assertTrue(((List<?>) exported.get("capabilities")).contains("stableKamiConfigIdMapping"));
        assertTrue(!service.exportData(request).getJsonData().contains("_error"));
    }

    @Test
    void versionTwoRequiresHandlerCompletenessFieldsButVersionOneRemainsCompatible() {
        when(accountHandler.getModuleKey()).thenReturn("account");
        when(accountHandler.getRequiredImportListKeys()).thenReturn(Set.of("accounts"));
        when(accountHandler.getAdditionalRequiredImportListKeys("2.0"))
                .thenReturn(Set.of("newRows"));
        when(accountHandler.getAdditionalRequiredImportListKeys("1.0"))
                .thenReturn(Set.of());

        assertThrows(IllegalArgumentException.class, () -> service.importData(
                request("2.0", Map.of("account", Map.of("accounts", List.of())))));

        service.importData(request("1.0", Map.of(
                "account", Map.of("accounts", List.of()))));
        verify(accountHandler).importData(anyMap(), anyMap());
    }

    @Test
    void importDataRejectsUnsupportedBackupVersion() {
        BackupImportReqBO request = request("0.9", Map.of("account", Map.of()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importData(request));

        assertTrue(error.getMessage().contains("0.9"));
    }

    @Test
    void importDataRejectsAnExportErrorMarkerBeforeCallingAHandler() {
        when(accountHandler.getModuleKey()).thenReturn("account");
        BackupImportReqBO request = request("1.0", Map.of(
                "account", Map.of("_error", "database unavailable")
        ));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importData(request));

        assertTrue(error.getMessage().contains("account"));
        verify(accountHandler, never()).importData(anyMap(), anyMap());
    }

    @Test
    void importDataRejectsUnknownSelectedModule() {
        when(accountHandler.getModuleKey()).thenReturn("account");
        BackupImportReqBO request = request("1.0", Map.of("futureModule", Map.of()),
                List.of("futureModule"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importData(request));

        assertTrue(error.getMessage().contains("futureModule"));
    }

    @Test
    void importDataRejectsNonObjectAndMissingModuleStructures() {
        when(goodsHandler.getModuleKey()).thenReturn("goods");
        when(goodsHandler.getRequiredImportListKeys()).thenReturn(Set.of("goodsList"));
        ReflectionTestUtils.setField(service, "handlers", List.of(goodsHandler));

        BackupImportReqBO nonObject = request("1.0", Map.of("goods", List.of()), List.of("goods"));
        BackupImportReqBO missingList = request("1.0", Map.of("goods", Map.of()), List.of("goods"));

        assertThrows(IllegalArgumentException.class, () -> service.importData(nonObject));
        assertThrows(IllegalArgumentException.class, () -> service.importData(missingList));
        verify(goodsHandler, never()).importData(anyMap(), anyMap());
    }

    @Test
    void accountImportsBeforeGoodsEvenWhenJsonOrderIsReversed() {
        XianyuAccount importedAccount = new XianyuAccount();
        importedAccount.setId(7L);
        importedAccount.setUnb("123456");
        when(accountMapper.selectList(null)).thenReturn(List.of(), List.of(importedAccount));
        when(accountHandler.getModuleKey()).thenReturn("account");
        when(accountHandler.getRequiredImportListKeys()).thenReturn(Set.of("accounts", "cookies"));
        when(goodsHandler.getModuleKey()).thenReturn("goods");
        when(goodsHandler.getRequiredImportListKeys()).thenReturn(Set.of("goodsList"));
        ReflectionTestUtils.setField(service, "handlers", List.of(goodsHandler, accountHandler));

        doAnswer(invocation -> {
            Map<String, Object> context = invocation.getArgument(1);
            @SuppressWarnings("unchecked")
            Map<String, Long> accountIds = (Map<String, Long>) context.get("unbToAccountId");
            assertEquals(7L, accountIds.get("123456"));
            return null;
        }).when(goodsHandler).importData(anyMap(), anyMap());

        Map<String, Object> reversedModules = new LinkedHashMap<>();
        reversedModules.put("goods", Map.of("goodsList", List.of()));
        reversedModules.put("account", Map.of("accounts", List.of(), "cookies", List.of()));

        service.importData(request("1.0", reversedModules));

        InOrder order = inOrder(accountHandler, goodsHandler);
        order.verify(accountHandler).importData(anyMap(), anyMap());
        order.verify(goodsHandler).importData(anyMap(), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"autoReply", "autoDelivery", "kami"})
    void dependentModulesReceiveExistingAccountContextWhenRestoredIndependently(String moduleKey) {
        XianyuAccount existingAccount = new XianyuAccount();
        existingAccount.setId(7L);
        existingAccount.setUnb("123456");
        when(accountMapper.selectList(null)).thenReturn(List.of(existingAccount));

        DataBackupHandler dependentHandler = mock(DataBackupHandler.class);
        when(dependentHandler.getModuleKey()).thenReturn(moduleKey);
        when(dependentHandler.getRequiredImportListKeys()).thenReturn(Set.of("rows"));
        ReflectionTestUtils.setField(service, "handlers", List.of(dependentHandler));
        doAnswer(invocation -> {
            Map<String, Object> context = invocation.getArgument(1);
            @SuppressWarnings("unchecked")
            Map<String, Long> accountIds = (Map<String, Long>) context.get("unbToAccountId");
            assertEquals(7L, accountIds.get("123456"));
            return null;
        }).when(dependentHandler).importData(anyMap(), anyMap());

        service.importData(request("1.0", Map.of(moduleKey, Map.of("rows", List.of())),
                List.of(moduleKey)));

        verify(dependentHandler).importData(anyMap(), anyMap());
    }

    @Test
    void importDataRemainsTransactional() throws NoSuchMethodException {
        Transactional annotation = DataBackupServiceImpl.class
                .getMethod("importData", BackupImportReqBO.class)
                .getAnnotation(Transactional.class);

        assertTrue(annotation != null);
        assertEquals(Exception.class, annotation.rollbackFor()[0]);
    }

    @Test
    void realTransactionRollsBackEarlierModuleWritesWhenALaterModuleFails() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("backup-transaction.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE restored_value (module_key TEXT NOT NULL)");

        DataBackupHandler goods = jdbcHandler("goods", jdbc, false);
        DataBackupHandler autoReply = jdbcHandler("autoReply", jdbc, true);
        DataBackupServiceImpl target = new DataBackupServiceImpl();
        ReflectionTestUtils.setField(target, "handlers", List.of(autoReply, goods));
        ReflectionTestUtils.setField(target, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(target, "accountMapper", accountMapper);
        DataBackupService proxy = transactionalProxy(target, dataSource);

        Map<String, Object> modules = new LinkedHashMap<>();
        modules.put("autoReply", Map.of("rows", List.of()));
        modules.put("goods", Map.of("rows", List.of()));

        assertThrows(IllegalStateException.class,
                () -> proxy.importData(request("1.0", modules)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM restored_value", Integer.class));
    }

    private DataBackupHandler jdbcHandler(String moduleKey, JdbcTemplate jdbc, boolean fail) {
        return new DataBackupHandler() {
            @Override
            public String getModuleKey() {
                return moduleKey;
            }

            @Override
            public String getModuleName() {
                return moduleKey;
            }

            @Override
            public Set<String> getRequiredImportListKeys() {
                return Set.of("rows");
            }

            @Override
            public Map<String, Object> exportData() {
                return Map.of("rows", List.of());
            }

            @Override
            public void importData(Map<String, Object> data, Map<String, Object> context) {
                jdbc.update("INSERT INTO restored_value(module_key) VALUES (?)", moduleKey);
                if (fail) {
                    throw new IllegalStateException("simulated import failure");
                }
            }
        };
    }

    private DataBackupService transactionalProxy(DataBackupServiceImpl target,
                                                  DriverManagerDataSource dataSource) {
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(new DataSourceTransactionManager(dataSource));
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(advice);
        return (DataBackupService) proxyFactory.getProxy();
    }

    /**
     * 流式导出与旧接口必须产出同样的内容。旧接口内部就是包了一层 ByteArrayOutputStream，
     * 所以这条测试同时也是"两条路不会漂移"的回归保护。
     */
    @Test
    void exportDataToStreamWritesSameJsonAsExportData() throws Exception {
        when(accountHandler.getModuleKey()).thenReturn("account");
        when(accountHandler.exportData(anyMap())).thenReturn(Map.of("accounts", List.of()));
        BackupExportReqBO request = new BackupExportReqBO();
        request.setModules(List.of("account"));

        String legacy = service.exportData(request).getJsonData();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportDataToStream(out, request);
        String streamed = out.toString(StandardCharsets.UTF_8);

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> legacyMap = mapper.readValue(legacy, Map.class);
        Map<String, Object> streamedMap = mapper.readValue(streamed, Map.class);
        // exportTime 是导出时刻，必然不同
        legacyMap.remove("exportTime");
        streamedMap.remove("exportTime");

        assertEquals(legacyMap, streamedMap);
        assertTrue(streamedMap.containsKey("modules"));
    }

    /**
     * 声明支持流式的模块必须走 exportJson 直接写，不能再让它先返回一个
     * 整模块的 Map（否则"流式"只是换了个地方 OOM）。
     */
    @Test
    void streamingModuleWritesDirectlyInsteadOfReturningWholeMap() throws Exception {
        DataBackupHandler streamingHandler = mock(DataBackupHandler.class);
        when(streamingHandler.getModuleKey()).thenReturn("chatLogs");
        when(streamingHandler.supportsStreamingExport()).thenReturn(true);
        doAnswer(invocation -> {
            com.fasterxml.jackson.core.JsonGenerator generator = invocation.getArgument(0);
            generator.writeStartObject();
            generator.writeArrayFieldStart("messages");
            generator.writeEndArray();
            generator.writeEndObject();
            return null;
        }).when(streamingHandler).exportJson(any(), anyMap());
        ReflectionTestUtils.setField(service, "handlers", List.of(streamingHandler));

        BackupExportReqBO request = new BackupExportReqBO();
        request.setModules(List.of("chatLogs"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportDataToStream(out, request);

        Map<String, Object> parsed = new ObjectMapper()
                .readValue(out.toString(StandardCharsets.UTF_8), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> modules = (Map<String, Object>) parsed.get("modules");

        assertTrue(modules.containsKey("chatLogs"));
        verify(streamingHandler, never()).exportData(anyMap());
    }

    private BackupImportReqBO request(String version, Map<String, Object> modules) {
        return request(version, modules, null);
    }

    private BackupImportReqBO request(String version, Map<String, Object> modules, List<String> selectedModules) {
        BackupImportReqBO request = new BackupImportReqBO();
        request.setModules(selectedModules);
        try {
            Map<String, Object> backup = new LinkedHashMap<>();
            backup.put("version", version);
            backup.put("modules", modules);
            request.setJsonData(new ObjectMapper().writeValueAsString(backup));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return request;
    }
}
