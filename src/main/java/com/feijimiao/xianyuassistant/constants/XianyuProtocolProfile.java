package com.feijimiao.xianyuassistant.constants;

/**
 * 闲鱼兼容协议的集中配置。
 *
 * <p>这些值来自当前公开网页/开源客户端中仍在使用的请求契约。
 * 它们不是官方稳定 SDK；平台变更时只应在这里集中调整，并通过测试和小范围账号验证后发布。</p>
 */
public final class XianyuProtocolProfile {

    private XianyuProtocolProfile() {
    }

    public static final String H5_BASE_URL = "https://h5api.m.goofish.com/h5/";
    public static final String H5_ORIGIN = "https://h5api.m.goofish.com";
    public static final String H5_HOST = "h5api.m.goofish.com";

    public static final String H5_API_VERSION = "1.0";
    public static final String H5_JSV = "2.7.2";
    public static final String H5_APP_KEY = "34839810";

    public static final String WEB_ORIGIN = "https://www.goofish.com";
    public static final String WEB_REFERER = WEB_ORIGIN + "/";

    /** 官方个人主页商品接口使用的页面来源与首屏 SPM。 */
    public static final String PERSONAL_PAGE_REFERER = WEB_ORIGIN + "/personal";
    public static final String PERSONAL_PAGE_SPM_CNT = "a21ybx.personal.0.0";

    public static final String WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";
    public static final String WEB_ACCEPT_LANGUAGE =
            "en,zh-CN;q=0.9,zh;q=0.8,zh-TW;q=0.7,ja;q=0.6";
    public static final String WEB_SEC_CH_UA =
            "\"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"";

    public static final String WEBSOCKET_URL = "wss://wss-goofish.dingtalk.com/";
    public static final String WEBSOCKET_HOST = "wss-goofish.dingtalk.com";
    public static final String WEBSOCKET_APP_KEY = "444e9908a51d1cb236a27862abc769c9";
    public static final String WEBSOCKET_USER_AGENT = WEB_USER_AGENT
            + " DingTalk(2.1.5) OS(Windows/10) Browser(Chrome/147.0.0.0)"
            + " DingWeb/2.1.5 IMPaaS DingWeb/2.1.5";
    public static final String WEBSOCKET_WV = "im:3,au:3,sy:6";

    public static final String TOKEN_API = "mtop.taobao.idlemessage.pc.login.token";
    public static final String H5_TOKEN_REFRESH_API =
            "mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get";

    // ============ 卖家运营 API（参考 goofish-cli 契约） ============

    /** 商品发布：mtop.idle.pc.idleitem.publish v1.0 */
    public static final String ITEM_PUBLISH_API = "mtop.idle.pc.idleitem.publish";
    public static final String ITEM_PUBLISH_API_VERSION = "1.0";

    /** 商品下架/删除：com.taobao.idle.item.delete v1.1 */
    public static final String ITEM_DELETE_API = "com.taobao.idle.item.delete";
    public static final String ITEM_DELETE_API_VERSION = "1.1";

    /** AI 类目识别：mtop.taobao.idle.kgraph.property.recommend v2.0 */
    public static final String CATEGORY_RECOMMEND_API = "mtop.taobao.idle.kgraph.property.recommend";
    public static final String CATEGORY_RECOMMEND_API_VERSION = "2.0";

    /** 默认地址查询：mtop.taobao.idle.local.poi.get v1.0 */
    public static final String LOCAL_POI_API = "mtop.taobao.idle.local.poi.get";
    public static final String LOCAL_POI_API_VERSION = "1.0";

    /** 图片上传 CDN：stream-upload.goofish.com/api/upload.api（multipart，appkey=xy_chat） */
    public static final String MEDIA_UPLOAD_URL = "https://stream-upload.goofish.com/api/upload.api";
    public static final String MEDIA_UPLOAD_APP_KEY = "xy_chat";

    /** 连续认证失败后的保护窗口，防止后台重连风暴。 */
    public static final int AUTH_FAILURE_THRESHOLD = 3;
    public static final long AUTH_FAILURE_WINDOW_MS = 60_000L;
    public static final long AUTH_FAILURE_COOLDOWN_MS = 120_000L;

    /** RGV587 等明确风控错误的熔断时长：无需等待阈值，立即进入熔断（分级更严）。 */
    public static final long RISK_CONTROL_COOLDOWN_MS = 300_000L;

    // ============ P0-6 账号级自动化限速与并发 ============

    /** 单账号每分钟最大 API 调用次数（滑动窗口），超过则拒绝并提示稍后重试。 */
    public static final int ACCOUNT_RATE_LIMIT_PER_MIN = 30;

    /** 单账号最大并发 API 调用数，超过则拒绝新请求，防止触发平台风控。 */
    public static final int ACCOUNT_MAX_CONCURRENCY = 5;
}
