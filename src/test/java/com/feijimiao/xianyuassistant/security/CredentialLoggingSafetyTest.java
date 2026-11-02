package com.feijimiao.xianyuassistant.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.feijimiao.xianyuassistant.utils.AccountDisplayNameUtils;
import com.feijimiao.xianyuassistant.websocket.WebSocketInitializer;
import com.feijimiao.xianyuassistant.websocket.XianyuWebSocketClient;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CredentialLoggingSafetyTest {

    private static final List<String> CREDENTIAL_PATHS = List.of(
            "src/main/java/com/feijimiao/xianyuassistant/service/impl/AccountServiceImpl.java",
            "src/main/java/com/feijimiao/xianyuassistant/service/impl/CookieRefreshServiceImpl.java",
            "src/main/java/com/feijimiao/xianyuassistant/service/impl/QRLoginServiceImpl.java",
            "src/main/java/com/feijimiao/xianyuassistant/service/impl/TokenRefreshServiceImpl.java",
            "src/main/java/com/feijimiao/xianyuassistant/service/impl/WebSocketTokenServiceImpl.java",
            "src/main/java/com/feijimiao/xianyuassistant/utils/XianyuApiCallUtils.java",
            "src/main/java/com/feijimiao/xianyuassistant/websocket/WebSocketInitializer.java"
    );

    private static final Pattern LOG_STATEMENT = Pattern.compile(
            "log\\.(?:trace|debug|info|warn|error)\\s*\\((?s:.*?)\\);"
    );
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern SAFE_METADATA = Pattern.compile(
            "(?i)\\b(?:accessToken|captchaUrl|cookieText|cookiesStr|dataMap|dataVal|deviceId|fullUrl|jsonMessage|"
                    + "html|jsonString|mH5Tk|mh5tk|newCookieStr|oldCookieStr|response|responseBody|responseMap|retList|setCookie|"
                    + "setCookieHeaders|token|unb|verificationUrl)\\b\\s*(?:"
                    + "\\.\\s*(?:code|isBlank|isEmpty|isSuccessful|length|size)\\s*\\(\\s*\\)"
                    + "|[!=]=\\s*null)|session\\.getUnb\\(\\)\\s*[!=]=\\s*null"
                    + "|parts\\s*\\[\\s*1\\s*]\\.\\s*length\\s*\\(\\s*\\)"
    );
    private static final Pattern SENSITIVE_LOG_ARGUMENT = Pattern.compile(
            "(?i)\\b(?:accessToken|captchaUrl|cookieText|cookiesStr|dataMap|dataVal|deviceId|fullUrl|jsonMessage|"
                    + "html|jsonString|mH5Tk|mh5tk|newCookieStr|oldCookieStr|response|responseBody|responseMap|retList|setCookie|"
                    + "setCookieHeaders|token|unb|verificationUrl)\\b|(?:getUnb|parts\\s*\\[\\s*1\\s*])"
    );

    @Test
    void credentialBearingValuesAreNotPassedToLoggers() throws IOException {
        for (String relativePath : CREDENTIAL_PATHS) {
            String source = Files.readString(Path.of(relativePath));
            Matcher matcher = LOG_STATEMENT.matcher(source);
            while (matcher.find()) {
                String statementWithoutMessages = STRING_LITERAL.matcher(matcher.group()).replaceAll("\"\"");
                String argumentsWithoutSafeMetadata = SAFE_METADATA.matcher(statementWithoutMessages).replaceAll("");

                assertThat(argumentsWithoutSafeMetadata)
                        .as("credential-safe logging in %s: %s", relativePath,
                                matcher.group().replaceAll("\\s+", " "))
                        .doesNotContainPattern(SENSITIVE_LOG_ARGUMENT);
            }
        }
    }

    @Test
    void websocketRegistrationSendsButDoesNotLogTheAccessToken() {
        String sensitiveToken = "access-token-secret-that-must-not-reach-logs";
        WebSocketInitializer initializer = new WebSocketInitializer();
        AccountDisplayNameUtils displayNameUtils = mock(AccountDisplayNameUtils.class);
        XianyuWebSocketClient client = mock(XianyuWebSocketClient.class);
        when(displayNameUtils.getDisplayName("7")).thenReturn("account-7");
        ReflectionTestUtils.setField(initializer, "displayNameUtils", displayNameUtils);

        Logger logger = (Logger) LoggerFactory.getLogger(WebSocketInitializer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            initializer.sendRegistrationMessage(client, sensitiveToken, "device-7", "7");

            verify(client).send(contains(sensitiveToken));
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains(sensitiveToken));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
