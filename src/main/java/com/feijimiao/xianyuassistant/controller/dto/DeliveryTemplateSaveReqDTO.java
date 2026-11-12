package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 保存有序多段发货模板请求。 */
@Data
public class DeliveryTemplateSaveReqDTO {

    private Long id;

    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "模板名称不能为空")
    @Size(max = 100, message = "模板名称最多100个字符")
    private String name;

    private Integer enabled = 1;

    @Valid
    @NotEmpty(message = "模板至少需要一条消息")
    @Size(max = 20, message = "一个模板最多20条消息")
    private List<DeliveryTemplateMessageDTO> messages;
}
