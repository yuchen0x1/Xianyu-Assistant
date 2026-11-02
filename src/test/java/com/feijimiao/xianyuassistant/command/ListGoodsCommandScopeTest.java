package com.feijimiao.xianyuassistant.command;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.controller.dto.ItemListFromDbReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.ItemListFromDbRespDTO;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.service.ItemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListGoodsCommandScopeTest {

    private final ItemService itemService = mock(ItemService.class);
    private final RbacService rbacService = mock(RbacService.class);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void nonAdminResultsAreFilteredToVisibleAccount() {
        UserContext.set(7L, "operator");
        when(rbacService.getCommandVisibleAccountIds(7L)).thenReturn(List.of(10L));
        XianyuGoodsInfo visible = new XianyuGoodsInfo();
        visible.setId(1L);
        visible.setXianyuAccountId(10L);
        XianyuGoodsInfo hidden = new XianyuGoodsInfo();
        hidden.setId(2L);
        hidden.setXianyuAccountId(20L);
        ItemListFromDbRespDTO data = new ItemListFromDbRespDTO();
        data.setItems(List.of(visible, hidden));
        data.setTotalCount(2);
        when(itemService.getItemsFromDb(any(ItemListFromDbReqDTO.class)))
                .thenReturn(ResultObject.success(data));

        Map<String, Object> result = new ListGoodsCommand(itemService, rbacService)
                .execute(Map.of("accountId", 10L));

        assertThat(result.get("items")).asList().containsExactly(visible);
    }

    @Test
    void nonAdminCannotQueryGoodsWithoutAccountId() {
        UserContext.set(7L, "operator");
        when(rbacService.getCommandVisibleAccountIds(7L)).thenReturn(List.of(10L));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new ListGoodsCommand(itemService, rbacService).execute(Map.of()))
                .isInstanceOf(CommandExecutionException.class)
                .hasMessage("非管理员必须指定 accountId");
    }
}
