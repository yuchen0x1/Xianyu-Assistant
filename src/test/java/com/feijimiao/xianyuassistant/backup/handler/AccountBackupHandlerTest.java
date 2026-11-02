package com.feijimiao.xianyuassistant.backup.handler;

import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountBackupHandlerTest {

    @Mock
    private XianyuAccountMapper accountMapper;

    @Mock
    private XianyuCookieMapper cookieMapper;

    private AccountBackupHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AccountBackupHandler();
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(handler, "cookieMapper", cookieMapper);
    }

    @Test
    void importDataDoesNotHideAccountWriteFailure() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(accountMapper).insert(any(XianyuAccount.class));
        Map<String, Object> data = Map.of("accounts", List.of(Map.of(
                "unb", "123456",
                "accountNote", "test account",
                "status", 1
        )));

        assertThrows(IllegalStateException.class,
                () -> handler.importData(data, new LinkedHashMap<>()));
    }

    @Test
    void importDataRejectsAccountWithoutUnb() {
        Map<String, Object> data = Map.of("accounts", List.of(Map.of(
                "accountNote", "missing unb"
        )));

        assertThrows(IllegalStateException.class,
                () -> handler.importData(data, new LinkedHashMap<>()));
    }

    @Test
    void importDataRejectsCookieWithoutAnAssociatedAccount() {
        Map<String, Object> data = Map.of(
                "accounts", List.of(),
                "cookies", List.of(Map.of(
                        "unb", "missing-account",
                        "cookieText", "unb=missing-account"
                ))
        );

        assertThrows(IllegalStateException.class,
                () -> handler.importData(data, new LinkedHashMap<>()));
    }

    @Test
    void exportIncludesThePersistedWebSocketIntent() {
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        account.setUnb("buyer-7");
        account.setWebsocketEnabled(0);
        when(accountMapper.selectList(null)).thenReturn(List.of(account));
        when(cookieMapper.selectList(null)).thenReturn(List.of());

        Map<String, Object> exported = handler.exportData();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accounts =
                (List<Map<String, Object>>) exported.get("accounts");
        assertEquals(0, accounts.getFirst().get("websocketEnabled"));
    }

    @Test
    void importDefaultsOldBackupsToEnabledAndPreservesExplicitDisable() {
        doAnswer(invocation -> {
            XianyuAccount account = invocation.getArgument(0);
            account.setId("old-format".equals(account.getUnb()) ? 1L : 2L);
            return 1;
        }).when(accountMapper).insert(any(XianyuAccount.class));
        Map<String, Object> data = Map.of(
                "accounts", List.of(
                        Map.of("unb", "old-format", "status", 1),
                        Map.of("unb", "disabled", "status", 1, "websocketEnabled", 0)),
                "cookies", List.of());

        handler.importData(data, new LinkedHashMap<>());

        ArgumentCaptor<XianyuAccount> accounts = ArgumentCaptor.forClass(XianyuAccount.class);
        verify(accountMapper, org.mockito.Mockito.times(2)).insert(accounts.capture());
        assertEquals(1, accounts.getAllValues().get(0).getWebsocketEnabled());
        assertEquals(0, accounts.getAllValues().get(1).getWebsocketEnabled());
    }
}
