package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.config.rag.DynamicAIChatClientManager;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoReplyRecord;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import com.feijimiao.xianyuassistant.entity.XianyuChatMessage;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.entity.bo.AutoReplyTriggerContext;
import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsInfoMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuChatMessageMapper;
import com.feijimiao.xianyuassistant.service.AIService;
import com.feijimiao.xianyuassistant.service.AutoReplyDecisionService;
import com.feijimiao.xianyuassistant.service.AutoReplyService;
import com.feijimiao.xianyuassistant.service.AutomationHealthService;
import com.feijimiao.xianyuassistant.service.MessageSendResult;
import com.feijimiao.xianyuassistant.service.WebSocketService;
import com.feijimiao.xianyuassistant.service.bo.RAGReplyResult;
import com.feijimiao.xianyuassistant.service.reply.ReplyStrategy;
import com.feijimiao.xianyuassistant.service.reply.ReplyStrategyResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class AutoReplyServiceImpl implements AutoReplyService {
    
    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;
    
    @Autowired
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;

    @Autowired
    private com.feijimiao.xianyuassistant.rbac.service.RbacService rbacService;
    
    @Autowired
    private XianyuGoodsInfoMapper goodsInfoMapper;
    
    @Autowired
    private XianyuChatMessageMapper chatMessageMapper;
    
    @Autowired
    private WebSocketService webSocketService;
    
    @Autowired(required = false)
    private AIService aiService;

    @Autowired
    private DynamicAIChatClientManager dynamicAIChatClientManager;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.SentMessageSaveService sentMessageSaveService;

    @Autowired
    private AutoReplyDecisionService autoReplyDecisionService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.AccountService accountService;

    @Autowired
    private ReplyStrategyResolver replyStrategyResolver;

    @Autowired
    private AutomationHealthService automationHealthService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.NotificationService notificationService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public void executeAutoReply(ChatMessageData messageData) {
        if (messageData == null) {
            log.warn("消息数据为空，无法执行自动回复");
            return;
        }
        executeAutoReplyWithOutcome(Collections.singletonList(messageData));
    }

    @Override
    public void executeAutoReply(List<ChatMessageData> messageList) {
        executeAutoReplyWithOutcome(messageList);
    }

    @Override
    public ReplyOutcome executeAutoReplyWithOutcome(List<ChatMessageData> messageList) {
        if (messageList == null || messageList.isEmpty()) {
            log.warn("消息列表为空，无法执行自动回复");
            return ReplyOutcome.SKIPPED;
        }
        
        ChatMessageData lastMessage = messageList.get(messageList.size() - 1);
        Long accountId = lastMessage.getXianyuAccountId();
        String xyGoodsId = lastMessage.getXyGoodsId();
        String sId = lastMessage.getSId();
        String pnmId = lastMessage.getPnmId();
        
        String buyerMessage = messageList.stream()
                .map(ChatMessageData::getMsgContent)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        
        log.info("【账号{}】开始执行自动回复: xyGoodsId={}, sId={}, 触发消息数={}, buyerMessageLength={}",
                accountId, xyGoodsId, sId, messageList.size(), buyerMessage.length());
        automationHealthService.recordReplyState(accountId, "RUNNING", "GENERATING_REPLY");
        
        try {
            // 1. 检查是否有任何回复开关开启
            if (!isAnyReplyEnabled(accountId, xyGoodsId)) {
                automationHealthService.recordReplyState(accountId, "SKIPPED", "REPLY_DISABLED");
                log.info("【账号{}】商品未开启任何回复开关: xyGoodsId={}", accountId, xyGoodsId);
                return ReplyOutcome.SKIPPED;
            }
            
            // 2. 获取商品本地ID
            XianyuGoodsInfo goodsInfo = goodsInfoMapper.selectOne(
                    new LambdaQueryWrapper<XianyuGoodsInfo>()
                            .eq(XianyuGoodsInfo::getXyGoodId, xyGoodsId)
                            .eq(XianyuGoodsInfo::getXianyuAccountId, accountId)
            );
            if (goodsInfo == null) {
                automationHealthService.recordReplyState(accountId, "SKIPPED", "GOODS_NOT_FOUND");
                log.warn("【账号{}】未找到商品信息: xyGoodsId={}", accountId, xyGoodsId);
                autoReplyDecisionService.recordSkip(accountId, sId, xyGoodsId, lastMessage.getSenderUserId(),
                        buyerMessage, AutoReplyDecisionService.REASON_GOODS_NOT_FOUND, "商品未同步到本地，请先同步商品");
                return ReplyOutcome.SKIPPED;
            }

            // 3. 解析回复策略
            ReplyStrategy strategy = replyStrategyResolver.resolve(messageList);
            if (strategy == null) {
                automationHealthService.recordReplyState(accountId, "SKIPPED", "NO_REPLY_STRATEGY");
                log.info("【账号{}】无可用回复策略: xyGoodsId={}", accountId, xyGoodsId);
                autoReplyDecisionService.recordSkip(accountId, sId, xyGoodsId, lastMessage.getSenderUserId(),
                        buyerMessage, AutoReplyDecisionService.REASON_NO_REPLY_STRATEGY, "关键词回复与AI回复均未开启");
                return ReplyOutcome.SKIPPED;
            }
            
            // 4. 构建触发上下文
            AutoReplyTriggerContext triggerContext = new AutoReplyTriggerContext();
            List<AutoReplyTriggerContext.TriggerMessage> triggerMessages = new ArrayList<>();
            for (ChatMessageData msg : messageList) {
                AutoReplyTriggerContext.TriggerMessage tm = new AutoReplyTriggerContext.TriggerMessage();
                tm.setPnmId(msg.getPnmId());
                tm.setSenderUserId(msg.getSenderUserId());
                tm.setSenderUserName(msg.getSenderUserName());
                tm.setMsgContent(msg.getMsgContent());
                tm.setMessageTime(msg.getMessageTime());
                triggerMessages.add(tm);
            }
            triggerContext.setTriggerMessages(triggerMessages);
            
            // 5. 创建回复记录（状态=0，待回复）
            XianyuGoodsAutoReplyRecord record = new XianyuGoodsAutoReplyRecord();
            record.setXianyuAccountId(accountId);
            record.setXianyuGoodsId(goodsInfo.getId());
            record.setXyGoodsId(xyGoodsId);
            record.setSId(sId);
            record.setPnmId(pnmId);
            record.setBuyerUserId(lastMessage.getSenderUserId());
            record.setBuyerUserName(lastMessage.getSenderUserName());
            record.setBuyerMessage(buyerMessage);
            record.setState(0);
            
            int insertResult;
            try {
                insertResult = autoReplyRecordMapper.insert(record);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                    // 同一 pnmId 只能有一条记录（唯一索引）。上次若是发送失败（state=-1），
                    // 直接当重复消息跳过会让该买家永久收不到回复、看板却显示成功。
                    // 这里允许失败记录重开一次，继续走发送流程。
                    XianyuGoodsAutoReplyRecord existing =
                            autoReplyRecordMapper.selectByAccountSIdPnm(accountId, sId, pnmId);
                    if (existing != null && Integer.valueOf(-1).equals(existing.getState())
                            && autoReplyRecordMapper.reopenFailed(existing.getId()) > 0) {
                        record = existing;
                        insertResult = 1;
                        log.info("【账号{}】该消息上次自动回复失败，重新执行一次: recordId={}, pnmId={}",
                                accountId, existing.getId(), pnmId);
                    } else {
                        automationHealthService.recordReplyState(accountId, "SKIPPED", "DUPLICATE_MESSAGE");
                        log.info("【账号{}】该消息已处理过，跳过自动回复: sId={}, pnmId={}", accountId, sId, pnmId);
                        return ReplyOutcome.SKIPPED;
                    }
                } else {
                    throw e;
                }
            }

            if (insertResult <= 0) {
                automationHealthService.recordReplyState(accountId, "FAILED", "RECORD_CREATE_FAILED");
                log.error("【账号{}】创建回复记录失败", accountId);
                return ReplyOutcome.FAILED;
            }
            
            // 6. 执行回复策略
            ReplyStrategy.ReplyResult replyResult = strategy.execute(messageList);
            
            if (!replyResult.isSuccess() || replyResult.getItems() == null || replyResult.getItems().isEmpty()) {
                automationHealthService.recordReplyState(accountId, "FAILED", "NO_REPLY_CONTENT");
                log.warn("【账号{}】回复策略未生成有效内容", accountId);
                autoReplyDecisionService.recordSkip(accountId, sId, xyGoodsId, lastMessage.getSenderUserId(),
                        buyerMessage, AutoReplyDecisionService.REASON_NO_REPLY_CONTENT,
                        "策略已执行但未生成有效回复内容（请检查AI配置或关键词规则）");
                updateRecordState(record.getId(), -1, null);
                return ReplyOutcome.FAILED;
            }
            
            if (replyResult.getMatchedKeyword() != null) {
                record.setMatchedKeyword(replyResult.getMatchedKeyword());
            }
            
            String allReplyText = replyResult.getItems().stream()
                    .map(ReplyStrategy.ReplyResult.ReplyItem::getTextContent)
                    .filter(t -> t != null && !t.trim().isEmpty())
                    .collect(java.util.stream.Collectors.joining("\n"));
            record.setReplyType(replyResult.getItems().get(0).getReplyType());
            
            log.info("【账号{}】回复策略生成内容: type={}, keywordMatched={}, itemCount={}",
                    accountId, replyResult.getItems().get(0).getReplyType(), replyResult.getMatchedKeyword() != null,
                    replyResult.getItems().size());
            
            // 7. 保存触发上下文
            try {
                XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
                if (goodsConfig != null && goodsConfig.getFixedMaterial() != null && !goodsConfig.getFixedMaterial().isEmpty()) {
                    triggerContext.setFixedMaterial(goodsConfig.getFixedMaterial());
                }
                
                XianyuGoodsInfo goodsInfoForContext = goodsInfoMapper.selectOne(
                    new LambdaQueryWrapper<XianyuGoodsInfo>().eq(XianyuGoodsInfo::getXyGoodId, xyGoodsId)
                );
                if (goodsInfoForContext != null && goodsInfoForContext.getDetailInfo() != null && !goodsInfoForContext.getDetailInfo().isEmpty()) {
                    triggerContext.setGoodsDetail(goodsInfoForContext.getDetailInfo());
                }
            } catch (Exception e) {
                log.warn("【账号{}】获取固定资料和商品详情失败: errorType={}",
                        accountId, e.getClass().getSimpleName());
            }
            
            try {
                String triggerContextJson = objectMapper.writeValueAsString(triggerContext);
                record.setTriggerContext(triggerContextJson);
                autoReplyRecordMapper.updateTriggerContext(record.getId(), triggerContextJson);
            } catch (Exception e) {
                log.warn("【账号{}】序列化触发上下文失败，跳过保存: errorType={}",
                        accountId, e.getClass().getSimpleName());
            }
            

            // 7.5 人工确认模式：AI生成草稿后不自动发送，等待客服确认/编辑后再发送
            boolean manualConfirmOn = false;
            try {
                XianyuGoodsConfig manualConfirmConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
                manualConfirmOn = manualConfirmConfig != null
                        && manualConfirmConfig.getReplyManualConfirmOn() != null
                        && manualConfirmConfig.getReplyManualConfirmOn() == 1;
            } catch (Exception e) {
                log.warn("【账号{}】检查人工确认模式失败，按自动发送处理: errorType={}",
                        accountId, e.getClass().getSimpleName());
            }
            if (manualConfirmOn) {
                updateRecordState(record.getId(), 2, allReplyText);
                automationHealthService.recordReplyState(accountId, "SKIPPED", "MANUAL_CONFIRM");
                log.info("【账号{}】人工确认模式已开启，回复草稿等待确认: xyGoodsId={}, sId={}, recordId={}",
                        accountId, xyGoodsId, sId, record.getId());
                try {
                    notificationService.createNotification(
                            "AI回复待人工确认",
                            "账号" + accountId + "商品" + xyGoodsId + "的AI回复已生成草稿，请在自动回复页面确认/编辑后发送",
                            "auto_reply_confirm",
                            "info");
                } catch (Exception e) {
                    log.warn("【账号{}】创建人工确认通知失败: errorType={}", accountId, e.getClass().getSimpleName());
                }
                return ReplyOutcome.SKIPPED;
            }

            // 8. 发送回复消息
            boolean sendSuccess = false;
            boolean sendUnconfirmed = false;
            String cid = sId.replace("@goofish", "");
            String toId = cid;
            
            for (ReplyStrategy.ReplyResult.ReplyItem item : replyResult.getItems()) {
                if (item.getImageUrl() != null && !item.getImageUrl().isEmpty()) {
                    boolean imageSent = webSocketService.sendImageMessageWithResult(accountId, cid, toId, item.getImageUrl(), 0, 0);
                    if (!imageSent) {
                        log.warn("【账号{}】发送回复图片失败", accountId);
                    } else {
                        sendSuccess = true;
                        sentMessageSaveService.saveAiImageReply(accountId, cid, toId, item.getImageUrl(), xyGoodsId);
                    }
                }
                if (item.getTextContent() != null && !item.getTextContent().trim().isEmpty()) {
                    MessageSendResult textResult = webSocketService.sendMessageWithOutcome(accountId, cid, toId, item.getTextContent());
                    if (textResult == MessageSendResult.SUCCESS) {
                        sendSuccess = true;
                    } else if (textResult == MessageSendResult.UNKNOWN) {
                        sendUnconfirmed = true;
                        log.warn("【账号{}】自动回复已发送但服务端确认超时，不自动重发以避免重复消息", accountId);
                    } else {
                        log.warn("【账号{}】自动回复被服务端拒绝或发送失败", accountId);
                    }
                }
            }
            
            // 9. 更新记录状态
            ReplyOutcome outcome;
            if (sendSuccess) {
                automationHealthService.recordReplySuccess(accountId);
                log.info("【账号{}】自动回复成功: xyGoodsId={}, sId={}", accountId, xyGoodsId, sId);
                updateRecordState(record.getId(), 1, allReplyText);
                outcome = ReplyOutcome.SUCCESS;

                if (allReplyText != null && !allReplyText.trim().isEmpty()) {
                    sentMessageSaveService.saveAiAssistantReply(accountId, cid, toId, allReplyText, xyGoodsId);
                }
            } else if (sendUnconfirmed) {
                automationHealthService.recordReplyState(accountId, "RUNNING", "SEND_UNCONFIRMED");
                log.warn("【账号{}】自动回复可能已发送但服务端确认超时，状态记为成功以避免重复发送: xyGoodsId={}, sId={}", accountId, xyGoodsId, sId);
                updateRecordState(record.getId(), 1, allReplyText);
                // 结果未知：不能因为"可能已发送"就让任务重试，否则买家会收到两条
                outcome = ReplyOutcome.UNKNOWN;
            } else {
                automationHealthService.recordReplyState(accountId, "FAILED", "SEND_FAILED");
                log.error("【账号{}】自动回复发送失败: xyGoodsId={}, sId={}", accountId, xyGoodsId, sId);
                updateRecordState(record.getId(), -1, allReplyText);
                outcome = ReplyOutcome.FAILED;
            }
            return outcome;

        } catch (Exception e) {
            automationHealthService.recordReplyState(accountId, "FAILED", "EXECUTION_ERROR");
            log.error("【账号{}】执行自动回复异常: xyGoodsId={}, sId={}, errorType={}",
                    accountId, xyGoodsId, sId, e.getClass().getSimpleName());
            return ReplyOutcome.FAILED;
        }
    }
    
    @Override
    public boolean isAutoReplyEnabled(Long accountId, String xyGoodsId) {
        return isAnyReplyEnabled(accountId, xyGoodsId);
    }
    
    private boolean isAnyReplyEnabled(Long accountId, String xyGoodsId) {
        if (accountId == null || xyGoodsId == null) {
            return false;
        }
        try {
            XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
            if (goodsConfig == null) {
                return false;
            }
            boolean aiOn = goodsConfig.getXianyuAutoReplyOn() != null && goodsConfig.getXianyuAutoReplyOn() == 1;
            boolean keywordOn = goodsConfig.getXianyuKeywordReplyOn() != null && goodsConfig.getXianyuKeywordReplyOn() == 1;
            return aiOn || keywordOn;
        } catch (Exception e) {
            log.error("【账号{}】检查回复开关异常: xyGoodsId={}", accountId, xyGoodsId, e);
            return false;
        }
    }
    @Override
    public com.feijimiao.xianyuassistant.common.ResultObject<String> confirmAndSendReply(Long recordId, String editedContent) {
        if (recordId == null) {
            return com.feijimiao.xianyuassistant.common.ResultObject.failed("记录ID不能为空");
        }
        XianyuGoodsAutoReplyRecord record = autoReplyRecordMapper.selectById(recordId);
        if (record == null) {
            return com.feijimiao.xianyuassistant.common.ResultObject.failed("回复记录不存在");
        }
        if (record.getState() == null || record.getState() != 2) {
            return com.feijimiao.xianyuassistant.common.ResultObject.failed("该记录不是待人工确认状态");
        }
        // 确认后会以记录所属账号的 WebSocket 身份发出消息，必须校验归属
        rbacService.assertAccountVisible(
                com.feijimiao.xianyuassistant.context.UserContext.getUserId(),
                record.getXianyuAccountId());
        String replyText = (editedContent != null && !editedContent.trim().isEmpty())
                ? editedContent.trim() : record.getReplyContent();
        if (replyText == null || replyText.trim().isEmpty()) {
            return com.feijimiao.xianyuassistant.common.ResultObject.failed("回复内容不能为空");
        }
        Long accountId = record.getXianyuAccountId();
        String sId = record.getSId();
        String cid = sId != null ? sId.replace("@goofish", "") : null;
        if (accountId == null || cid == null || cid.isEmpty()) {
            return com.feijimiao.xianyuassistant.common.ResultObject.failed("会话信息缺失，无法发送");
        }
        String xyGoodsId = record.getXyGoodsId();
        try {
            MessageSendResult result = webSocketService.sendMessageWithOutcome(accountId, cid, cid, replyText);
            if (result == MessageSendResult.SUCCESS) {
                updateRecordState(recordId, 1, replyText);
                automationHealthService.recordReplySuccess(accountId);
                if (xyGoodsId != null && !xyGoodsId.isEmpty()) {
                    sentMessageSaveService.saveAiAssistantReply(accountId, cid, cid, replyText, xyGoodsId);
                }
                return com.feijimiao.xianyuassistant.common.ResultObject.success("回复已发送");
            } else if (result == MessageSendResult.UNKNOWN) {
                updateRecordState(recordId, 1, replyText);
                automationHealthService.recordReplyState(accountId, "RUNNING", "SEND_UNCONFIRMED");
                log.warn("【账号{}】人工确认回复已发送但服务端确认超时: recordId={}", accountId, recordId);
                return com.feijimiao.xianyuassistant.common.ResultObject.success("回复已发送（服务端确认超时，请勿重复发送）");
            } else {
                return com.feijimiao.xianyuassistant.common.ResultObject.failed("回复发送失败，服务端拒绝了消息");
            }
        } catch (Exception e) {
            log.error("【账号{}】人工确认发送回复异常: recordId={}, errorType={}",
                    accountId, recordId, e.getClass().getSimpleName());
            return com.feijimiao.xianyuassistant.common.ResultObject.failed("回复发送异常: " + e.getMessage());
        }
    }

    @Override
    public void dismissPendingReply(Long recordId) {
        if (recordId == null) {
            return;
        }
        rbacService.assertAccountVisible(
                com.feijimiao.xianyuassistant.context.UserContext.getUserId(),
                Optional.ofNullable(autoReplyRecordMapper.selectById(recordId))
                        .map(XianyuGoodsAutoReplyRecord::getXianyuAccountId).orElse(null));
        try {
            updateRecordState(recordId, -2, null);
            log.info("已忽略待人工确认的回复草稿: recordId={}", recordId);
        } catch (Exception e) {
            log.error("忽略待确认回复草稿失败: recordId={}, errorType={}",
                    recordId, e.getClass().getSimpleName());
        }
    }

    private void updateRecordState(Long recordId, Integer state, String replyContent) {
        try {
            autoReplyRecordMapper.updateStateAndContent(recordId, state, replyContent);
        } catch (Exception e) {
            log.error("更新回复记录状态失败: recordId={}, state={}", recordId, state, e);
        }
    }
}
