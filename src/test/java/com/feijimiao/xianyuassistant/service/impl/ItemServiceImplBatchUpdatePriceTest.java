package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.BatchUpdatePriceReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.BatchUpdatePriceRespDTO;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.service.GoodsInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceImplBatchUpdatePriceTest {

    @Mock
    private GoodsInfoService goodsInfoService;

    private ItemServiceImpl itemService;

    @BeforeEach
    void setUp() {
        itemService = new ItemServiceImpl();
        ReflectionTestUtils.setField(itemService, "goodsInfoService", goodsInfoService);
    }

    @Test
    void reportsPerItemResultsAndContinuesAfterFailure() {
        when(goodsInfoService.updateItemPrice(eq(1L), eq("A"), any(BigDecimal.class)))
                .thenReturn(goods("A", "12.50"));
        when(goodsInfoService.updateItemPrice(eq(1L), eq("B"), any(BigDecimal.class)))
                .thenThrow(new IllegalArgumentException("商品不存在或不属于当前账号"));
        when(goodsInfoService.updateItemPrice(eq(1L), eq("C"), any(BigDecimal.class)))
                .thenReturn(goods("C", "12.50"));

        BatchUpdatePriceReqDTO request = request(List.of("A", "B", "C"));
        ResultObject<BatchUpdatePriceRespDTO> result = itemService.batchUpdatePrice(request);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getTotal()).isEqualTo(3);
        assertThat(result.getData().getSuccess()).isEqualTo(2);
        assertThat(result.getData().getFailed()).isEqualTo(1);
        assertThat(result.getData().getDetails()).extracting(BatchUpdatePriceRespDTO.ItemPriceResult::isSuccess)
                .containsExactly(true, false, true);
        assertThat(result.getData().getDetails().get(1).getMessage()).contains("商品不存在");
        verify(goodsInfoService).updateItemPrice(eq(1L), eq("A"), any(BigDecimal.class));
        verify(goodsInfoService).updateItemPrice(eq(1L), eq("B"), any(BigDecimal.class));
        verify(goodsInfoService).updateItemPrice(eq(1L), eq("C"), any(BigDecimal.class));
    }

    @Test
    void rejectsMoreThanFiftyItemsBeforeUpdating() {
        BatchUpdatePriceReqDTO request = request(IntStream.rangeClosed(1, 51)
                .mapToObj(index -> "goods-" + index)
                .toList());

        ResultObject<BatchUpdatePriceRespDTO> result = itemService.batchUpdatePrice(request);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMsg()).contains("50");
        verifyNoInteractions(goodsInfoService);
    }

    private BatchUpdatePriceReqDTO request(List<String> goodsIds) {
        BatchUpdatePriceReqDTO request = new BatchUpdatePriceReqDTO();
        request.setXianyuAccountId(1L);
        request.setXyGoodsIds(goodsIds);
        request.setSoldPrice(new BigDecimal("12.50"));
        return request;
    }

    private XianyuGoodsInfo goods(String goodsId, String price) {
        XianyuGoodsInfo goods = new XianyuGoodsInfo();
        goods.setXyGoodId(goodsId);
        goods.setSoldPrice(price);
        return goods;
    }
}
