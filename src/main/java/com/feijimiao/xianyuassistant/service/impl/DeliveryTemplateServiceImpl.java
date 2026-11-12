package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.controller.dto.DeliveryTemplateMessageDTO;
import com.feijimiao.xianyuassistant.controller.dto.DeliveryTemplateRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.DeliveryTemplateSaveReqDTO;
import com.feijimiao.xianyuassistant.entity.DeliveryTemplate;
import com.feijimiao.xianyuassistant.entity.DeliveryTemplateMessage;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.mapper.DeliveryTemplateMapper;
import com.feijimiao.xianyuassistant.mapper.DeliveryTemplateMessageMapper;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.service.DeliveryTemplateService;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryTemplateRenderContext;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryTemplateRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 有序多段自动发货模板服务实现。 */
@Service
@RequiredArgsConstructor
public class DeliveryTemplateServiceImpl implements DeliveryTemplateService {

    private final DeliveryTemplateMapper templateMapper;
    private final DeliveryTemplateMessageMapper messageMapper;
    private final DeliveryTemplateRenderer renderer;
    private final RbacService rbacService;

    @Override
    public List<DeliveryTemplateRespDTO> list(Long accountId) {
        assertVisible(accountId);
        return templateMapper.selectByAccount(accountId).stream()
                .map(this::withMessages)
                .map(this::toResponse)
                .toList();
    }

    @Override
    public DeliveryTemplateRespDTO get(Long accountId, Long templateId) {
        DeliveryTemplate template = findByIdForAccount(accountId, templateId);
        return template == null ? null : toResponse(template);
    }

    @Override
    @Transactional
    public DeliveryTemplateRespDTO save(DeliveryTemplateSaveReqDTO request) {
        if (request == null) {
            throw new BusinessException("模板请求不能为空");
        }
        assertVisible(request.getXianyuAccountId());
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty()) {
            throw new BusinessException("模板名称不能为空");
        }
        if (request.getEnabled() != null && request.getEnabled() != 0 && request.getEnabled() != 1) {
            throw new BusinessException("模板启用状态无效");
        }

        DeliveryTemplate template;
        if (request.getId() == null) {
            template = new DeliveryTemplate();
            template.setXianyuAccountId(request.getXianyuAccountId());
            template.setName(name);
            template.setEnabled(request.getEnabled() == null ? 1 : request.getEnabled());
            try {
                templateMapper.insert(template);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("该账号下已存在同名发货模板", e);
            }
        } else {
            template = templateMapper.selectByIdAndAccount(request.getId(), request.getXianyuAccountId());
            if (template == null) {
                throw new BusinessException("发货模板不存在或无权访问");
            }
            template.setName(name);
            template.setEnabled(request.getEnabled() == null ? 1 : request.getEnabled());
            try {
                if (templateMapper.update(template) != 1) {
                    throw new BusinessException("更新发货模板失败");
                }
            } catch (DuplicateKeyException e) {
                throw new BusinessException("该账号下已存在同名发货模板", e);
            }
            messageMapper.deleteByTemplateId(template.getId());
        }

        List<DeliveryTemplateMessage> messages = toEntities(template.getId(), request.getMessages());
        renderer.validate(messages);
        for (DeliveryTemplateMessage message : messages) {
            messageMapper.insert(message);
        }
        template.setMessages(messages);
        return toResponse(template);
    }

    @Override
    @Transactional
    public void delete(Long accountId, Long templateId) {
        assertVisible(accountId);
        if (templateMapper.selectByIdAndAccount(templateId, accountId) == null) {
            throw new BusinessException("发货模板不存在或无权访问");
        }
        if (templateMapper.countConfigReferences(accountId, templateId) > 0) {
            throw new BusinessException("模板仍被商品自动发货配置引用，请先解除绑定");
        }
        // 显式删除子记录，兼容历史 SQLite 数据库未开启 foreign_keys 的情况。
        messageMapper.deleteByTemplateId(templateId);
        if (templateMapper.deleteByIdAndAccount(templateId, accountId) != 1) {
            throw new BusinessException("删除发货模板失败");
        }
    }

    @Override
    public DeliveryTemplate findByIdForAccount(Long accountId, Long templateId) {
        if (templateId == null) {
            return null;
        }
        assertVisible(accountId);
        DeliveryTemplate template = templateMapper.selectByIdAndAccount(templateId, accountId);
        return template == null ? null : withMessages(template);
    }

    @Override
    public List<String> renderMessages(DeliveryTemplate template, DeliveryTemplateRenderContext context) {
        return renderer.renderMessages(template, context);
    }

    private void assertVisible(Long accountId) {
        rbacService.assertAccountVisible(UserContext.getUserId(), accountId);
    }

    private DeliveryTemplate withMessages(DeliveryTemplate template) {
        template.setMessages(messageMapper.selectByTemplateId(template.getId()));
        return template;
    }

    private List<DeliveryTemplateMessage> toEntities(Long templateId, List<DeliveryTemplateMessageDTO> source) {
        if (source == null) {
            return List.of();
        }
        List<DeliveryTemplateMessage> result = new ArrayList<>();
        for (DeliveryTemplateMessageDTO dto : source) {
            if (dto == null) {
                throw new BusinessException("模板消息不能为空");
            }
            DeliveryTemplateMessage message = new DeliveryTemplateMessage();
            message.setTemplateId(templateId);
            message.setSortOrder(dto.getSortOrder());
            message.setMessageType(dto.getMessageType() == null || dto.getMessageType().isBlank()
                    ? "TEXT" : dto.getMessageType().trim().toUpperCase());
            message.setContent(dto.getContent() == null ? null : dto.getContent().trim());
            result.add(message);
        }
        result.sort(Comparator.comparing(DeliveryTemplateMessage::getSortOrder,
                Comparator.nullsFirst(Integer::compareTo)));
        return result;
    }

    private DeliveryTemplateRespDTO toResponse(DeliveryTemplate template) {
        DeliveryTemplateRespDTO response = new DeliveryTemplateRespDTO();
        response.setId(template.getId());
        response.setXianyuAccountId(template.getXianyuAccountId());
        response.setName(template.getName());
        response.setEnabled(template.getEnabled());
        response.setCreateTime(template.getCreateTime());
        response.setUpdateTime(template.getUpdateTime());
        response.setMessages(template.getMessages() == null ? List.of() : template.getMessages().stream()
                .sorted(Comparator.comparing(DeliveryTemplateMessage::getSortOrder))
                .map(message -> {
                    DeliveryTemplateMessageDTO dto = new DeliveryTemplateMessageDTO();
                    dto.setId(message.getId());
                    dto.setSortOrder(message.getSortOrder());
                    dto.setMessageType(message.getMessageType());
                    dto.setContent(message.getContent());
                    return dto;
                }).toList());
        return response;
    }
}
