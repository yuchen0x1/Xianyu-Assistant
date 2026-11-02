package com.feijimiao.xianyuassistant.adapter.xianyu;

import com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 闲鱼协议适配层默认实现。
 *
 * <p>所有方法委托给内部协议工具；任何异常都会被捕获并记录结构化降级日志
 * （“接口失效降级”），对外返回失败结果而非抛出异常，保证业务侧不因平台
 * 协议异常而整体失败。</p>
 */
@Slf4j
@Component
public class XianyuAdapterImpl implements XianyuAdapter {

    @Autowired
    private XianyuApiCallUtils xianyuApiCallUtils;

    @Override
    public XianyuApiCallUtils.ApiCallResult callApi(Long accountId, String apiName,
                                                    Map<String, Object> dataMap, String cookiesStr) {
        return callApi(accountId, apiName, dataMap, cookiesStr, null, null, null);
    }

    @Override
    public XianyuApiCallUtils.ApiCallResult callApi(Long accountId, String apiName,
                                                    Map<String, Object> dataMap, String cookiesStr,
                                                    Map<String, String> extraHeaders) {
        return callApi(accountId, apiName, dataMap, cookiesStr, extraHeaders, null, null);
    }

    @Override
    public XianyuApiCallUtils.ApiCallResult callApi(Long accountId, String apiName,
                                                    Map<String, Object> dataMap, String cookiesStr,
                                                    Map<String, String> extraHeaders,
                                                    Map<String, String> extraQueryParams) {
        return callApi(accountId, apiName, dataMap, cookiesStr, extraHeaders, extraQueryParams, null);
    }

    @Override
    public XianyuApiCallUtils.ApiCallResult callApi(Long accountId, String apiName,
                                                    Map<String, Object> dataMap, String cookiesStr,
                                                    String apiVersion) {
        return callApi(accountId, apiName, dataMap, cookiesStr, null, null, apiVersion);
    }

    private XianyuApiCallUtils.ApiCallResult callApi(Long accountId, String apiName,
                                                     Map<String, Object> dataMap, String cookiesStr,
                                                     Map<String, String> extraHeaders,
                                                     Map<String, String> extraQueryParams,
                                                     String apiVersion) {
        try {
            if (apiVersion != null) {
                return xianyuApiCallUtils.callApiWithRetry(accountId, apiName, dataMap, cookiesStr, apiVersion);
            }
            if (extraHeaders == null && extraQueryParams == null) {
                return xianyuApiCallUtils.callApiWithRetry(accountId, apiName, dataMap, cookiesStr);
            }
            if (extraQueryParams == null) {
                return xianyuApiCallUtils.callApiWithRetry(accountId, apiName, dataMap, cookiesStr, extraHeaders);
            }
            return xianyuApiCallUtils.callApiWithRetry(accountId, apiName, dataMap, cookiesStr, extraHeaders, extraQueryParams);
        } catch (Exception e) {
            // 降级时必须保持"结果未知"语义：底层请求可能已送达闲鱼，判为确定失败会让
            // 上层回滚卡密，形成重复发货。outcomeUnknown=true 只阻断自动回滚，不影响降级本身。
            log.warn("【闲鱼适配层】API接口失效降级（结果未知）: apiName={}, accountId={}, errorType={}, errorMessage={}",
                    apiName, accountId, e.getClass().getSimpleName(), e.getMessage());
            return new XianyuApiCallUtils.ApiCallResult(false, null, "adapter-degraded: " + e.getMessage(), false, true);
        }
    }
}