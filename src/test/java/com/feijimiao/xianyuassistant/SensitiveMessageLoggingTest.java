package com.feijimiao.xianyuassistant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveMessageLoggingTest {

    private static final Path JAVA_SOURCE_ROOT = Path.of("src", "main", "java");

    private static final List<String> MESSAGE_PATHS = List.of(
            "com/feijimiao/xianyuassistant/controller/WebSocketController.java",
            "com/feijimiao/xianyuassistant/websocket/XianyuWebSocketClient.java",
            "com/feijimiao/xianyuassistant/service/impl/WebSocketServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/AutoDeliveryServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/EnhancedMessageSendServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/SentMessageSaveServiceImpl.java",
            "com/feijimiao/xianyuassistant/event/chatMessageEvent/lister/ChatMessageEventSaveListener.java",
            "com/feijimiao/xianyuassistant/event/chatMessageEvent/lister/ChatMessageEventAutoReplyListener.java",
            "com/feijimiao/xianyuassistant/event/chatMessageEvent/lister/ChatMessageEventHumanInterventionListener.java",
            "com/feijimiao/xianyuassistant/event/chatMessageEvent/lister/ChatMessageEventAutoDeliveryListener.java"
    );
    private static final List<String> CONNECTION_LOG_PATHS = List.of(
            "com/feijimiao/xianyuassistant/controller/WebSocketController.java",
            "com/feijimiao/xianyuassistant/websocket/XianyuWebSocketClient.java",
            "com/feijimiao/xianyuassistant/service/impl/WebSocketServiceImpl.java"
    );

    private static final Pattern LOG_INVOCATION = Pattern.compile(
            "\\blog\\.(?:trace|debug|info|warn|error)\\s*\\([^;]*?\\);",
            Pattern.DOTALL);
    private static final Pattern RAW_MESSAGE_ACCESSOR_ARGUMENT = Pattern.compile(
            ",\\s*[\\w$.]+\\.get(?:Text|Content|MsgContent)\\(\\)\\s*(?:,|\\))");
    private static final Pattern RAW_MESSAGE_VARIABLE_ARGUMENT = Pattern.compile(
            ",\\s*(?:text|content|msgContent|message|textJson|imageJson|messageJson|imageUrl)\\s*(?:,|\\))");
    private static final Pattern RAW_MESSAGE_FRAGMENT_ARGUMENT = Pattern.compile(
            "\\b(?:text|content|msgContent|message)\\s*\\.\\s*substring\\(");
    private static final Pattern RAW_CONNECTION_ACCESSOR_ARGUMENT = Pattern.compile(
            "[\\w$.]+\\.get(?:CaptchaUrl|HttpStatusMessage|Message)\\(\\)");
    private static final Pattern RAW_CONNECTION_VARIABLE_ARGUMENT = Pattern.compile(
            ",\\s*(?:deviceId|sessionId|actualReceivers|reason)\\s*(?:,|\\))");
    private static final Pattern RAW_REGISTRATION_ID_ARGUMENT = Pattern.compile(
            ",\\s*headers\\.get\\(\"reg-sid\"\\)\\s*(?:,|\\))");
    private static final Pattern RAW_THROWABLE_ARGUMENT = Pattern.compile(
            ",\\s*(?:e|ex|exception|throwable)\\s*(?:,|\\))");
    private static final Pattern OPERATION_LOG_EXCEPTION_MESSAGE = Pattern.compile(
            "operationLogService\\.log\\([^;]*?\\.getMessage\\(\\)", Pattern.DOTALL);

    @Test
    void messageBodiesAreNeverPassedDirectlyToLogs() throws IOException {
        for (String relativePath : MESSAGE_PATHS) {
            Path sourcePath = JAVA_SOURCE_ROOT.resolve(relativePath);
            String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            Matcher matcher = LOG_INVOCATION.matcher(source);

            while (matcher.find()) {
                String logInvocation = matcher.group();
                assertThat(logInvocation)
                        .as("raw message accessor in log call at %s", relativePath)
                        .doesNotContainPattern(RAW_MESSAGE_ACCESSOR_ARGUMENT);
                assertThat(logInvocation)
                        .as("raw message variable in log call at %s", relativePath)
                        .doesNotContainPattern(RAW_MESSAGE_VARIABLE_ARGUMENT);
                assertThat(logInvocation)
                        .as("message fragment in log call at %s", relativePath)
                        .doesNotContainPattern(RAW_MESSAGE_FRAGMENT_ARGUMENT);
                if (CONNECTION_LOG_PATHS.contains(relativePath)) {
                    assertThat(logInvocation)
                            .as("raw connection accessor in log call at %s", relativePath)
                            .doesNotContainPattern(RAW_CONNECTION_ACCESSOR_ARGUMENT);
                    assertThat(logInvocation)
                            .as("raw connection identifier in log call at %s", relativePath)
                            .doesNotContainPattern(RAW_CONNECTION_VARIABLE_ARGUMENT);
                    assertThat(logInvocation)
                            .as("raw registration identifier in log call at %s", relativePath)
                            .doesNotContainPattern(RAW_REGISTRATION_ID_ARGUMENT);
                    assertThat(logInvocation)
                            .as("raw throwable in log call at %s", relativePath)
                            .doesNotContainPattern(RAW_THROWABLE_ARGUMENT);
                }
            }

            if (CONNECTION_LOG_PATHS.contains(relativePath)) {
                assertThat(source)
                        .as("exception message persisted in operation log at %s", relativePath)
                        .doesNotContainPattern(OPERATION_LOG_EXCEPTION_MESSAGE);
            }
        }
    }
}
