package com.feijimiao.xianyuassistant.command;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListAccountsCommandScopeTest {

    private final XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
    private final RbacService rbacService = mock(RbacService.class);

    @BeforeAll
    static void initializeMyBatisLambdaMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                XianyuAccount.class);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void nonAdminListOnlyQueriesAccountsInExplicitScope() {
        UserContext.set(7L, "operator");
        when(rbacService.getCommandVisibleAccountIds(7L)).thenReturn(List.of(10L));
        XianyuAccount visible = new XianyuAccount();
        visible.setId(10L);
        visible.setAccountNote("visible");
        XianyuAccount hidden = new XianyuAccount();
        hidden.setId(20L);
        hidden.setAccountNote("hidden");
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(visible));

        Map<String, Object> result = new ListAccountsCommand(accountMapper, rbacService).execute(Map.of());

        assertThat(result.get("total")).isEqualTo(1);
        assertThat(result.get("accounts").toString()).contains("visible").doesNotContain("hidden");
    }

    @Test
    void nonAdminWithNoScopeReturnsEmptyWithoutQueryingAllAccounts() {
        UserContext.set(7L, "operator");
        when(rbacService.getCommandVisibleAccountIds(7L)).thenReturn(List.of());

        Map<String, Object> result = new ListAccountsCommand(accountMapper, rbacService).execute(Map.of());

        assertThat(result.get("total")).isEqualTo(0);
        assertThat(result.get("accounts")).isEqualTo(List.of());
        org.mockito.Mockito.verify(accountMapper, org.mockito.Mockito.never()).selectList(any(Wrapper.class));
    }
}
