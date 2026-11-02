package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.controller.dto.MsgContextReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgListReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgListRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgSessionReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgSessionRespDTO;
import com.feijimiao.xianyuassistant.service.ChatMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
/**
 * 消息管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/msg")
@RequirePermission("message:read")
public class MsgController {

    @Autowired
    private ChatMessageService chatMessageService;

    /**
     * 分页查询消息列表
     * 按时间排序，时间新的在前面
     *
     * @param reqDTO 请求参数
     * @return 消息列表
     */
    @PostMapping("/list")
    public ResultObject<MsgListRespDTO> getMessageList(@Valid @RequestBody MsgListReqDTO reqDTO) {
        try {
            log.info("查询消息列表请求: xianyuAccountId={}, xyGoodsId={}, filterCurrentAccount={}, pageNum={}, pageSize={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getFilterCurrentAccount(), reqDTO.getPageNum(), reqDTO.getPageSize());
            return chatMessageService.getMessageList(reqDTO);
        } catch (Exception e) {
            log.error("查询消息列表失败", e);
            return ResultObject.failed("查询消息列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 根据会话ID获取上下文消息（最近50条）
     *
     * @param reqDTO 请求参数（sid, limit）
     * @return 消息列表
     */
    @PostMapping("/context")
    public ResultObject<?> getContextMessages(@Valid @RequestBody MsgContextReqDTO reqDTO) {
        try {
            log.info("查询上下文消息请求: sid={}, limit={}", reqDTO.getSid(), reqDTO.getLimit());
            return chatMessageService.getContextMessages(reqDTO);
        } catch (Exception e) {
            log.error("查询上下文消息失败", e);
            return ResultObject.failed("查询上下文消息失败: " + e.getMessage());
        }
    }

    /**
     * 分页查询会话列表（按会话聚合，每个会话展示最新消息与待回复标记）
     *
     * @param reqDTO 请求参数（xianyuAccountId, keyword, pageNum, pageSize）
     * @return 会话列表
     */
    @PostMapping("/sessions")
    public ResultObject<MsgSessionRespDTO> getSessions(@Valid @RequestBody MsgSessionReqDTO reqDTO) {
        try {
            log.info("查询会话列表请求: xianyuAccountId={}, keyword={}, pageNum={}, pageSize={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getKeyword(), reqDTO.getPageNum(), reqDTO.getPageSize());
            return chatMessageService.getSessions(reqDTO);
        } catch (Exception e) {
            log.error("查询会话列表失败", e);
            return ResultObject.failed("查询会话列表失败: " + e.getMessage());
        }
    }
}

