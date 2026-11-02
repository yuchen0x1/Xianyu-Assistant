package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.controller.dto.MsgContextReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgDTO;
import com.feijimiao.xianyuassistant.entity.XianyuChatMessage;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.mapper.XianyuChatMessageMapper;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceImplAccountScopeTest {

    @Mock
    private XianyuChatMessageMapper chatMessageMapper;

    @Mock
    private RbacService rbacService;

    @InjectMocks
    private ChatMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.set(42L, "restricted-user");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void deniesExplicitRequestForAnotherAccountBeforeQueryingMessages() {
        doThrow(new BusinessException(403, "无权访问该账号的数据"))
                .when(rbacService).assertAccountVisible(42L, 99L);

        assertThatThrownBy(() -> service.getMessagesByAccountId(99L, 1, 20))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);

        verifyNoInteractions(chatMessageMapper);
    }

    @Test
    void sessionMessagesExcludeNullAndInvisibleAccounts() {
        XianyuChatMessage visible = message(1L);
        XianyuChatMessage invisible = message(2L);
        XianyuChatMessage unowned = message(null);
        when(chatMessageMapper.findBySId("session-1"))
                .thenReturn(List.of(visible, invisible, unowned));
        when(rbacService.getVisibleAccountIds(42L)).thenReturn(List.of(1L));

        assertThat(service.getMessagesBySessionId("session-1")).containsExactly(visible);

        verify(chatMessageMapper).findBySId("session-1");
    }

    @Test
    void contextMessagesExcludeNullAndInvisibleAccounts() {
        XianyuChatMessage visible = message(1L);
        XianyuChatMessage invisible = message(2L);
        XianyuChatMessage unowned = message(null);
        when(chatMessageMapper.findRecentBySId("session-1", 20, 0))
                .thenReturn(List.of(visible, invisible, unowned));
        when(rbacService.getVisibleAccountIds(42L)).thenReturn(List.of(1L));
        MsgContextReqDTO request = new MsgContextReqDTO();
        request.setSid("session-1");

        var result = service.getContextMessages(request);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat((List<MsgDTO>) result.getData()).hasSize(1);
    }

    @Test
    void contextMessagesReturnNoRowsWhenRestrictedUserHasEmptyVisibility() {
        when(chatMessageMapper.findRecentBySId("session-1", 20, 0))
                .thenReturn(List.of(message(1L), message(2L)));
        when(rbacService.getVisibleAccountIds(42L)).thenReturn(List.of());
        MsgContextReqDTO request = new MsgContextReqDTO();
        request.setSid("session-1");

        var result = service.getContextMessages(request);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat((List<MsgDTO>) result.getData()).isEmpty();
    }

    @Test
    void sessionMessagesReturnNoRowsWhenRestrictedUserHasEmptyVisibility() {
        when(chatMessageMapper.findBySId("session-1"))
                .thenReturn(List.of(message(1L), message(2L)));
        when(rbacService.getVisibleAccountIds(42L)).thenReturn(List.of());

        assertThat(service.getMessagesBySessionId("session-1")).isEmpty();
    }

    private XianyuChatMessage message(Long accountId) {
        XianyuChatMessage message = new XianyuChatMessage();
        message.setXianyuAccountId(accountId);
        return message;
    }
}
