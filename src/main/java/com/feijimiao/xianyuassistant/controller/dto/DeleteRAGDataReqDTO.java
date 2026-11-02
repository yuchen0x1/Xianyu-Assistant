package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @author IAMLZY
 * @date 2026/4/22
 * @description 删除RAG资料请求
 */
@Data
public class DeleteRAGDataReqDTO {
    @NotBlank(message = "文档ID不能为空")
    private String documentId;
}
