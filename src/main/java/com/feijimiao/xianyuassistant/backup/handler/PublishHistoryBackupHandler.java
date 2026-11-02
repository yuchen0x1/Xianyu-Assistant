package com.feijimiao.xianyuassistant.backup.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuPublishHistory;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuPublishHistoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 发布历史备份：记录商品发布/下架操作轨迹，导入时按账号幂等重建。
 */
@Slf4j
@Component
public class PublishHistoryBackupHandler implements DataBackupHandler {

    @Autowired
    private XianyuPublishHistoryMapper publishHistoryMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Override
    public String getModuleKey() {
        return "publishHistory";
    }

    @Override
    public String getModuleName() {
        return "发布历史";
    }

    @Override
    public Set<String> getRequiredImportListKeys() {
        return Set.of("publishRecords");
    }

    @Override
    public Map<String, Object> exportData() {
        List<XianyuPublishHistory> records = publishHistoryMapper.selectList(null);

        List<Map<String, Object>> result = new ArrayList<>();
        for (XianyuPublishHistory record : records) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", findAccountUnb(record.getAccountId()));
            map.put("accountNote", record.getAccountNote());
            map.put("title", record.getTitle());
            map.put("description", record.getDescription());
            map.put("price", record.getPrice());
            map.put("quantity", record.getQuantity());
            map.put("imageCount", record.getImageCount());
            map.put("categoryId", record.getCategoryId());
            map.put("itemId", record.getItemId());
            map.put("operationType", record.getOperationType());
            map.put("status", record.getStatus());
            map.put("errorMessage", record.getErrorMessage());
            map.put("rawResponse", record.getRawResponse());
            map.put("createdTime", record.getCreatedTime());
            result.add(map);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("publishRecords", result);
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Long> unbToAccountId = context.get("unbToAccountId") != null
                ? (Map<String, Long>) context.get("unbToAccountId")
                : Collections.emptyMap();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recordMaps = (List<Map<String, Object>>) data.get("publishRecords");
        if (recordMaps == null) return;

        for (Map<String, Object> map : recordMaps) {
            try {
                String unb = (String) map.get("unb");
                if (unb == null || unb.isBlank()) {
                    throw new IllegalArgumentException("发布记录缺少账号标识");
                }
                Long accountId = unbToAccountId.get(unb);
                if (accountId == null) {
                    throw new IllegalStateException("找不到发布记录关联账号: " + unb);
                }

                Long id = longValue(map.get("id"));
                XianyuPublishHistory existing = id != null
                        ? publishHistoryMapper.selectById(id)
                        : null;

                XianyuPublishHistory record = new XianyuPublishHistory();
                record.setAccountId(accountId);
                record.setAccountNote((String) map.get("accountNote"));
                record.setTitle((String) map.get("title"));
                record.setDescription((String) map.get("description"));
                record.setPrice(bigDecimalValue(map.get("price")));
                record.setQuantity(integerValue(map.get("quantity")));
                record.setImageCount(integerValue(map.get("imageCount")));
                record.setCategoryId((String) map.get("categoryId"));
                record.setItemId((String) map.get("itemId"));
                record.setOperationType((String) map.get("operationType"));
                record.setStatus((String) map.get("status"));
                record.setErrorMessage((String) map.get("errorMessage"));
                record.setRawResponse((String) map.get("rawResponse"));
                record.setCreatedTime(timeValue(map.get("createdTime")));

                if (existing == null) {
                    record.setId(id);
                    publishHistoryMapper.insert(record);
                } else {
                    record.setId(existing.getId());
                    publishHistoryMapper.updateById(record);
                }
            } catch (Exception e) {
                throw new IllegalStateException("导入单条发布记录失败", e);
            }
        }
    }

    private String findAccountUnb(Long accountId) {
        if (accountId == null) {
            throw new IllegalStateException("发布记录缺少账号ID");
        }
        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null || account.getUnb() == null || account.getUnb().isBlank()) {
            throw new IllegalStateException("发布记录找不到关联账号: " + accountId);
        }
        return account.getUnb();
    }

    private Integer integerValue(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        return Long.valueOf(value.toString());
    }

    private java.math.BigDecimal bigDecimalValue(Object value) {
        if (value == null) return null;
        if (value instanceof java.math.BigDecimal bd) return bd;
        if (value instanceof Number n) return java.math.BigDecimal.valueOf(n.doubleValue());
        return new java.math.BigDecimal(value.toString());
    }

    private java.time.LocalDateTime timeValue(Object value) {
        if (value == null) return null;
        if (value instanceof java.time.LocalDateTime ldt) return ldt;
        String str = value.toString();
        if (str.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(str);
        } catch (Exception e) {
            try {
                return java.time.LocalDateTime.parse(str,
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e2) {
                return null;
            }
        }
    }
}