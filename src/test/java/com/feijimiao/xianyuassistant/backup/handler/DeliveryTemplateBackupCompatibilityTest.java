package com.feijimiao.xianyuassistant.backup.handler;

import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.DeliveryTemplate;
import com.feijimiao.xianyuassistant.entity.DeliveryTemplateMessage;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig;
import com.feijimiao.xianyuassistant.mapper.DeliveryTemplateMapper;
import com.feijimiao.xianyuassistant.mapper.DeliveryTemplateMessageMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryTemplateBackupCompatibilityTest {

    @Mock
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    @Mock
    private XianyuAccountMapper accountMapper;
    @Mock
    private XianyuKamiConfigMapper kamiConfigMapper;
    @Mock
    private DeliveryTemplateMapper deliveryTemplateMapper;
    @Mock
    private DeliveryTemplateMessageMapper deliveryTemplateMessageMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private AutoDeliveryBackupHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AutoDeliveryBackupHandler();
        ReflectionTestUtils.setField(handler, "autoDeliveryConfigMapper", autoDeliveryConfigMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(handler, "kamiConfigMapper", kamiConfigMapper);
        ReflectionTestUtils.setField(handler, "deliveryTemplateMapper", deliveryTemplateMapper);
        ReflectionTestUtils.setField(handler, "deliveryTemplateMessageMapper", deliveryTemplateMessageMapper);
        ReflectionTestUtils.setField(handler, "jdbcTemplate", jdbcTemplate);
        lenient().when(jdbcTemplate.queryForList(any(String.class))).thenReturn(List.of());
    }

    @Test
    void exportIncludesUnreferencedTemplatesAndConfigReference() {
        XianyuAccount account = account(7L, "account-1");
        XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
        config.setXianyuAccountId(7L);
        config.setXyGoodsId("goods-1");
        config.setDeliveryTemplateId(4L);
        DeliveryTemplate template = template(4L, 7L, "交付模板");
        DeliveryTemplateMessage message = message(4L, 1, "订单 {{order_id}}");

        when(autoDeliveryConfigMapper.selectList(null)).thenReturn(List.of(config));
        when(accountMapper.selectById(7L)).thenReturn(account);
        when(accountMapper.selectList(null)).thenReturn(List.of(account));
        when(deliveryTemplateMapper.selectByAccount(7L)).thenReturn(List.of(template));
        when(deliveryTemplateMessageMapper.selectByTemplateId(4L)).thenReturn(List.of(message));

        Map<String, Object> exported = handler.exportData();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configs = (List<Map<String, Object>>) exported.get("autoDeliveryConfigs");
        assertThat(configs).singleElement().satisfies(item ->
                assertThat(item.get("deliveryTemplateId")).isEqualTo(4L));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> templates = (List<Map<String, Object>>) exported.get("deliveryTemplates");
        assertThat(templates).singleElement().satisfies(item -> {
            assertThat(item.get("sourceTemplateId")).isEqualTo(4L);
            assertThat(item.get("unb")).isEqualTo("account-1");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) item.get("messages");
            assertThat(messages).singleElement().satisfies(messageEntry ->
                    assertThat(messageEntry.get("content")).isEqualTo("订单 {{order_id}}"));
        });
    }

    @Test
    void importMapsTemplateIdBeforeRestoringConfig() {
        when(deliveryTemplateMapper.selectByAccount(20L)).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            DeliveryTemplate target = invocation.getArgument(0);
            target.setId(104L);
            return 1;
        }).when(deliveryTemplateMapper).insert(any(DeliveryTemplate.class));
        when(autoDeliveryConfigMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryTemplates", List.of(Map.of(
                "sourceTemplateId", 4,
                "unb", "account-1",
                "name", "交付模板",
                "enabled", 1,
                "messages", List.of(Map.of(
                        "sortOrder", 1,
                        "messageType", "TEXT",
                        "content", "订单 {{order_id}}")))));
        data.put("autoDeliveryConfigs", List.of(Map.of(
                "unb", "account-1",
                "xyGoodsId", "goods-1",
                "deliveryTemplateId", 4)));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("account-1", 20L));

        handler.importData(data, context);

        ArgumentCaptor<XianyuGoodsAutoDeliveryConfig> configCaptor =
                ArgumentCaptor.forClass(XianyuGoodsAutoDeliveryConfig.class);
        verify(autoDeliveryConfigMapper).insert(configCaptor.capture());
        assertThat(configCaptor.getValue().getDeliveryTemplateId()).isEqualTo(104L);
        ArgumentCaptor<DeliveryTemplateMessage> messageCaptor =
                ArgumentCaptor.forClass(DeliveryTemplateMessage.class);
        verify(deliveryTemplateMessageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getTemplateId()).isEqualTo(104L);
    }

    @Test
    void rejectsConfigThatReferencesMissingTemplateInsteadOfLeavingDanglingId() {
        Map<String, Object> data = Map.of("autoDeliveryConfigs", List.of(Map.of(
                "unb", "account-1",
                "xyGoodsId", "goods-1",
                "deliveryTemplateId", 999)));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("account-1", 20L));

        assertThatThrownBy(() -> handler.importData(data, context))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("自动发货配置引用了未恢复的发货模板: sourceTemplateId=999, accountId=20");
        verify(autoDeliveryConfigMapper, never()).insert(any(XianyuGoodsAutoDeliveryConfig.class));
    }

    private XianyuAccount account(Long id, String unb) {
        XianyuAccount account = new XianyuAccount();
        account.setId(id);
        account.setUnb(unb);
        return account;
    }

    private DeliveryTemplate template(Long id, Long accountId, String name) {
        DeliveryTemplate template = new DeliveryTemplate();
        template.setId(id);
        template.setXianyuAccountId(accountId);
        template.setName(name);
        template.setEnabled(1);
        return template;
    }

    private DeliveryTemplateMessage message(Long templateId, int sortOrder, String content) {
        DeliveryTemplateMessage message = new DeliveryTemplateMessage();
        message.setTemplateId(templateId);
        message.setSortOrder(sortOrder);
        message.setMessageType("TEXT");
        message.setContent(content);
        return message;
    }
}
