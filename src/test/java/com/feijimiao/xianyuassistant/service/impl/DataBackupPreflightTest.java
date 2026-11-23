package com.feijimiao.xianyuassistant.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.service.bo.BackupImportReqBO;
import com.feijimiao.xianyuassistant.service.bo.BackupPreflightRespBO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导入预检必须是严格只读的：解析、校验、统计，但不能调用 handler 的写方法。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataBackupPreflightTest {

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
        ReflectionTestUtils.setField(service, "handlers", List.of(accountHandler, goodsHandler));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
    }

    @Test
    void reportsModulesRecordCountsAndOrderWithoutWriting() {
        stubHandlers();
        BackupImportReqBO request = request("2.0", Map.of(
                "goods", Map.of("goodsList", List.of(Map.of("id", 1)), "goodsSkus", List.of(), "goodsSkuProperties", List.of()),
                "account", Map.of("accounts", List.of(Map.of("unb", "a"), Map.of("unb", "b")), "cookies", List.of())
        ), null);

        BackupPreflightRespBO result = service.preflightImport(request);

        assertThat(result.isImportable()).isTrue();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getBackupVersion()).isEqualTo("2.0");
        // 导入顺序由 IMPORT_ORDER 决定，而不是 JSON 里的键顺序
        assertThat(result.getImportOrder()).containsExactly("account", "goods");
        assertThat(result.getModules())
                .extracting(BackupPreflightRespBO.ModuleDetail::getModuleKey)
                .containsExactly("account", "goods");
        assertThat(result.getModules().get(0).getRecordCount()).isEqualTo(2);
        assertThat(result.getModules().get(1).getRecordCount()).isEqualTo(1);
        verify(accountHandler, never()).importData(anyMap(), anyMap());
        verify(goodsHandler, never()).importData(anyMap(), anyMap());
    }

    @Test
    void reportsUnsupportedVersionAsErrorInsteadOfThrowing() {
        stubHandlers();
        BackupPreflightRespBO result = service.preflightImport(
                request("0.9", Map.of("account", Map.of("accounts", List.of(), "cookies", List.of())), null));

        assertThat(result.isImportable()).isFalse();
        assertThat(result.getErrors()).singleElement().asString().contains("0.9");
    }

    @Test
    void reportsMissingRequiredFieldsAsError() {
        stubHandlers();
        BackupPreflightRespBO result = service.preflightImport(
                request("2.0", Map.of("goods", Map.of("goodsList", List.of())), null));

        assertThat(result.isImportable()).isFalse();
        assertThat(result.getErrors()).singleElement().asString().contains("goodsSkus");
    }

    @Test
    void rejectsNonJsonPayloadWithoutEchoingParserDetails() {
        BackupImportReqBO request = new BackupImportReqBO();
        request.setJsonData("{\"version\":\"2.0\", secret=\"leaked\"");

        BackupPreflightRespBO result = service.preflightImport(request);

        assertThat(result.isImportable()).isFalse();
        assertThat(result.getErrors()).containsExactly("备份文件不是有效的 JSON 对象");
        assertThat(String.join("", result.getErrors())).doesNotContain("leaked");
    }

    @Test
    void warnsAboutLegacyVersionAndUnknownModulesButKeepsImportableWhenValid() {
        stubHandlers();
        BackupPreflightRespBO result = service.preflightImport(request("1.0", Map.of(
                "account", Map.of("accounts", List.of(), "cookies", List.of()),
                "futureModule", Map.of("rows", List.of())
        ), List.of("account")));

        assertThat(result.isImportable()).as("errors=%s warnings=%s", result.getErrors(), result.getWarnings()).isTrue();
        assertThat(result.getAvailableModules()).contains("account", "futureModule");
        assertThat(result.getImportOrder()).containsExactly("account");
        assertThat(result.getWarnings())
                .anyMatch(warning -> warning.contains("1.0"))
                .anyMatch(warning -> warning.contains("futureModule"));
    }

    @Test
    void warnsWhenBackupContainsPlaintextCredentials() {
        stubHandlers();
        BackupImportReqBO request = request("2.0",
                Map.of("account", Map.of("accounts", List.of(), "cookies", List.of())), null);
        request.setJsonData(request.getJsonData().replace("\"version\"", "\"includeSecrets\":true,\"version\""));

        BackupPreflightRespBO result = service.preflightImport(request);

        assertThat(result.getIncludeSecrets()).isTrue();
        assertThat(result.getWarnings()).anyMatch(warning -> warning.contains("明文凭据"));
    }

    private void stubHandlers() {
        when(accountHandler.getModuleKey()).thenReturn("account");
        when(accountHandler.getModuleName()).thenReturn("闲鱼账号");
        when(accountHandler.getRequiredImportListKeys()).thenReturn(Set.of("accounts", "cookies"));
        when(accountHandler.getAdditionalRequiredImportListKeys("2.0")).thenReturn(Set.of());
        when(accountHandler.getAdditionalRequiredImportListKeys("1.0")).thenReturn(Set.of());
        when(goodsHandler.getModuleKey()).thenReturn("goods");
        when(goodsHandler.getModuleName()).thenReturn("商品管理");
        when(goodsHandler.getRequiredImportListKeys()).thenReturn(Set.of("goodsList"));
        when(goodsHandler.getAdditionalRequiredImportListKeys("2.0"))
                .thenReturn(Set.of("goodsSkus", "goodsSkuProperties"));
        when(goodsHandler.getAdditionalRequiredImportListKeys("1.0")).thenReturn(Set.of());
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
