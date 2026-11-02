package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.controller.dto.BackupExportReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.BackupImportReqDTO;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.service.DataBackupScheduler;
import com.feijimiao.xianyuassistant.service.DataBackupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 备份是整库快照，导出/导入必须收敛到 ADMIN。
 *
 * <p>{@code backup:write} 是 OPERATOR 的默认权限位，它能「管理备份」，但不能
 * 等价于「可以把全部账号的订单、聊天记录、卡密搬走」，也不能用一份手工构造
 * 的备份覆盖全库。这里守的就是这条线：非 ADMIN 在开始写流之前就被拦下
 * （流一旦开始写，HTTP 状态已经发出，再拒绝只会留下半截文件）。</p>
 */
class DataBackupControllerAdminOnlyTest {

    private DataBackupService dataBackupService;
    private RbacService rbacService;
    private DataBackupController controller;

    @BeforeEach
    void setUp() {
        dataBackupService = mock(DataBackupService.class);
        rbacService = mock(RbacService.class);
        controller = new DataBackupController();
        ReflectionTestUtils.setField(controller, "dataBackupService", dataBackupService);
        ReflectionTestUtils.setField(controller, "rbacService", rbacService);
        ReflectionTestUtils.setField(controller, "dataBackupScheduler", mock(DataBackupScheduler.class));
        UserContext.set(2L, "operator");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void operatorCannotExportWholeDatabase() {
        when(rbacService.getUserRoleNames(2L)).thenReturn(List.of("OPERATOR"));

        assertExportRejected(() -> controller.exportData(exportRequest(false)));
    }

    @Test
    void operatorCannotUseStreamingExportEither() {
        when(rbacService.getUserRoleNames(2L)).thenReturn(List.of("OPERATOR"));

        assertExportRejected(() -> controller.exportDataStream(exportRequest(false)));
    }

    @Test
    void operatorCannotImportWholeDatabase() {
        when(rbacService.getUserRoleNames(2L)).thenReturn(List.of("OPERATOR"));

        ResultObject<?> result = controller.importData(importRequest());

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMsg()).contains("仅管理员");
        verify(dataBackupService, never()).importData(any());
    }

    @Test
    void missingUserContextIsRejectedInsteadOfAllowed() {
        UserContext.clear();

        assertExportRejected(() -> controller.exportData(exportRequest(false)));
    }

    @Test
    void roleLookupFailureFailsClosed() {
        when(rbacService.getUserRoleNames(2L)).thenThrow(new IllegalStateException("db down"));

        assertExportRejected(() -> controller.exportData(exportRequest(false)));
    }

    @Test
    void adminGetsStreamingResponseWithCompletenessMarker() throws Exception {
        when(rbacService.getUserRoleNames(2L)).thenReturn(List.of("ADMIN"));

        ResponseEntity<StreamingResponseBody> response = controller.exportData(exportRequest(true));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("X-Backup-Export")).isEqualTo("ok");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains(".json");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();

        // 真正写流时才调用 service，验证没有走「先拼成字符串」的老路径
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        verify(dataBackupService, never()).exportData(any());
        verify(dataBackupService).exportDataToStream(any(), any());
    }

    private void assertExportRejected(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅管理员")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        verify(dataBackupService, never()).exportDataToStream(any(), any());
        verify(dataBackupService, never()).exportData(any());
        verify(dataBackupService, never()).importData(any());
    }

    private BackupExportReqDTO exportRequest(boolean includeSecrets) {
        BackupExportReqDTO dto = new BackupExportReqDTO();
        dto.setModules(List.of("account"));
        dto.setIncludeSecrets(includeSecrets);
        return dto;
    }

    private BackupImportReqDTO importRequest() {
        BackupImportReqDTO dto = new BackupImportReqDTO();
        dto.setJsonData("{\"modules\":[]}");
        dto.setModules(List.of("account"));
        return dto;
    }
}
