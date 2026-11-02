package com.feijimiao.xianyuassistant.exception;

/**
 * 闲鱼接口临时繁忙。该状态不能被当作Cookie失效或人工滑块验证。
 */
public class XianyuServiceBusyException extends RuntimeException {

    public XianyuServiceBusyException() {
        super("闲鱼服务暂时繁忙，请稍后重试");
    }
}
