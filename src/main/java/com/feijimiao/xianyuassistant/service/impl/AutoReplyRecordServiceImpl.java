package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoReplyRecord;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.feijimiao.xianyuassistant.service.AutoReplyRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AutoReplyRecordServiceImpl implements AutoReplyRecordService {

    @Autowired
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;

    @Override
    public List<XianyuGoodsAutoReplyRecord> selectByAccountIdAndGoodsId(Long accountId, String xyGoodsId, int pageSize, int offset) {
        if (accountId == null || xyGoodsId == null) {
            return java.util.Collections.emptyList();
        }
        return autoReplyRecordMapper.selectByAccountIdAndGoodsId(accountId, xyGoodsId, pageSize, offset);
    }

    @Override
    public int countByAccountIdAndGoodsId(Long accountId, String xyGoodsId) {
        if (accountId == null || xyGoodsId == null) {
            return 0;
        }
        return autoReplyRecordMapper.countByAccountIdAndGoodsId(accountId, xyGoodsId);
    }

    @Override
    public int countAiRepliesByDateScoped(String date, com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return autoReplyRecordMapper.countAiRepliesByDateScoped(date, scope);
    }

    @Override
    public List<java.util.Map<String, Object>> getAccountRepliesByDateScoped(String date, com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return autoReplyRecordMapper.selectAccountRepliesByDateScoped(date, scope);
    }

    @Override
    public int countAllRepliesScoped(com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope) {
        return autoReplyRecordMapper.countAllRepliesScoped(scope);
    }


    @Override
    public List<XianyuGoodsAutoReplyRecord> selectPendingConfirm(Long accountId, int pageSize, int offset) {
        if (accountId == null) {
            return java.util.Collections.emptyList();
        }
        return autoReplyRecordMapper.selectPendingConfirm(accountId, pageSize, offset);
    }

    @Override
    public int countPendingConfirm(Long accountId) {
        if (accountId == null) {
            return 0;
        }
        return autoReplyRecordMapper.countPendingConfirm(accountId);
    }
}
