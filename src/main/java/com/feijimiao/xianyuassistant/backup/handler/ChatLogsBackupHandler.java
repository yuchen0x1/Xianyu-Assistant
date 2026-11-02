package com.feijimiao.xianyuassistant.backup.handler;

import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuChatMessage;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuChatMessageMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 聊天记录备份：只导出元数据/脱敏内容，不上传完整消息体与买家可识别身份字段。
 */
@Slf4j
@Component
public class ChatLogsBackupHandler implements DataBackupHandler {

    /** 游标翻页的页大小：500 条大约几百 KB，既不让单页撑爆堆，也不至于查询过碎。 */
    private static final int EXPORT_BATCH_SIZE = 500;

    @Autowired
    private XianyuChatMessageMapper chatMessageMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Override
    public String getModuleKey() {
        return "chatLogs";
    }

    @Override
    public String getModuleName() {
        return "聊天记录";
    }

    @Override
    public Set<String> getRequiredImportListKeys() {
        return Set.of("messages");
    }

    @Override
    public Map<String, Object> exportData() {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<Long, String> accountUnb = loadAccountUnbMap();
        long afterId = 0L;
        while (true) {
            List<XianyuChatMessage> batch = chatMessageMapper.selectBatchAfterId(afterId, EXPORT_BATCH_SIZE);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (XianyuChatMessage message : batch) {
                result.add(toExportMap(message, accountUnb));
            }
            afterId = batch.get(batch.size() - 1).getId();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messages", result);
        return data;
    }

    @Override
    public boolean supportsStreamingExport() {
        return true;
    }

    /**
     * 流式导出：分页读、逐条写，内存峰值只跟单页大小有关。
     *
     * <p>旧实现有两处会把大账号拖死：一是把整表实体读进 `List`，再构造一份
     * 等大的 `List<Map>`，峰值约为数据量的 2~3 倍；二是每条消息都调一次
     * `accountMapper.selectById` 取 unb（N+1），十万条消息就是十万次查询，
     * 导出会跑成"看起来卡住"。现在 unb 一次预取成 Map，消息按主键游标翻页。</p>
     */
    @Override
    public void exportJson(JsonGenerator generator, Map<String, Object> context) throws IOException {
        Map<Long, String> accountUnb = loadAccountUnbMap();
        generator.writeStartObject();
        generator.writeArrayFieldStart("messages");
        long afterId = 0L;
        long exported = 0L;
        while (true) {
            List<XianyuChatMessage> batch = chatMessageMapper.selectBatchAfterId(afterId, EXPORT_BATCH_SIZE);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (XianyuChatMessage message : batch) {
                generator.writeObject(toExportMap(message, accountUnb));
            }
            exported += batch.size();
            afterId = batch.get(batch.size() - 1).getId();
            if (exported % (EXPORT_BATCH_SIZE * 20L) == 0) {
                log.info("[Backup] 聊天记录已导出 {} 条（游标 id={}）", exported, afterId);
            }
        }
        generator.writeEndArray();
        generator.writeEndObject();
        log.info("[Backup] 聊天记录导出完成: {} 条", exported);
    }

    /**
     * 一次性预取「账号ID → unb」，替代原先每条消息一次查询。
     * 账号表很小，整体读进内存的代价远低于 N+1。
     */
    private Map<Long, String> loadAccountUnbMap() {
        List<XianyuAccount> accounts = accountMapper.selectList(null);
        Map<Long, String> map = new HashMap<>(Math.max(16, accounts.size() * 2));
        for (XianyuAccount account : accounts) {
            if (account.getId() != null && account.getUnb() != null && !account.getUnb().isBlank()) {
                map.put(account.getId(), account.getUnb());
            }
        }
        return map;
    }

    private Map<String, Object> toExportMap(XianyuChatMessage message, Map<Long, String> accountUnb) {
        Long accountId = message.getXianyuAccountId();
        if (accountId == null) {
            throw new IllegalStateException("聊天记录缺少账号ID");
        }
        String unb = accountUnb.get(accountId);
        if (unb == null) {
            throw new IllegalStateException("聊天记录找不到关联账号: " + accountId);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("unb", unb);
        map.put("pnmId", message.getPnmId());
        map.put("sId", message.getSId());
        map.put("contentType", message.getContentType());
        // 脱敏导出：保留消息类型/发送者名，不上传完整消息体与用户ID/链接。
        map.put("msgContent", message.getMsgContent());
        map.put("senderUserName", message.getSenderUserName());
        map.put("xyGoodsId", message.getXyGoodsId());
        map.put("reminderUrl", null);
        map.put("senderUserId", null);
        map.put("completeMsg", null);
        map.put("messageTime", message.getMessageTime());
        return map;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Long> unbToAccountId = context.get("unbToAccountId") != null
                ? (Map<String, Long>) context.get("unbToAccountId")
                : Collections.emptyMap();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messageMaps = (List<Map<String, Object>>) data.get("messages");
        if (messageMaps == null) return;

        for (Map<String, Object> map : messageMaps) {
            try {
                String unb = (String) map.get("unb");
                if (unb == null || unb.isBlank()) {
                    throw new IllegalArgumentException("聊天记录缺少账号标识");
                }
                Long accountId = unbToAccountId.get(unb);
                if (accountId == null) {
                    throw new IllegalStateException("找不到聊天记录关联账号: " + unb);
                }

                String pnmId = (String) map.get("pnmId");
                XianyuChatMessage message = new XianyuChatMessage();
                message.setXianyuAccountId(accountId);
                message.setPnmId(pnmId);
                message.setSId((String) map.get("sId"));
                message.setContentType(integerValue(map.get("contentType")));
                message.setMsgContent((String) map.get("msgContent"));
                message.setSenderUserName((String) map.get("senderUserName"));
                message.setSenderUserId((String) map.get("senderUserId"));
                message.setReminderUrl((String) map.get("reminderUrl"));
                message.setXyGoodsId((String) map.get("xyGoodsId"));
                message.setCompleteMsg((String) map.get("completeMsg"));
                message.setMessageTime(longValue(map.get("messageTime")));
                message.setCreateTime(LocalDateTime.now());

                XianyuChatMessage existing = chatMessageMapper.findByPnmId(accountId, pnmId);
                if (existing == null) {
                    chatMessageMapper.insert(message);
                } else if (message.getMsgContent() != null) {
                    // 仅当消息内容相同或为空时跳过；导入以第一条消息内容为准，不覆盖更新。
                    if (!message.getMsgContent().equals(existing.getMsgContent())) {
                        log.debug("聊天记录已存在且内容不一致，保留已有内容: pnmId={}", pnmId);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("导入单条聊天记录失败", e);
            }
        }
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
}