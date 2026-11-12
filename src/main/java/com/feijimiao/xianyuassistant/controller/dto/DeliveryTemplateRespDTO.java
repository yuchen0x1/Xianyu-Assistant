package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 有序多段发货模板响应。 */
@Data
public class DeliveryTemplateRespDTO {

    private Long id;
    private Long xianyuAccountId;
    private String name;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<DeliveryTemplateMessageDTO> messages;
}
