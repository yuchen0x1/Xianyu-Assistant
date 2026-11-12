package com.feijimiao.xianyuassistant.service.reply;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeywordMatchUtilsTest {

    @Test
    void fuzzyAndExactModesSupportMultipleExpressions() {
        assertTrue(KeywordMatchUtils.matches("你好,在吗", KeywordMatchUtils.FUZZY, "买家说：在吗？"));
        assertTrue(KeywordMatchUtils.matches("你好， 在吗", KeywordMatchUtils.EXACT, "在吗"));
        assertFalse(KeywordMatchUtils.matches("你好,在吗", KeywordMatchUtils.EXACT, "买家说：在吗？"));
    }

    @Test
    void regexModeUsesFindAndIgnoresInvalidExpression() {
        assertTrue(KeywordMatchUtils.matches("(价格|多少钱).*", KeywordMatchUtils.REGEX, "这个多少钱？"));
        assertFalse(KeywordMatchUtils.matches("[", KeywordMatchUtils.REGEX, "任意消息"));
        assertFalse(KeywordMatchUtils.isValidRegex("["));
        assertTrue(KeywordMatchUtils.isValidRegex("价格.*，多少钱.*"));
    }
}
