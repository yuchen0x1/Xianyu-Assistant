package com.feijimiao.xianyuassistant.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyLoggingSafetyTest {

    private static final Path JAVA_SOURCE_ROOT = Path.of("src", "main", "java");
    private static final List<String> TARGET_PATHS = List.of(
            "com/feijimiao/xianyuassistant/controller/AccountController.java",
            "com/feijimiao/xianyuassistant/controller/WebSocketController.java",
            "com/feijimiao/xianyuassistant/config/rag/DynamicVectorStoreManager.java",
            "com/feijimiao/xianyuassistant/event/chatMessageEvent/lister/ChatMessageEventAutoDeliveryListener.java",
            "com/feijimiao/xianyuassistant/service/delivery/OrderDetailFetcher.java",
            "com/feijimiao/xianyuassistant/service/impl/AccountServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/AuthServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/AutoDeliveryServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/ItemServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/OrderServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/QRLoginServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/WebSocketServiceImpl.java",
            "com/feijimiao/xianyuassistant/util/JwtUtil.java",
            "com/feijimiao/xianyuassistant/utils/HttpClientUtils.java",
            "com/feijimiao/xianyuassistant/websocket/XianyuWebSocketClient.java"
    );

    private static final Pattern LOG_INVOCATION = Pattern.compile(
            "\\blog\\.(?:trace|debug|info|warn|error)\\s*\\([^;]*?\\);", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final String SENSITIVE_VALUE =
            "(?:accountNote|buyerUserId|buyerUserName|captchaUrl|deliveryResult|deviceId|"
                    + "effectiveBaseUrl|errorMessage|goodsTitle|iframeUrl|reason|sessionId|"
                    + "skuInfoStr|totalPrice|url|actualReceivers)";
    private static final Pattern SAFE_METADATA = Pattern.compile(
            "(?i)(?:safeLength\\s*\\(\\s*[\\w.]+\\.get(?:AccountNote|CaptchaUrl|DeviceId|ErrorMessage|"
                    + "HttpStatusMessage|Ip|SessionId|Username|VerificationUrl)\\(\\)\\s*\\)"
                    + "|safeLength\\s*\\(\\s*" + SENSITIVE_VALUE + "\\s*\\)"
                    + "|\\b" + SENSITIVE_VALUE + "\\b\\s*\\.\\s*(?:isBlank|isEmpty|length|size)\\s*\\(\\s*\\)"
                    + "|\\b" + SENSITIVE_VALUE + "\\b\\s*[!=]=\\s*null)"
    );
    private static final Pattern RAW_VALUE_ARGUMENT = Pattern.compile(
            "(?i)(?:,|\\()\\s*" + SENSITIVE_VALUE + "\\s*(?:,|\\))");
    private static final Pattern RAW_ACCESSOR_ARGUMENT = Pattern.compile(
            "(?i)\\.get(?:AccountNote|CaptchaUrl|DeviceId|ErrorMessage|HttpStatusMessage|Ip|SessionId|Username|VerificationUrl)"
                    + "\\(\\)\\s*(?:,|\\))");
    private static final Pattern EXCEPTION_MESSAGE = Pattern.compile("\\.getMessage\\s*\\(");

    @Test
    void personalAndSessionDataAreReducedToSafeMetadataBeforeLogging() throws IOException {
        for (String relativePath : TARGET_PATHS) {
            String source = Files.readString(JAVA_SOURCE_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
            Matcher matcher = LOG_INVOCATION.matcher(source);

            while (matcher.find()) {
                String invocation = STRING_LITERAL.matcher(matcher.group()).replaceAll("\"\"");
                invocation = SAFE_METADATA.matcher(invocation).replaceAll("SAFE");

                assertThat(invocation)
                        .as("raw personal or session value in log call at %s", relativePath)
                        .doesNotContainPattern(RAW_VALUE_ARGUMENT);
                assertThat(invocation)
                        .as("raw sensitive accessor in log call at %s", relativePath)
                        .doesNotContainPattern(RAW_ACCESSOR_ARGUMENT);
                assertThat(invocation)
                        .as("exception message in privacy-sensitive log call at %s", relativePath)
                        .doesNotContainPattern(EXCEPTION_MESSAGE);
            }
        }
    }
}
