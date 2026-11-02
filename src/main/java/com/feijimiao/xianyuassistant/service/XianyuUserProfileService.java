package com.feijimiao.xianyuassistant.service;

/**
 * 闲鱼账号资料（昵称/头像/信用分）抓取服务。
 * 资料来源于官方个人主页接口，失败时不抛异常，由调用方决定回退行为。
 */
public interface XianyuUserProfileService {

    /**
     * 拉取并回填指定账号的闲鱼资料。
     *
     * @return 至少一个字段更新成功时返回 true；Cookie缺失、接口失败或响应无资料字段时返回 false
     */
    boolean refreshAccountProfile(Long accountId);
}
