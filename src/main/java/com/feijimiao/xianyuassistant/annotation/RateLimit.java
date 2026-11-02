package com.feijimiao.xianyuassistant.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 写操作限流注解（滑动窗口，按账号 + 桶隔离）。
 *
 * <p>参考 goofish-cli 的令牌桶限流思路：卖家写操作（发布/下架/改价）默认
 * 每分钟 1 次，避免高频写触发平台风控。使用方式：</p>
 *
 * <pre>{@code
 * @RateLimit(bucket = "item_publish", accountParam = "accountId")
 * public PublishRespDTO publish(Long accountId, PublishReqDTO req) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流桶名（如 item_publish / item_off_shelf / batch_config），不同桶独立计数。 */
    String bucket() default "write";

    /** 窗口内允许的最大次数。 */
    int limit() default 1;

    /** 滑动窗口时长（秒）。 */
    int windowSeconds() default 60;

    /** 被限流时返回给用户的可读提示。 */
    String message() default "操作过于频繁，请稍后再试";
}
