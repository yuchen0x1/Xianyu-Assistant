package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 模板中的一条有序消息。 */
@Data
public class DeliveryTemplateMessageDTO {

    private Long id;

    @NotNull(message = "消息顺序不能为空")
    private Integer sortOrder;

    private String messageType = "TEXT";

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 2000, message = "单条消息最多2000个字符")
    private String content;
}
