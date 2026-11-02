package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.controller.dto.DeleteAccountReqDTO;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.WebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 重构后：Controller 不再直接持有 AccountMapper，账号存在性查询改走 AccountService.getById。
 * 这里只验证 deleteAccount 的编排顺序：
 * 1. 先查账号是否存在；
 * 2. 先停 WebSocket 任务；
 * 3. 再删持久化数据；
 * 4. 停不下来则拒绝删除（不会触碰持久化数据）。
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock private AccountService accountService;
    @Mock private WebSocketService webSocketService;

    private AccountController controller;

    @BeforeEach
    void setUp() {
        controller = new AccountController();
        ReflectionTestUtils.setField(controller, "accountService", accountService);
        ReflectionTestUtils.setField(controller, "webSocketService", webSocketService);
    }

    @Test
    void deleteStopsConnectionBeforeRemovingPersistentData() {
        Long accountId = 7L;
        DeleteAccountReqDTO request = new DeleteAccountReqDTO();
        request.setAccountId(accountId);
        when(accountService.getById(accountId)).thenReturn(new XianyuAccount());
        when(webSocketService.stopWebSocketForAccountDeletion(accountId)).thenReturn(true);

        controller.deleteAccount(request);

        InOrder order = inOrder(webSocketService, accountService);
        order.verify(webSocketService).stopWebSocketForAccountDeletion(accountId);
        order.verify(accountService).deleteAccountAndRelatedData(accountId);
    }

    @Test
    void deleteKeepsPersistentDataWhenConnectionCannotBePermanentlyStopped() {
        Long accountId = 7L;
        DeleteAccountReqDTO request = new DeleteAccountReqDTO();
        request.setAccountId(accountId);
        when(accountService.getById(accountId)).thenReturn(new XianyuAccount());
        when(webSocketService.stopWebSocketForAccountDeletion(accountId)).thenReturn(false);

        var response = controller.deleteAccount(request);

        assertEquals(500, response.getCode());
        // 停不下来时绝对不能删持久化数据
        verify(accountService, never()).deleteAccountAndRelatedData(accountId);
    }
}
