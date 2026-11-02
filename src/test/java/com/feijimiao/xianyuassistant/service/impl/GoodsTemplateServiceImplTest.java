package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.controller.dto.PublishItemReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.PublishItemRespDTO;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsTemplate;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsTemplateMapper;
import com.feijimiao.xianyuassistant.service.PublishService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoodsTemplateServiceImplTest {

    @Mock private XianyuGoodsTemplateMapper templateMapper;
    @Mock private PublishService publishService;

    private GoodsTemplateServiceImpl buildService() {
        GoodsTemplateServiceImpl service = new GoodsTemplateServiceImpl();
        ReflectionTestUtils.setField(service, "templateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "publishService", publishService);
        return service;
    }

    @Test
    void importCsvSkipsHeaderAndParsesQuotedFields() {
        GoodsTemplateServiceImpl service = buildService();
        String csv = "\uFEFF标题,描述,价格,图片URL,类目ID,备注\n" +
                "测试商品A,\"包含,逗号的描述\",9.9,http://img/a.jpg;http://img/b.jpg,cat-1,备注A\n" +
                "测试商品B,描述B,{价格},,\n";
        Map<String, Object> result = service.importCsv(csv);
        assertThat(result.get("successCount")).isEqualTo(2);
        assertThat(result.get("failCount")).isEqualTo(0);
        ArgumentCaptor<XianyuGoodsTemplate> captor = ArgumentCaptor.forClass(XianyuGoodsTemplate.class);
        verify(templateMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        XianyuGoodsTemplate first = captor.getAllValues().get(0);
        assertThat(first.getTitle()).isEqualTo("测试商品A");
        assertThat(first.getDescription()).isEqualTo("包含,逗号的描述");
        assertThat(first.getPricePlaceholder()).isEqualTo("9.9");
        assertThat(first.getImageUrls()).isEqualTo("http://img/a.jpg;http://img/b.jpg");
    }

    @Test
    void importCsvReportsInvalidRows() {
        GoodsTemplateServiceImpl service = buildService();
        String csv = "标题,价格\n,\n";  // 空标题行
        Map<String, Object> result = service.importCsv(csv);
        assertThat(result.get("successCount")).isEqualTo(0);
        assertThat(result.get("failCount")).isEqualTo(1);
    }

    @Test
    void batchPublishPublishesNumericPriceAndSkipsPlaceholder() {
        XianyuGoodsTemplate ok = new XianyuGoodsTemplate();
        ok.setId(1L);
        ok.setTitle("模板1");
        ok.setPricePlaceholder("19.9元");
        ok.setImageUrls("http://img/a.jpg");
        XianyuGoodsTemplate bad = new XianyuGoodsTemplate();
        bad.setId(2L);
        bad.setTitle("模板2");
        bad.setPricePlaceholder("{价格}");
        when(templateMapper.selectById(1L)).thenReturn(ok);
        when(templateMapper.selectById(2L)).thenReturn(bad);
        PublishItemRespDTO resp = new PublishItemRespDTO();
        resp.setSuccess(true);
        resp.setItemId("item-1");
        resp.setMessage("ok");
        when(publishService.publish(any(PublishItemReqDTO.class))).thenReturn(resp);

        GoodsTemplateServiceImpl service = buildService();
        List<Map<String, Object>> results = service.batchPublish(100L, List.of(1L, 2L));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("success")).isEqualTo(true);
        assertThat(results.get(0).get("itemId")).isEqualTo("item-1");
        assertThat(results.get(1).get("success")).isEqualTo(false);

        ArgumentCaptor<PublishItemReqDTO> captor = ArgumentCaptor.forClass(PublishItemReqDTO.class);
        verify(publishService).publish(captor.capture());
        PublishItemReqDTO req = captor.getValue();
        assertThat(req.getTitle()).isEqualTo("模板1");
        assertThat(req.getPrice()).isEqualTo(new BigDecimal("19.9"));
        assertThat(req.getImages()).containsExactly("http://img/a.jpg");
    }
}