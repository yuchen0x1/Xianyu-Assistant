package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuChatMessage;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuChatMessageMapper;
import com.feijimiao.xianyuassistant.controller.dto.MsgContextReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgListReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgListRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgSessionDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgSessionReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgSessionRespDTO;
import com.feijimiao.xianyuassistant.service.ChatMessageService;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聊天消息服务实现
 * 
 * <p>职责：提供消息查询相关的服务</p>
 * <p>注意：WebSocket 消息的解析和保存现在由 SyncMessageHandler 直接处理</p>
 */
@Slf4j
@Service
public class ChatMessageServiceImpl implements ChatMessageService {

    /** 单次上下文消息查询的最大条数，防止全量导出 */
    private static final int CONTEXT_MESSAGE_LIMIT = 100;

    @Autowired
    private XianyuChatMessageMapper chatMessageMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private RbacService rbacService;
    
    @Override
    public List<XianyuChatMessage> getMessagesByAccountId(Long accountId, int page, int pageSize) {
        rbacService.assertAccountVisible(UserContext.getUserId(), accountId);
        int offset = (page - 1) * pageSize;
        return chatMessageMapper.findByAccountId(accountId, pageSize, offset);
    }
    
    @Override
    public List<XianyuChatMessage> getMessagesBySessionId(String sessionId) {
        List<XianyuChatMessage> messages = chatMessageMapper.findBySId(sessionId);
        List<Long> visibleAccountIds = rbacService.getVisibleAccountIds(UserContext.getUserId());
        if (visibleAccountIds == null || messages == null) {
            return messages;
        }
        return messages.stream()
                .filter(message -> message.getXianyuAccountId() != null
                        && visibleAccountIds.contains(message.getXianyuAccountId()))
                .toList();
    }
    
    @Override
    public ResultObject<MsgListRespDTO> getMessageList(MsgListReqDTO reqDTO) {
        try {
            // 参数和账号归属校验：账号范围检查必须先于消息查询。
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.validateFailed("xianyuAccountId不能为空");
            }
            rbacService.assertAccountVisible(UserContext.getUserId(), reqDTO.getXianyuAccountId());
            
            // 设置默认值
            int pageNum = reqDTO.getPageNum() != null && reqDTO.getPageNum() > 0 ? reqDTO.getPageNum() : 1;
            int pageSize = reqDTO.getPageSize() != null && reqDTO.getPageSize() > 0 ? reqDTO.getPageSize() : 20;
            
            // 计算偏移量
            int offset = (pageNum - 1) * pageSize;
            
            // 获取当前账号的UNB（用于过滤）
            String currentAccountUnb = null;
            if (reqDTO.getFilterCurrentAccount() != null && reqDTO.getFilterCurrentAccount()) {
                XianyuAccount account = accountMapper.selectById(reqDTO.getXianyuAccountId());
                if (account != null) {
                    currentAccountUnb = account.getUnb();
                }
            }
            
            // 查询总数
            int totalCount = chatMessageMapper.countMessages(
                    reqDTO.getXianyuAccountId(),
                    reqDTO.getXyGoodsId(),
                    currentAccountUnb
            );
            
            // 查询分页数据
            List<XianyuChatMessage> messages = chatMessageMapper.findMessagesByPage(
                    reqDTO.getXianyuAccountId(),
                    reqDTO.getXyGoodsId(),
                    currentAccountUnb,
                    pageSize,
                    offset
            );
            
            // 转换为DTO
            List<MsgDTO> msgDTOList = new ArrayList<>();
            if (messages != null) {
                for (XianyuChatMessage message : messages) {
                    MsgDTO msgDTO = new MsgDTO();
                    msgDTO.setId(message.getId());
                    msgDTO.setSId(message.getSId());
                    msgDTO.setContentType(message.getContentType());
                    msgDTO.setMsgContent(message.getMsgContent());
                    msgDTO.setXyGoodsId(message.getXyGoodsId());
                    msgDTO.setReminderUrl(message.getReminderUrl());
                    msgDTO.setSenderUserName(message.getSenderUserName());
                    msgDTO.setSenderUserId(message.getSenderUserId());
                    msgDTO.setMessageTime(message.getMessageTime());
                    msgDTOList.add(msgDTO);
                }
            }
            
            // 计算总页数
            int totalPage = (int) Math.ceil((double) totalCount / pageSize);
            if (totalPage == 0 && totalCount > 0) {
                totalPage = 1;
            }
            
            // 构建响应
            MsgListRespDTO respDTO = new MsgListRespDTO();
            respDTO.setList(msgDTOList);
            respDTO.setTotalCount(totalCount);
            respDTO.setPageNum(pageNum);
            respDTO.setPageSize(pageSize);
            respDTO.setTotalPage(totalPage);
            
            return ResultObject.success(respDTO);
            
        } catch (Exception e) {
            log.error("查询消息列表失败: accountId={}, xyGoodsId={}, filterCurrentAccount={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getFilterCurrentAccount(), e);
            return ResultObject.failed("查询消息列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<?> getContextMessages(MsgContextReqDTO reqDTO) {
        try {
            if (reqDTO.getSid() == null || reqDTO.getSid().isEmpty()) {
                return ResultObject.validateFailed("sid不能为空");
            }

            int limit = reqDTO.getLimit() != null && reqDTO.getLimit() > 0 ? reqDTO.getLimit() : 20;
            int offset = reqDTO.getOffset() != null && reqDTO.getOffset() >= 0 ? reqDTO.getOffset() : 0;
            if (limit > CONTEXT_MESSAGE_LIMIT) {
                limit = CONTEXT_MESSAGE_LIMIT;
            }

            List<XianyuChatMessage> messages = chatMessageMapper.findRecentBySId(reqDTO.getSid(), limit, offset);

            // 会话按 sid 直达，绕过了账号范围切面，这里按当前用户可见账号兜底过滤
            List<Long> visibleAccountIds = rbacService.getVisibleAccountIds(UserContext.getUserId());

            List<MsgDTO> msgDTOList = new ArrayList<>();
            if (messages != null) {
                for (XianyuChatMessage message : messages) {
                    if (visibleAccountIds != null
                            && (message.getXianyuAccountId() == null
                            || !visibleAccountIds.contains(message.getXianyuAccountId()))) {
                        continue;
                    }
                    MsgDTO msgDTO = new MsgDTO();
                    msgDTO.setId(message.getId());
                    msgDTO.setSId(message.getSId());
                    msgDTO.setContentType(message.getContentType());
                    msgDTO.setMsgContent(message.getMsgContent());
                    msgDTO.setXyGoodsId(message.getXyGoodsId());
                    msgDTO.setReminderUrl(message.getReminderUrl());
                    msgDTO.setSenderUserName(message.getSenderUserName());
                    msgDTO.setSenderUserId(message.getSenderUserId());
                    msgDTO.setMessageTime(message.getMessageTime());
                    msgDTOList.add(msgDTO);
                }
            }
            
            return ResultObject.success(msgDTOList);
            
        } catch (Exception e) {
            log.error("查询上下文消息失败: sid={}", reqDTO.getSid(), e);
            return ResultObject.failed("查询上下文消息失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<MsgSessionRespDTO> getSessions(MsgSessionReqDTO reqDTO) {
        try {
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.validateFailed("xianyuAccountId不能为空");
            }
            rbacService.assertAccountVisible(UserContext.getUserId(), reqDTO.getXianyuAccountId());

            int pageNum = reqDTO.getPageNum() != null && reqDTO.getPageNum() > 0 ? reqDTO.getPageNum() : 1;
            int pageSize = reqDTO.getPageSize() != null && reqDTO.getPageSize() > 0 ? reqDTO.getPageSize() : 20;
            String keyword = (reqDTO.getKeyword() == null || reqDTO.getKeyword().trim().isEmpty())
                    ? null : reqDTO.getKeyword().trim();
            int offset = (pageNum - 1) * pageSize;

            // 获取账号 unb（用于判断最新一条是否来自买家）
            String unb = null;
            XianyuAccount account = accountMapper.selectById(reqDTO.getXianyuAccountId());
            if (account != null) {
                unb = account.getUnb();
            }

            int totalCount = chatMessageMapper.countSessions(reqDTO.getXianyuAccountId(), keyword);
            List<Map<String, Object>> rows = chatMessageMapper.findSessions(
                    reqDTO.getXianyuAccountId(), keyword, pageSize, offset);

            List<MsgSessionDTO> sessionList = new ArrayList<>();
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    MsgSessionDTO dto = new MsgSessionDTO();
                    dto.setSid(asString(row.get("sid")));
                    dto.setBuyerName(asString(row.get("buyerName")));
                    dto.setBuyerUserId(asString(row.get("buyerUserId")));
                    dto.setXyGoodsId(asString(row.get("xyGoodsId")));
                    dto.setLastContent(asString(row.get("lastContent")));
                    dto.setLastTime(asLong(row.get("lastTime")));
                    dto.setMessageCount(asInt(row.get("messageCount")));
                    // 最新一条来自买家（非当前账号）→ 待回复
                    dto.setPendingReply(unb != null && !unb.isEmpty()
                            && !unb.equals(dto.getBuyerUserId()));
                    sessionList.add(dto);
                }
            }

            MsgSessionRespDTO respDTO = new MsgSessionRespDTO();
            respDTO.setList(sessionList);
            respDTO.setTotalCount(totalCount);
            respDTO.setPageNum(pageNum);
            respDTO.setPageSize(pageSize);
            respDTO.setTotalPage((int) Math.ceil((double) totalCount / pageSize));

            return ResultObject.success(respDTO);

        } catch (Exception e) {
            log.error("查询会话列表失败: accountId={}", reqDTO.getXianyuAccountId(), e);
            return ResultObject.failed("查询会话列表失败: " + e.getMessage());
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Integer asInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}
