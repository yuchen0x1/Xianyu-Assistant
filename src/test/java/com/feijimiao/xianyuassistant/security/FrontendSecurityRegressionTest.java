package com.feijimiao.xianyuassistant.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendSecurityRegressionTest {

    @Test
    void confirmDialogTreatsTitlesAndMessagesAsText() throws IOException {
        String source = read("vue-code/src/utils/confirm.ts");

        assertThat(source)
                .doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML")
                .contains(
                        "titleElement.textContent = title",
                        "messageElement.textContent = message",
                        "dialog.setAttribute('role', 'alertdialog')",
                        "dialog.setAttribute('aria-modal', 'true')");
    }

    @Test
    void qrLoginFrontendCannotRequestRawSessionCredentials() throws IOException {
        String api = read("vue-code/src/api/qrlogin.ts");
        String addDialog = read("vue-code/src/views/accounts/components/QRLoginDialog.vue");
        String updateDialog = read("vue-code/src/views/connection/components/QRUpdateDialog.vue");
        String refreshDialog = read("vue-code/src/views/connection/components/RefreshCookieDialog.vue");

        assertThat(api).doesNotContain("/cookies", "getQRCodeCookies");
        assertThat(addDialog + updateDialog + refreshDialog)
                .doesNotContain("getQRCodeCookies", "cookieRes.data", "cookieText = cookieRes");
    }

    @Test
    void successfulPasswordChangeClearsTokenBeforeReplacingTheRoute() throws IOException {
        String settings = read("vue-code/src/views/settings/index.vue");
        int handlerStart = settings.indexOf("async function handleChangePassword()");
        int handlerEnd = settings.indexOf("async function handleLogout()", handlerStart);
        String handler = settings.substring(handlerStart, handlerEnd);

        assertThat(handler.indexOf("clearAuthToken()"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(handler.indexOf("await router.replace('/login')"));
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath));
    }
}
