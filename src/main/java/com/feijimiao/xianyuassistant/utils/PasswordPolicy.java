package com.feijimiao.xianyuassistant.utils;

/**
 * 口令策略单一来源。
 *
 * <p>注册（{@code LoginController}）与改密（{@code SystemController}）历史上各写一套长度校验，
 * 注册已收紧到 8 位而改密仍允许 6 位，导致「注册时被拒的口令可以改密设进去」，
 * 弱口令策略可以从改密入口绕过。策略集中在常量与校验方法里，两处共用。</p>
 */
public final class PasswordPolicy {

    /** 口令最小长度。8 位是本项目对初始管理员账号设定的强度底线。 */
    public static final int MIN_LENGTH = 8;

    /** 口令最大长度（与前端输入框及数据库列一致）。 */
    public static final int MAX_LENGTH = 50;

    private PasswordPolicy() {
    }

    /**
     * 校验口令长度。
     *
     * @return 合规返回 {@code null}；不合规返回可直接展示给用户的提示文案
     */
    public static String validateLength(String password) {
        if (password == null || password.isEmpty()) {
            return "密码不能为空";
        }
        int length = password.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return "密码长度需在" + MIN_LENGTH + "-" + MAX_LENGTH + "之间";
        }
        return null;
    }

    /** 口令是否满足长度要求。 */
    public static boolean isValidLength(String password) {
        return validateLength(password) == null;
    }
}
