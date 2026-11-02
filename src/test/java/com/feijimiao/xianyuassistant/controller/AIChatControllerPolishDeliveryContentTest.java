package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.config.rag.DynamicAIChatClientManager;
import com.feijimiao.xianyuassistant.service.AIService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AIChatControllerPolishDeliveryContentTest {

    @Mock
    private AIService aiService;

    @Mock
    private DynamicAIChatClientManager dynamicAIChatClientManager;

    private AIChatController controller;

    @BeforeEach
    void setUp() {
        controller = new AIChatController();
        ReflectionTestUtils.setField(controller, "aiService", aiService);
        ReflectionTestUtils.setField(controller, "dynamicAIChatClientManager", dynamicAIChatClientManager);
    }

    @Test
    void polishesContentAndRestoresProtectedFragments() {
        String original = "链接：https://example.com/share/abc\n账号 abc123\n密码 X9Q7\n卡密：{kmKey}";
        when(dynamicAIChatClientManager.isAvailable()).thenReturn(true);
        when(aiService.polishDeliveryContent(anyString())).thenAnswer(invocation -> {
            String masked = invocation.getArgument(0);
            assertThat(masked).doesNotContain(
                    "https://example.com/share/abc", "abc123", "X9Q7", "{kmKey}");
            assertThat(masked).contains(
                    "__XY_DELIVERY_LITERAL_0__",
                    "__XY_DELIVERY_LITERAL_1__",
                    "__XY_DELIVERY_LITERAL_2__",
                    "__XY_DELIVERY_LITERAL_3__");
            return "已为您准备好：\n" + masked;
        });

        ResultObject<AIChatController.PolishDeliveryContentRespDTO> result =
                controller.polishDeliveryContent(request(original));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getContent())
                .isEqualTo("已为您准备好：\n" + original);
    }

    @Test
    void rejectsBlankAndOverlongInputBeforeCallingAi() {
        ResultObject<AIChatController.PolishDeliveryContentRespDTO> blank =
                controller.polishDeliveryContent(request("   "));
        ResultObject<AIChatController.PolishDeliveryContentRespDTO> overlong =
                controller.polishDeliveryContent(request("a".repeat(201)));

        assertThat(blank.getCode()).isEqualTo(404);
        assertThat(overlong.getCode()).isEqualTo(404);
        verifyNoInteractions(aiService, dynamicAIChatClientManager);
    }

    @Test
    void reportsUnavailableAiWithoutCallingModel() {
        when(dynamicAIChatClientManager.isAvailable()).thenReturn(false);

        ResultObject<AIChatController.PolishDeliveryContentRespDTO> result =
                controller.polishDeliveryContent(request("请查收发货内容"));

        assertThat(result.getCode()).isEqualTo(1001);
        assertThat(result.getMsg()).contains("系统设置");
        verifyNoInteractions(aiService);
    }

    @Test
    void keepsOriginalWhenAiDropsProtectedFragment() {
        when(dynamicAIChatClientManager.isAvailable()).thenReturn(true);
        when(aiService.polishDeliveryContent(anyString())).thenReturn("请点击链接查看");

        ResultObject<AIChatController.PolishDeliveryContentRespDTO> result =
                controller.polishDeliveryContent(request("链接：https://example.com/file"));

        assertThat(result.getCode()).isEqualTo(500);
        assertThat(result.getData()).isNull();
        assertThat(result.getMsg()).contains("已保留原文");
    }

    @Test
    void rejectsReorderedOrAttachedProtectedFragments() {
        when(dynamicAIChatClientManager.isAvailable()).thenReturn(true);
        when(aiService.polishDeliveryContent(anyString()))
                .thenReturn("链接：__XY_DELIVERY_LITERAL_1__ 提取码：__XY_DELIVERY_LITERAL_0__")
                .thenReturn("链接：__XY_DELIVERY_LITERAL_0__?ref=ai 提取码：__XY_DELIVERY_LITERAL_1__")
                .thenReturn("链接：__XY_DELIVERY_LITERAL_0__~evil 提取码：__XY_DELIVERY_LITERAL_1__");

        String original = "链接：https://example.com/file 提取码：A1B2";
        ResultObject<AIChatController.PolishDeliveryContentRespDTO> reordered =
                controller.polishDeliveryContent(request(original));
        ResultObject<AIChatController.PolishDeliveryContentRespDTO> attached =
                controller.polishDeliveryContent(request(original));
        ResultObject<AIChatController.PolishDeliveryContentRespDTO> suffixChanged =
                controller.polishDeliveryContent(request(original));

        assertThat(reordered.getCode()).isEqualTo(500);
        assertThat(attached.getCode()).isEqualTo(500);
        assertThat(suffixChanged.getCode()).isEqualTo(500);
        assertThat(reordered.getMsg()).contains("已保留原文");
        assertThat(attached.getMsg()).contains("已保留原文");
    }

    @Test
    void rejectsRemovedSensitiveBoundaryCharacter() {
        when(dynamicAIChatClientManager.isAvailable()).thenReturn(true);
        when(aiService.polishDeliveryContent(anyString()))
                .thenReturn("请使用 __XY_DELIVERY_LITERAL_0__");

        ResultObject<AIChatController.PolishDeliveryContentRespDTO> result =
                controller.polishDeliveryContent(request("请使用 ${kmKey}"));

        assertThat(result.getCode()).isEqualTo(500);
        assertThat(result.getMsg()).contains("已保留原文");
    }

    @Test
    void masksBareLinksAndAsciiCredentials() {
        when(dynamicAIChatClientManager.isAvailable()).thenReturn(true);
        when(aiService.polishDeliveryContent(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        String original = "备用地址 www.example.com/path，邮箱 user@example.com.，兑换码 VIP-2026-ABCD";
        ResultObject<AIChatController.PolishDeliveryContentRespDTO> result =
                controller.polishDeliveryContent(request(original));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getContent()).isEqualTo(original);
        org.mockito.Mockito.verify(aiService).polishDeliveryContent(org.mockito.ArgumentMatchers.argThat(masked ->
                !masked.contains("www.example.com/path")
                        && !masked.contains("user@example.com")
                        && !masked.contains("VIP-2026-ABCD")));
    }

    @Test
    void doesNotMaskChineseWordsThatOnlyStartWithSensitiveLabels() {
        when(dynamicAIChatClientManager.isAvailable()).thenReturn(true);
        when(aiService.polishDeliveryContent(anyString())).thenReturn("请注意账号安全很重要，密码不要泄露");

        ResultObject<AIChatController.PolishDeliveryContentRespDTO> result =
                controller.polishDeliveryContent(request("账号安全很重要，密码不要泄露"));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getContent()).isEqualTo("请注意账号安全很重要，密码不要泄露");
    }

    @Test
    void rejectsSensitiveFragmentsAddedByAi() {
        when(dynamicAIChatClientManager.isAvailable()).thenReturn(true);
        when(aiService.polishDeliveryContent(anyString()))
                .thenReturn("请查收 https://phishing.example/file")
                .thenReturn("链接：__XY_DELIVERY_LITERAL_0__ https://phishing.example/file")
                .thenReturn("请查收 evil.example");

        ResultObject<AIChatController.PolishDeliveryContentRespDTO> withoutOriginalFragment =
                controller.polishDeliveryContent(request("请查收"));
        ResultObject<AIChatController.PolishDeliveryContentRespDTO> withOriginalFragment =
                controller.polishDeliveryContent(request("链接：https://example.com/file"));
        ResultObject<AIChatController.PolishDeliveryContentRespDTO> withBareDomain =
                controller.polishDeliveryContent(request("请查收"));

        assertThat(withoutOriginalFragment.getCode()).isEqualTo(500);
        assertThat(withOriginalFragment.getCode()).isEqualTo(500);
        assertThat(withBareDomain.getCode()).isEqualTo(500);
        assertThat(withoutOriginalFragment.getMsg()).contains("已保留原文");
        assertThat(withOriginalFragment.getMsg()).contains("已保留原文");
    }

    @Test
    void keepsCredentialValuesThatContainSpacesTogether() {
        when(dynamicAIChatClientManager.isAvailable()).thenReturn(true);
        when(aiService.polishDeliveryContent(anyString())).thenAnswer(invocation -> {
            String masked = invocation.getArgument(0);
            assertThat(masked).doesNotContain("correct horse");
            assertThat(masked).contains("请妥善保管");
            return masked;
        });

        String original = "密码: correct horse. 请妥善保管";
        ResultObject<AIChatController.PolishDeliveryContentRespDTO> result =
                controller.polishDeliveryContent(request(original));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getContent()).isEqualTo(original);
    }

    @Test
    void rejectsEmptyOrOverlongAiOutput() {
        when(dynamicAIChatClientManager.isAvailable()).thenReturn(true);
        when(aiService.polishDeliveryContent(anyString()))
                .thenReturn(" ")
                .thenReturn("a".repeat(201));

        ResultObject<AIChatController.PolishDeliveryContentRespDTO> empty =
                controller.polishDeliveryContent(request("请查收"));
        ResultObject<AIChatController.PolishDeliveryContentRespDTO> overlong =
                controller.polishDeliveryContent(request("请查收"));

        assertThat(empty.getCode()).isEqualTo(500);
        assertThat(overlong.getCode()).isEqualTo(500);
        assertThat(overlong.getMsg()).contains("超过200字");
    }

    private AIChatController.PolishDeliveryContentReqDTO request(String content) {
        AIChatController.PolishDeliveryContentReqDTO req =
                new AIChatController.PolishDeliveryContentReqDTO();
        req.setContent(content);
        return req;
    }
}
