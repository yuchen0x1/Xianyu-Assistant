package com.feijimiao.xianyuassistant.service.impl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualCaptchaBrowserServiceImplTest {

    @Test
    void parsesCookieValuesContainingEqualsSigns() {
        Map<String, String> cookies = ManualCaptchaBrowserServiceImpl.parseCookieText(
                "unb=buyer-1; token=abc==; x5sec=old-value");

        assertEquals("buyer-1", cookies.get("unb"));
        assertEquals("abc==", cookies.get("token"));
        assertEquals("old-value", cookies.get("x5sec"));
    }

    @Test
    void detectsAChangedVerificationCookie() {
        Map<String, String> cookies = new LinkedHashMap<>();
        cookies.put("x5sec", "new-value");

        assertTrue(ManualCaptchaBrowserServiceImpl.verificationCompleted(
                "old-value", cookies,
                "https://h5api.m.goofish.com/punish?x5secdata=one",
                "https://h5api.m.goofish.com/punish?x5secdata=one"));
    }

    @Test
    void doesNotTreatAnUnchangedVerificationCookieAsSuccess() {
        Map<String, String> cookies = new LinkedHashMap<>();
        cookies.put("x5sec", "same-value");

        assertFalse(ManualCaptchaBrowserServiceImpl.verificationCompleted(
                "same-value", cookies,
                "https://h5api.m.goofish.com/punish?x5secdata=one",
                "https://h5api.m.goofish.com/punish?x5secdata=one"));
    }

    @Test
    void detectsRedirectBackToGoofishAfterVerification() {
        assertTrue(ManualCaptchaBrowserServiceImpl.verificationCompleted(
                "same-value", Map.of("x5sec", "same-value"),
                "https://h5api.m.goofish.com/punish?x5secdata=one",
                "https://www.goofish.com/im"));
    }

    @Test
    void detectsRiskCookieRefreshEvenWhenX5secStaysTheSame() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("x5sec", "same-value");
        original.put("tfstk", "before");
        Map<String, String> current = new LinkedHashMap<>(original);
        current.put("tfstk", "after");

        assertTrue(ManualCaptchaBrowserServiceImpl.verificationCompleted(
                original,
                current,
                "https://h5api.m.goofish.com/punish?x5secdata=one",
                "https://www.goofish.com/im"));
    }

    @Test
    void doesNotTreatAnUnrelatedCookieAsCaptchaCompletion() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("x5sec", "same-value");
        original.put("tracking", "before");
        Map<String, String> current = new LinkedHashMap<>(original);
        current.put("tracking", "after");

        assertFalse(ManualCaptchaBrowserServiceImpl.verificationCompleted(
                original,
                current,
                "https://h5api.m.goofish.com/punish?x5secdata=one",
                "https://h5api.m.goofish.com/punish?x5secdata=one"));
    }

    @Test
    void identifiesPlatformCaptchaErrorCode() {
        assertEquals("T73mam", ManualCaptchaBrowserServiceImpl.captchaErrorCode(
                "Oops... something's wrong. Please refresh and try again. error:T73mam"));
        assertEquals("M4HRm", ManualCaptchaBrowserServiceImpl.captchaErrorCode(
                "Oops... something's wrong. Please refresh and try again. error:M4HRm"));
        assertTrue(ManualCaptchaBrowserServiceImpl.isCaptchaErrorPageText(
                "Oops... something's wrong. Please refresh and try again."));
    }

    @Test
    void ignoresNormalCaptchaPageText() {
        assertFalse(ManualCaptchaBrowserServiceImpl.isCaptchaErrorPageText(
                "请完成滑块验证后继续"));
    }
}
