package com.feijimiao.xianyuassistant.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.feijimiao.xianyuassistant.controller.dto.QRLoginSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class QRLoginLifecycleTest {

    private static final String SESSION_ID = "sensitive-session-id";
    private static final String SECRET = "sensitive-cookie-value";

    private final Logger logger = (Logger) LoggerFactory.getLogger(QRLoginServiceImpl.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private QRLoginServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new QRLoginServiceImpl();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void monitorTaskIsSubmittedToTheInjectedExecutor() {
        AtomicReference<Runnable> submittedTask = new AtomicReference<>();
        setExecutor(submittedTask::set);
        QRLoginSession session = session();
        sessions().put(SESSION_ID, session);

        boolean accepted = submit(session);

        assertThat(accepted).isTrue();
        assertThat(submittedTask.get()).isNotNull();
        assertThat(sessions()).containsEntry(SESSION_ID, session);
    }

    @Test
    void rejectedSubmissionRemovesSessionClearsCredentialsAndLogsOnlySafeMetadata() {
        setExecutor(task -> {
            throw new RejectedExecutionException("rejected " + SESSION_ID + " " + SECRET);
        });
        QRLoginSession session = session();
        sessions().put(SESSION_ID, session);

        boolean accepted = submit(session);

        assertThat(accepted).isFalse();
        assertFailedSessionWasCleaned(session);
        assertSanitizedFailureLog("RejectedExecutionException");
    }

    @Test
    void unexpectedSubmissionFailureRemovesSessionClearsCredentialsAndLogsOnlySafeMetadata() {
        setExecutor(task -> {
            throw new IllegalStateException("executor failed " + SESSION_ID + " " + SECRET);
        });
        QRLoginSession session = session();
        sessions().put(SESSION_ID, session);

        boolean accepted = submit(session);

        assertThat(accepted).isFalse();
        assertFailedSessionWasCleaned(session);
        assertSanitizedFailureLog("IllegalStateException");
    }

    @Test
    void acceptedMonitorTaskFailureMarksSessionAsErrorAndSanitizesTheLog() throws Exception {
        service = new QRLoginServiceImpl() {
            @Override
            void monitorQRStatus(String sessionId) {
                throw new IllegalStateException("monitor failed " + sessionId + " " + SECRET);
            }
        };
        AtomicReference<Runnable> submittedTask = new AtomicReference<>();
        setExecutor(submittedTask::set);
        QRLoginSession session = session();
        sessions().put(SESSION_ID, session);

        assertThat(submit(session)).isTrue();
        submittedTask.get().run();

        assertThat(sessions()).containsEntry(SESSION_ID, session);
        assertThat(session.getStatus()).isEqualTo("error");
        assertCredentialsCleared(session);
        assertSanitizedFailureLog("IllegalStateException");
    }

    @Test
    void monitorInterruptionStopsPollingAndClearsSessionCredentials() throws Exception {
        CountDownLatch polled = new CountDownLatch(1);
        service = new QRLoginServiceImpl() {
            @Override
            String pollQRCodeStatus(QRLoginSession session) {
                polled.countDown();
                return "NEW";
            }
        };
        QRLoginSession session = session();
        sessions().put(SESSION_ID, session);
        Thread worker = new Thread(() ->
                ReflectionTestUtils.invokeMethod(service, "monitorQRStatus", SESSION_ID));
        worker.setDaemon(true);

        worker.start();
        assertThat(polled.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitThreadState(worker, Thread.State.TIMED_WAITING, 2_000)).isTrue();
        worker.interrupt();
        worker.join(300);
        boolean stoppedPromptly = !worker.isAlive();
        boolean interruptionPreserved = worker.isInterrupted();

        if (worker.isAlive()) {
            sessions().remove(SESSION_ID);
            worker.join(3_000);
        }

        assertThat(stoppedPromptly).isTrue();
        assertThat(interruptionPreserved).isTrue();
        assertThat(session.getStatus()).isEqualTo("expired");
        assertCredentialsCleared(session);
    }

    private QRLoginSession session() {
        QRLoginSession session = new QRLoginSession(SESSION_ID);
        session.getCookies().put("cookie", SECRET);
        session.getParams().put("token", SECRET);
        session.setUnb(SECRET);
        session.setQrContent(SECRET);
        session.setQrCodeUrl("data:image/png;base64," + SECRET);
        session.setVerificationUrl("https://example.invalid/" + SECRET);
        return session;
    }

    private boolean submit(QRLoginSession session) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "submitMonitorTask", session));
    }

    private void setExecutor(Executor executor) {
        Field field = ReflectionUtils.findField(QRLoginServiceImpl.class, "qrLoginExecutor");
        assertThat(field).as("QR login monitor executor field").isNotNull();
        ReflectionUtils.makeAccessible(field);
        ReflectionUtils.setField(field, service, executor);
    }

    private void assertFailedSessionWasCleaned(QRLoginSession session) {
        assertThat(sessions()).doesNotContainKey(SESSION_ID);
        assertCredentialsCleared(session);
    }

    private void assertCredentialsCleared(QRLoginSession session) {
        assertThat(session.getCookies()).isEmpty();
        assertThat(session.getParams()).isEmpty();
        assertThat(session.getUnb()).isNull();
        assertThat(session.getQrContent()).isNull();
        assertThat(session.getQrCodeUrl()).isNull();
        assertThat(session.getVerificationUrl()).isNull();
    }

    private void assertSanitizedFailureLog(String expectedErrorType) {
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("sessionIdLength=" + SESSION_ID.length(), "errorType=" + expectedErrorType)
                        .doesNotContain(SESSION_ID, SECRET));
    }

    private boolean awaitThreadState(Thread thread, Thread.State state, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (thread.isAlive() && System.currentTimeMillis() < deadline) {
            if (thread.getState() == state) {
                return true;
            }
            Thread.sleep(10);
        }
        return thread.getState() == state;
    }

    @SuppressWarnings("unchecked")
    private Map<String, QRLoginSession> sessions() {
        return (Map<String, QRLoginSession>) ReflectionTestUtils.getField(service, "sessions");
    }
}
