package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.controller.dto.ScheduleTaskReqDTO;
import com.feijimiao.xianyuassistant.entity.XianyuScheduleTask;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.mapper.XianyuScheduleTaskMapper;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.service.ItemService;
import com.feijimiao.xianyuassistant.service.PublishService;
import com.feijimiao.xianyuassistant.utils.XianyuAuthFailureGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleTaskServiceImplAccountScopeTest {

    @Mock
    private XianyuScheduleTaskMapper scheduleTaskMapper;

    @Mock
    private ItemService itemService;

    @Mock
    private PublishService publishService;

    @Mock
    private XianyuAuthFailureGuard authFailureGuard;

    @Mock
    private RbacService rbacService;

    @InjectMocks
    private ScheduleTaskServiceImpl service;

    @BeforeAll
    static void initializeMyBatisLambdaMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, XianyuScheduleTask.class);
    }

    @BeforeEach
    void setUp() {
        UserContext.set(42L, "restricted-user");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createRejectsAnotherAccountBeforeInsert() {
        doThrow(new BusinessException(403, "无权访问该账号的数据"))
                .when(rbacService).assertAccountVisible(42L, 99L);

        assertThatThrownBy(() -> service.create(request(99L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);

        verify(scheduleTaskMapper, never()).insert(any(XianyuScheduleTask.class));
    }

    @Test
    void listReturnsNoRowsForEmptyVisibilityWithoutMapperQuery() {
        when(rbacService.getCommandVisibleAccountIds(42L)).thenReturn(List.of());

        assertThat(service.list()).isEmpty();

        verifyNoInteractions(scheduleTaskMapper);
    }

    @Test
    void listConstrainsQueryToVisibleAccounts() {
        when(rbacService.getCommandVisibleAccountIds(42L)).thenReturn(List.of(10L, 20L));
        when(scheduleTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.list();

        ArgumentCaptor<LambdaQueryWrapper<XianyuScheduleTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(scheduleTaskMapper).selectList(captor.capture());
        assertThat(captor.getValue().getExpression().getNormal()).hasSize(3);
        assertThat(captor.getValue().getExpression().getNormal().get(1).toString()).isEqualTo("IN");
    }

    @Test
    void updateRejectsTaskOwnedByAnotherAccountBeforeMutation() {
        XianyuScheduleTask otherAccountTask = task(99L);
        when(scheduleTaskMapper.selectById(7L)).thenReturn(otherAccountTask);
        doThrow(new BusinessException(403, "无权访问该账号的数据"))
                .when(rbacService).assertAccountVisible(42L, 99L);

        assertThatThrownBy(() -> service.update(7L, request(10L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);

        verify(scheduleTaskMapper, never()).updateById(any(XianyuScheduleTask.class));
    }

    @Test
    void updateRejectsChangingOwnedTaskToAnotherAccountBeforeMutation() {
        when(scheduleTaskMapper.selectById(7L)).thenReturn(task(10L));
        lenient().doThrow(new BusinessException(403, "无权访问该账号的数据"))
                .when(rbacService).assertAccountVisible(42L, 99L);

        assertThatThrownBy(() -> service.update(7L, request(99L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);

        verify(scheduleTaskMapper, never()).updateById(any(XianyuScheduleTask.class));
    }

    @Test
    void toggleAndDeleteRejectAnotherAccountsTaskBeforeMutation() {
        when(scheduleTaskMapper.selectById(7L)).thenReturn(task(99L));
        doThrow(new BusinessException(403, "无权访问该账号的数据"))
                .when(rbacService).assertAccountVisible(42L, 99L);

        assertThatThrownBy(() -> service.toggle(7L, false))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);

        verify(scheduleTaskMapper, never()).updateById(any(XianyuScheduleTask.class));
        verify(scheduleTaskMapper, never()).deleteById(7L);
    }

    @Test
    void actionsRejectTasksWithNullOwnerForRestrictedUsers() {
        XianyuScheduleTask unowned = task(null);
        when(scheduleTaskMapper.selectById(7L)).thenReturn(unowned);
        doThrow(new BusinessException(403, "受限用户必须指定账号"))
                .when(rbacService).assertAccountVisible(42L, null);

        assertThatThrownBy(() -> service.toggle(7L, false))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);

        verify(scheduleTaskMapper, never()).updateById(any(XianyuScheduleTask.class));
        verify(scheduleTaskMapper, never()).deleteById(7L);
    }

    private ScheduleTaskReqDTO request(Long accountId) {
        ScheduleTaskReqDTO req = new ScheduleTaskReqDTO();
        req.setTaskName("test-task");
        req.setTaskType("SYNC_GOODS");
        req.setCronExpr("0 0 * * * *");
        req.setAccountId(accountId);
        req.setEnabled(false);
        return req;
    }

    private XianyuScheduleTask task(Long accountId) {
        XianyuScheduleTask task = new XianyuScheduleTask();
        task.setId(7L);
        task.setAccountId(accountId);
        task.setTaskType("SYNC_GOODS");
        task.setCronExpr("0 0 * * * *");
        task.setEnabled(0);
        task.setPaused(0);
        return task;
    }
}
