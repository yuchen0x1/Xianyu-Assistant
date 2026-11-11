package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoReplyRecord;

import java.util.List;

/**
 * 自动回复记录服务
 */
public interface AutoReplyRecordService {

    /**
     * 分页查询自动回复记录
     */
    List<XianyuGoodsAutoReplyRecord> selectByAccountIdAndGoodsId(Long accountId, String xyGoodsId, int pageSize, int offset);

    /**
     * 统计某账号某商品的回复记录总数
     */
    int countByAccountIdAndGoodsId(Long accountId, String xyGoodsId);

    /**
     * 按日期统计AI回复数（按账号范围过滤；调用方必须显式传入范围，避免跨账号统计）。
     */
    int countAiRepliesByDateScoped(String date, DataPanelAccountScope scope);

    /**
     * 按账号分组的AI回复统计（指定日期，按账号范围过滤）。
     */
    List<java.util.Map<String, Object>> getAccountRepliesByDateScoped(String date, DataPanelAccountScope scope);

    /**
     * 统计所有回复记录数（按账号范围过滤）。
     */
    int countAllRepliesScoped(DataPanelAccountScope scope);

    /**
     * 分页查询待人工确认的记录（state=2）（state=2）
     */
    List<XianyuGoodsAutoReplyRecord> selectPendingConfirm(Long accountId, int pageSize, int offset);

    /**
     * 统计待人工确认的记录数（state=2）
     */
    int countPendingConfirm(Long accountId);
}
