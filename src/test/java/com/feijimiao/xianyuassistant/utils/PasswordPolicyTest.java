package com.feijimiao.xianyuassistant.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 口令策略测试。
 *
 * <p>核心约束：注册与改密共用同一长度口径。历史上改密允许 6 位而注册要求 8 位，
 * 弱口令可以从改密入口绕过注册时的强度要求。</p>
 */
class PasswordPolicyTest {

    @Test
    void rejectsPasswordsShorterThanMinimum() {
        assertThat(PasswordPolicy.validateLength("1234567890".substring(0, 6)))
                .as("6 位口令必须被拒绝（旧改密接口曾放行）")
                .isNotNull();
        assertThat(PasswordPolicy.isValidLength("1234567")).isFalse();
    }

    @Test
    void acceptsPasswordsAtMinimumBoundary() {
        String eightChars = "12345678";
        assertThat(PasswordPolicy.validateLength(eightChars)).isNull();
        assertThat(PasswordPolicy.isValidLength(eightChars)).isTrue();
    }

    @Test
    void rejectsPasswordsLongerThanMaximum() {
        String tooLong = "a".repeat(PasswordPolicy.MAX_LENGTH + 1);
        assertThat(PasswordPolicy.validateLength(tooLong)).isNotNull();
    }

    @Test
    void rejectsBlankPassword() {
        assertThat(PasswordPolicy.validateLength(null)).isNotNull();
        assertThat(PasswordPolicy.validateLength("")).isNotNull();
    }

    @Test
    void minimumLengthMatchesRegistrationBaseline() {
        assertThat(PasswordPolicy.MIN_LENGTH)
                .as("注册基线为 8 位，改密不得更宽松")
                .isEqualTo(8);
    }
}
