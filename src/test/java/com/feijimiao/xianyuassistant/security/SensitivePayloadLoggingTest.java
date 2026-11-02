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

class SensitivePayloadLoggingTest {

    private static final Path JAVA_SOURCE_ROOT = Path.of("src", "main", "java");

    private static final List<String> TARGET_PATHS = List.of(
            "com/feijimiao/xianyuassistant/config/rag/DynamicAIChatClientManager.java",
            "com/feijimiao/xianyuassistant/utils/MessageDecryptUtils.java",
            "com/feijimiao/xianyuassistant/websocket/handler/SyncMessageHandler.java",
            "com/feijimiao/xianyuassistant/service/impl/AIServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/AutoReplyServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/ItemServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/ItemDetailSyncServiceImpl.java",
            "com/feijimiao/xianyuassistant/service/impl/ImageUploadServiceImpl.java"
    );

    private static final Pattern LOG_INVOCATION = Pattern.compile(
            "\\blog\\.(?:trace|debug|info|warn|error)\\s*\\([^;]*?\\);",
            Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final String SENSITIVE_VALUE =
            "(?:currentApiKey|currentBaseUrl|encryptedData|cleanedData|jsonString|fallback|"
                    + "decryptedData|field635|jsonStr|targetUrl|url|prompt|msg|buyerMessage|"
                    + "responseBody|response|responseMap|cardData|cdnUrl|imageUrl|reqDTO|error)";
    private static final Pattern RAW_VALUE_ARGUMENT = Pattern.compile(
            "(?i)(?:,|\\()\\s*" + SENSITIVE_VALUE + "\\s*(?:,|\\))");
    private static final Pattern RAW_VALUE_FRAGMENT = Pattern.compile(
            "(?i)\\b" + SENSITIVE_VALUE + "\\s*\\.\\s*(?:substring|toString)\\s*\\(");
    private static final Pattern RAW_ACCESSOR_ARGUMENT = Pattern.compile(
            "(?i)\\.get(?:MsgContent|ImageUrl|ReminderUrl)\\(\\)\\s*(?:,|\\))");
    private static final Pattern RAW_FORMATTER = Pattern.compile("\\bformatJson\\s*\\(");
    private static final Pattern EXCEPTION_MESSAGE = Pattern.compile("\\.getMessage\\s*\\(");

    @Test
    void sensitivePayloadsAreReducedToSafeMetadataBeforeLogging() throws IOException {
        for (String relativePath : TARGET_PATHS) {
            String source = Files.readString(JAVA_SOURCE_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
            Matcher matcher = LOG_INVOCATION.matcher(source);
            int logCount = 0;

            while (matcher.find()) {
                logCount++;
                String invocation = STRING_LITERAL.matcher(matcher.group()).replaceAll("\"\"");

                assertThat(invocation)
                        .as("raw sensitive value in log call at %s", relativePath)
                        .doesNotContainPattern(RAW_VALUE_ARGUMENT);
                assertThat(invocation)
                        .as("sensitive value fragment in log call at %s", relativePath)
                        .doesNotContainPattern(RAW_VALUE_FRAGMENT);
                assertThat(invocation)
                        .as("raw sensitive accessor in log call at %s", relativePath)
                        .doesNotContainPattern(RAW_ACCESSOR_ARGUMENT);
                assertThat(invocation)
                        .as("raw message formatter in log call at %s", relativePath)
                        .doesNotContainPattern(RAW_FORMATTER);
                assertThat(invocation)
                        .as("exception message in log call at %s", relativePath)
                        .doesNotContainPattern(EXCEPTION_MESSAGE);
            }

            assertThat(logCount).as("logger calls scanned in %s", relativePath).isPositive();
        }
    }
}
