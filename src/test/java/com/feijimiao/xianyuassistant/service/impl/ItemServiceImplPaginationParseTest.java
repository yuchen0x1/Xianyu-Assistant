package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.controller.dto.ItemListRespDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品列表分页响应解析回归测试。
 */
class ItemServiceImplPaginationParseTest {

    /**
     * 官方分页字段必须保留原始游标和动态分组，供下一页请求使用。
     */
    @Test
    void parsesServerPaginationCursorAndGroupContext() {
        ItemServiceImpl service = new ItemServiceImpl();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ret", List.of("SUCCESS::调用成功"));
        response.put("data", Map.of(
                "cardList", List.of(),
                "nextPage", "true",
                "nextPageModel", Map.of("cursor", "cursor-2"),
                "nextPageNum", 2,
                "itemGroupList", List.of(Map.of("groupId", "dynamic-group", "defaultGroup", true))
        ));

        ItemListRespDTO parsed = (ItemListRespDTO) ReflectionTestUtils.invokeMethod(
                service, "parseItemListResponse", response, 1, 20);

        assertThat(parsed.getSuccess()).isTrue();
        assertThat(parsed.getNextPage()).isTrue();
        assertThat(parsed.getNextPageModel()).isEqualTo(Map.of("cursor", "cursor-2"));
        assertThat(parsed.getNextPageNum()).isEqualTo(2);
        assertThat(parsed.getItemGroupList()).containsExactly(Map.of("groupId", "dynamic-group", "defaultGroup", true));
    }

    /**
     * nextPage=false 表示完整远端快照的终点，不能被字符串形态丢失。
     */
    @Test
    void parsesExplicitTerminalPageFlag() {
        ItemServiceImpl service = new ItemServiceImpl();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ret", List.of("SUCCESS::调用成功"));
        response.put("data", Map.of("cardList", List.of(), "nextPage", "false"));

        ItemListRespDTO parsed = (ItemListRespDTO) ReflectionTestUtils.invokeMethod(
                service, "parseItemListResponse", response, 2, 20);

        assertThat(parsed.getSuccess()).isTrue();
        assertThat(parsed.getNextPage()).isFalse();
    }
}
