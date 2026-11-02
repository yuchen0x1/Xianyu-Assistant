package com.feijimiao.xianyuassistant.service;

/**
 * 在注入后台Cookie的可见浏览器中完成人工滑块，并把验证后的Cookie写回后台。
 */
public interface ManualCaptchaBrowserService {

    final class LaunchResult {
        private final boolean accepted;
        private final String message;
        private final String sessionId;
        private final String screenshot;
        private final int viewportWidth;
        private final int viewportHeight;

        public LaunchResult(boolean accepted, String message) {
            this(accepted, message, null, null, 1280, 800);
        }

        public LaunchResult(boolean accepted, String message, String sessionId,
                            String screenshot, int viewportWidth, int viewportHeight) {
            this.accepted = accepted;
            this.message = message;
            this.sessionId = sessionId;
            this.screenshot = screenshot;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
        }

        public boolean accepted() { return accepted; }
        public boolean isAccepted() { return accepted; }
        public String message() { return message; }
        public String getMessage() { return message; }
        public String sessionId() { return sessionId; }
        public String getSessionId() { return sessionId; }
        public String screenshot() { return screenshot; }
        public String getScreenshot() { return screenshot; }
        public int viewportWidth() { return viewportWidth; }
        public int getViewportWidth() { return viewportWidth; }
        public int viewportHeight() { return viewportHeight; }
        public int getViewportHeight() { return viewportHeight; }
    }

    record SessionSnapshot(boolean active, boolean completed, String message,
                           String screenshot, int viewportWidth, int viewportHeight) { }

    LaunchResult openVerificationWindow(Long accountId);

    SessionSnapshot getSession(Long accountId, String sessionId);

    boolean handleMouseEvent(Long accountId, String sessionId, String eventType, double x, double y);

    void closeSession(Long accountId, String sessionId);
}
