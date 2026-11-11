package com.feijimiao.xianyuassistant.command;

import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandRegistryScopeTest {

    private final RbacService rbacService = mock(RbacService.class);
    private final XianyuCommand command = mock(XianyuCommand.class);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private CommandRegistry registry() {
        when(command.descriptor()).thenReturn(new CommandDescriptor(
                "account_command", "account command", List.of(), true));
        return new CommandRegistry(List.of(command), rbacService);
    }

    @Test
    void rejectsAccountCommandWithoutAuthenticatedUser() {
        when(rbacService.getCommandVisibleAccountIds(null)).thenReturn(List.of());

        assertThatThrownBy(() -> registry().execute("account_command", Map.of("accountId", 10L)))
                .isInstanceOf(CommandExecutionException.class)
                .hasMessage("无权访问该账号");
        verify(command, never()).execute(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void rejectsAccountOutsideExplicitScope() {
        UserContext.set(7L, "operator");
        when(rbacService.getCommandVisibleAccountIds(7L)).thenReturn(List.of(10L));

        assertThatThrownBy(() -> registry().execute("account_command", Map.of("accountId", 20L)))
                .isInstanceOf(CommandExecutionException.class)
                .hasMessage("无权访问该账号");
        verify(command, never()).execute(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void executesAccountWithinExplicitScope() {
        UserContext.set(7L, "operator");
        when(rbacService.getCommandVisibleAccountIds(7L)).thenReturn(List.of(10L));
        when(command.execute(Map.of("accountId", 10L))).thenReturn(Map.of("success", true));

        registry().execute("account_command", Map.of("accountId", 10L));

        verify(command).execute(Map.of("accountId", 10L));
    }

    @Test
    void rejectsNonAdminAccountCommandWhenScopeIsEmpty() {
        UserContext.set(7L, "operator");
        when(rbacService.getCommandVisibleAccountIds(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> registry().execute("account_command", Map.of()))
                .isInstanceOf(CommandExecutionException.class)
                .hasMessage("非管理员必须指定 accountId");
        verify(command, never()).execute(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void rejectsDuplicateCommandNamesInsteadOfSilentlySelectingOne() {
        XianyuCommand duplicate = mock(XianyuCommand.class);
        when(command.descriptor()).thenReturn(new CommandDescriptor(
                "duplicate", "first", List.of(), false));
        when(duplicate.descriptor()).thenReturn(new CommandDescriptor(
                "duplicate", "second", List.of(), false));

        assertThatThrownBy(() -> new CommandRegistry(List.of(command, duplicate), rbacService))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("检测到重复的命令名称: duplicate");
    }

    @Test
    void preservesRegistrationOrderForStableCommandCenterRendering() {
        XianyuCommand second = mock(XianyuCommand.class);
        when(command.descriptor()).thenReturn(new CommandDescriptor(
                "first", "first", List.of(), false));
        when(second.descriptor()).thenReturn(new CommandDescriptor(
                "second", "second", List.of(), false));

        List<String> names = new CommandRegistry(List.of(command, second), rbacService)
                .list().stream().map(CommandDescriptor::name).toList();

        org.junit.jupiter.api.Assertions.assertEquals(List.of("first", "second"), names);
    }
}
