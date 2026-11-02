package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 消息会话列表响应DTO
 */
@Data
public class MsgSessionRespDTO {

    /**
     * 会话列表
     */
    private List<MsgSessionDTO> list;

    /**
     * 会话总数
     */
    private Integer totalCount;

    /**
     * 当前页码
     */
    private Integer pageNum;

    /**
     * 每页数量
     */
    private Integer pageSize;

    /**
     * 总页数
     */
    private Integer totalPage;
}
