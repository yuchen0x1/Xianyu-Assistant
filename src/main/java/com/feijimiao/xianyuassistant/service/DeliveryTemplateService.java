package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.controller.dto.DeliveryTemplateRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.DeliveryTemplateSaveReqDTO;
import com.feijimiao.xianyuassistant.entity.DeliveryTemplate;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryTemplateRenderContext;

import java.util.List;

/** 有序多段自动发货模板服务。 */
public interface DeliveryTemplateService {

    List<DeliveryTemplateRespDTO> list(Long accountId);

    DeliveryTemplateRespDTO get(Long accountId, Long templateId);

    DeliveryTemplateRespDTO save(DeliveryTemplateSaveReqDTO request);

    void delete(Long accountId, Long templateId);

    /** 按账号读取模板，供自动发货编排层使用。 */
    DeliveryTemplate findByIdForAccount(Long accountId, Long templateId);

    /** 渲染模板消息，顺序与模板配置一致。 */
    List<String> renderMessages(DeliveryTemplate template, DeliveryTemplateRenderContext context);
}
