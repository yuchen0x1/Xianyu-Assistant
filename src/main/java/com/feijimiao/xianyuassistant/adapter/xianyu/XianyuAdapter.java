package com.feijimiao.xianyuassistant.adapter.xianyu;

import com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils;

import java.util.Map;

/**
 * 闲鱼协议适配层（对外稳定接口）。
 *
 * <p>所有面向闲鱼平台的协议调用（H5 API、WebSocket 消息等）应通过本接口进入，
 * 平台契约变更时只需调整 {@link XianyuAdapterImpl} 内部实现，业务层不感知。
 * 旧的 {@code utils/XianyuApiCallUtils}、{@code websocket/XianyuWebSocketClient}、
 * {@code constants/XianyuProtocolProfile} 属于内部实现细节，仅供适配层调用。</p>
 */
public interface XianyuAdapter {

    /**
     * 调用闲鱼 H5 API（4 参数版）。
     *
     * @return API调用结果；接口失效降级时返回失败结果并记录降级日志，不抛异常
     */
    XianyuApiCallUtils.ApiCallResult callApi(Long accountId, String apiName,
                                             Map<String, Object> dataMap, String cookiesStr);

    /**
     * 调用闲鱼 H5 API（携带额外请求头）。
     */
    XianyuApiCallUtils.ApiCallResult callApi(Long accountId, String apiName,
                                             Map<String, Object> dataMap, String cookiesStr,
                                             Map<String, String> extraHeaders);

    /**
     * 调用闲鱼 H5 API（携带额外请求头与额外查询参数）。
     */
    XianyuApiCallUtils.ApiCallResult callApi(Long accountId, String apiName,
                                             Map<String, Object> dataMap, String cookiesStr,
                                             Map<String, String> extraHeaders,
                                             Map<String, String> extraQueryParams);

    /**
     * 调用闲鱼 H5 API（显式指定协议版本号）。
     */
    XianyuApiCallUtils.ApiCallResult callApi(Long accountId, String apiName,
                                             Map<String, Object> dataMap, String cookiesStr,
                                             String apiVersion);
}