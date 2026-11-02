package com.feijimiao.xianyuassistant.rbac.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.feijimiao.xianyuassistant.rbac.entity.SysPermission;
import com.feijimiao.xianyuassistant.rbac.entity.SysRole;
import com.feijimiao.xianyuassistant.rbac.entity.SysRolePermission;
import com.feijimiao.xianyuassistant.rbac.entity.SysUserAccountScope;
import com.feijimiao.xianyuassistant.rbac.entity.SysUserRole;
import com.feijimiao.xianyuassistant.rbac.mapper.SysPermissionMapper;
import com.feijimiao.xianyuassistant.rbac.mapper.SysRoleMapper;
import com.feijimiao.xianyuassistant.rbac.mapper.SysRolePermissionMapper;
import com.feijimiao.xianyuassistant.rbac.mapper.SysUserAccountScopeMapper;
import com.feijimiao.xianyuassistant.rbac.mapper.SysUserRoleMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RbacServiceAccountScopeTest {

    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysPermissionMapper permissionMapper;
    @Mock
    private SysRolePermissionMapper rolePermissionMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private SysUserAccountScopeMapper accountScopeMapper;

    private RbacService service;

    @BeforeAll
    static void initializeMyBatisLambdaMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, SysRole.class);
        TableInfoHelper.initTableInfo(assistant, SysPermission.class);
        TableInfoHelper.initTableInfo(assistant, SysRolePermission.class);
        TableInfoHelper.initTableInfo(assistant, SysUserRole.class);
        TableInfoHelper.initTableInfo(assistant, SysUserAccountScope.class);
    }

    @BeforeEach
    void setUp() {
        service = new RbacService(roleMapper, permissionMapper, rolePermissionMapper,
                userRoleMapper, accountScopeMapper);
    }

    private void mockRoles(String... roleNames) {
        when(userRoleMapper.selectList(any(Wrapper.class))).thenReturn(
                java.util.Arrays.stream(roleNames).map(name -> {
                    SysUserRole ur = new SysUserRole();
                    ur.setUserId(1L);
                    ur.setRoleId((long) name.hashCode());
                    return ur;
                }).toList());
        when(roleMapper.selectList(any(Wrapper.class))).thenReturn(
                java.util.Arrays.stream(roleNames).map(name -> {
                    SysRole role = new SysRole();
                    role.setId((long) name.hashCode());
                    role.setName(name);
                    return role;
                }).toList());
    }

    @Test
    void adminSeesAllAccounts() {
        mockRoles("ADMIN");

        assertThat(service.getVisibleAccountIds(1L)).isNull();
    }

    @Test
    void configuredScopeRestrictsVisibleAccounts() {
        mockRoles("OPERATOR");
        SysUserAccountScope scope1 = new SysUserAccountScope();
        scope1.setUserId(1L);
        scope1.setAccountId(10L);
        SysUserAccountScope scope2 = new SysUserAccountScope();
        scope2.setUserId(1L);
        scope2.setAccountId(20L);
        when(accountScopeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(scope1, scope2));

        assertThat(service.getVisibleAccountIds(1L)).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void noScopeRecordsDeniesAllAccountsByDefault() {
        mockRoles("OPERATOR");
        when(accountScopeMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(service.getVisibleAccountIds(1L)).isEmpty();
    }

    @Test
    void unconfiguredOperatorCannotPassAccountGuard() {
        mockRoles("OPERATOR");
        when(accountScopeMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.assertAccountVisible(1L, 10L))
                .isInstanceOf(com.feijimiao.xianyuassistant.exception.BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    void setScopeReplacesAllRowsAndPersistsNewOnes() {
        when(accountScopeMapper.delete(any(Wrapper.class))).thenReturn(1);

        service.setUserAccountScope(1L, List.of(10L, 20L));

        verify(accountScopeMapper).delete(any(Wrapper.class));
        ArgumentCaptor<SysUserAccountScope> captor = ArgumentCaptor.forClass(SysUserAccountScope.class);
        verify(accountScopeMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(SysUserAccountScope::getAccountId)
                .containsExactlyInAnyOrder(10L, 20L);
        assertThat(captor.getAllValues()).allSatisfy(scope ->
                assertThat(scope.getUserId()).isEqualTo(1L));
    }

    @Test
    void clearScopeOnlyDeletesRows() {
        when(accountScopeMapper.delete(any(Wrapper.class))).thenReturn(2);

        service.setUserAccountScope(1L, List.of());

        verify(accountScopeMapper).delete(any(Wrapper.class));
        verify(accountScopeMapper, org.mockito.Mockito.never()).insert(any(SysUserAccountScope.class));
    }
}
