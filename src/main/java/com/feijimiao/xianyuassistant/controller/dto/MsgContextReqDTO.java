package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 查询上下文消息请求DTO
 */
@Data
public class MsgContextReqDTO {
    
    /**
     * 会话ID
     */
    @NotBlank(message = "会话ID不能为空")
    private String sid;
    
    /**
     * 限制条数（默认20）
     */
    private Integer limit;
    
    /**
     * 偏移量（默认0）
     */
    private Integer offset;
}
