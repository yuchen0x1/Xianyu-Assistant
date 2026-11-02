package com.feijimiao.xianyuassistant.backup.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsSku;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsSkuProperty;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsInfoMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsSkuMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsSkuPropertyMapper;
import com.feijimiao.xianyuassistant.service.bo.BackupImportReqBO;
import com.feijimiao.xianyuassistant.service.impl.DataBackupServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoodsBackupHandlerTest {

    @Mock
    private XianyuGoodsInfoMapper goodsInfoMapper;

    @Mock
    private XianyuAccountMapper accountMapper;

    @Mock
    private XianyuGoodsSkuMapper goodsSkuMapper;

    @Mock
    private XianyuGoodsSkuPropertyMapper goodsSkuPropertyMapper;

    private GoodsBackupHandler handler;
    private DataBackupServiceImpl service;

    @BeforeEach
    void setUp() {
        handler = new GoodsBackupHandler();
        ReflectionTestUtils.setField(handler, "goodsInfoMapper", goodsInfoMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(handler, "goodsSkuMapper", goodsSkuMapper);
        ReflectionTestUtils.setField(handler, "goodsSkuPropertyMapper", goodsSkuPropertyMapper);

        service = new DataBackupServiceImpl();
        ReflectionTestUtils.setField(service, "handlers", List.of(handler));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
    }

    @Test
    void goodsCanBeRestoredIndependentlyForAnExistingAccount() throws Exception {
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        account.setUnb("123456");
        when(accountMapper.selectList(null)).thenReturn(List.of(account));
        when(goodsInfoMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> modules = Map.of("goods", Map.of("goodsList", List.of(Map.of(
                "unb", "123456",
                "xyGoodId", "goods-1",
                "title", "测试商品",
                "status", 1
        ))));
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("version", "1.0");
        backup.put("modules", modules);

        BackupImportReqBO request = new BackupImportReqBO();
        request.setModules(List.of("goods"));
        request.setJsonData(new ObjectMapper().writeValueAsString(backup));

        service.importData(request);

        ArgumentCaptor<XianyuGoodsInfo> goodsCaptor = ArgumentCaptor.forClass(XianyuGoodsInfo.class);
        verify(goodsInfoMapper).insert(goodsCaptor.capture());
        assertEquals(7L, goodsCaptor.getValue().getXianyuAccountId());
        assertEquals("goods-1", goodsCaptor.getValue().getXyGoodId());
    }

    @Test
    void versionTwoRestoresSkuAndPropertyRowsWithTheMappedAccount() throws Exception {
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        account.setUnb("123456");
        when(accountMapper.selectList(null)).thenReturn(List.of(account));
        when(goodsSkuMapper.selectOne(any())).thenReturn(null);
        when(goodsSkuPropertyMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> sku = new LinkedHashMap<>();
        sku.put("unb", "123456");
        sku.put("xyGoodsId", "goods-1");
        sku.put("skuId", "sku-1");
        sku.put("price", 1299);
        sku.put("quantity", 3);
        sku.put("propertyText", "等级:新号");
        sku.put("propertyId", 10);
        sku.put("valueId", 20);
        sku.put("valueText", "新号");

        Map<String, Object> property = new LinkedHashMap<>();
        property.put("unb", "123456");
        property.put("xyGoodsId", "goods-1");
        property.put("propertyId", 10);
        property.put("propertyText", "等级");
        property.put("valueId", 20);
        property.put("valueText", "新号");

        Map<String, Object> modules = Map.of("goods", Map.of(
                "goodsList", List.of(),
                "goodsSkus", List.of(sku),
                "goodsSkuProperties", List.of(property)
        ));
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("version", "2.0");
        backup.put("modules", modules);
        BackupImportReqBO request = new BackupImportReqBO();
        request.setModules(List.of("goods"));
        request.setJsonData(new ObjectMapper().writeValueAsString(backup));

        service.importData(request);

        ArgumentCaptor<XianyuGoodsSku> skuCaptor = ArgumentCaptor.forClass(XianyuGoodsSku.class);
        verify(goodsSkuMapper).insert(skuCaptor.capture());
        assertEquals(7L, skuCaptor.getValue().getXianyuAccountId());
        assertEquals("sku-1", skuCaptor.getValue().getSkuId());
        assertEquals(1299, skuCaptor.getValue().getPrice());

        ArgumentCaptor<XianyuGoodsSkuProperty> propertyCaptor =
                ArgumentCaptor.forClass(XianyuGoodsSkuProperty.class);
        verify(goodsSkuPropertyMapper).insert(propertyCaptor.capture());
        assertEquals(7L, propertyCaptor.getValue().getXianyuAccountId());
        assertEquals(20, propertyCaptor.getValue().getValueId());
    }

    @Test
    void skuExportSafelyDerivesMissingLegacyAccountFromTheUniqueGoodsRecord() {
        XianyuGoodsSku sku = new XianyuGoodsSku();
        sku.setXyGoodsId("goods-1");
        sku.setSkuId("sku-1");
        XianyuGoodsInfo goods = new XianyuGoodsInfo();
        goods.setXyGoodId("goods-1");
        goods.setXianyuAccountId(7L);
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        account.setUnb("123456");
        when(goodsInfoMapper.selectList(null)).thenReturn(List.of());
        when(goodsSkuMapper.selectList(null)).thenReturn(List.of(sku));
        when(goodsSkuPropertyMapper.selectList(null)).thenReturn(List.of());
        when(goodsInfoMapper.selectOne(any())).thenReturn(goods);
        when(accountMapper.selectById(7L)).thenReturn(account);

        Map<String, Object> exported = handler.exportData();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skus = (List<Map<String, Object>>) exported.get("goodsSkus");
        assertEquals("123456", skus.get(0).get("unb"));
    }
}
