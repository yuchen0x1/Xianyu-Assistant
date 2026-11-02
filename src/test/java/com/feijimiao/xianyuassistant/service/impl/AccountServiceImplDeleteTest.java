package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsSku;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsSkuProperty;
import com.feijimiao.xianyuassistant.entity.XianyuKamiConfig;
import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;
import com.feijimiao.xianyuassistant.entity.XianyuKamiUsageRecord;
import com.feijimiao.xianyuassistant.entity.XianyuKeywordReplyContent;
import com.feijimiao.xianyuassistant.entity.XianyuKeywordReplyRule;
import com.feijimiao.xianyuassistant.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceImplDeleteTest {

    @InjectMocks
    private AccountServiceImpl service;

    @Mock private XianyuAccountMapper accountMapper;
    @Mock private XianyuCookieMapper cookieMapper;
    @Mock private XianyuChatMessageMapper chatMessageMapper;
    @Mock private XianyuGoodsInfoMapper goodsInfoMapper;
    @Mock private XianyuGoodsConfigMapper goodsConfigMapper;
    @Mock private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    @Mock private XianyuDeliveryBlockRuleMapper deliveryBlockRuleMapper;
    @Mock private XianyuGoodsOrderMapper orderMapper;
    @Mock private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;
    @Mock private XianyuOperationLogMapper operationLogMapper;
    @Mock private XianyuKamiConfigMapper kamiConfigMapper;
    @Mock private XianyuKamiItemMapper kamiItemMapper;
    @Mock private XianyuKamiUsageRecordMapper kamiUsageRecordMapper;
    @Mock private XianyuKeywordReplyRuleMapper keywordReplyRuleMapper;
    @Mock private XianyuKeywordReplyContentMapper keywordReplyContentMapper;
    @Mock private XianyuGoodsSkuMapper goodsSkuMapper;
    @Mock private XianyuGoodsSkuPropertyMapper goodsSkuPropertyMapper;
    @Mock private XianyuHumanInterventionRecordMapper humanInterventionRecordMapper;

    @BeforeAll
    static void initializeMyBatisLambdaMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        java.util.stream.Stream.of(
                XianyuAccount.class, XianyuCookie.class, XianyuGoodsInfo.class, XianyuGoodsSku.class,
                XianyuGoodsSkuProperty.class, XianyuKamiConfig.class, XianyuKamiItem.class,
                XianyuKamiUsageRecord.class, XianyuKeywordReplyContent.class, XianyuKeywordReplyRule.class)
                .forEach(clazz -> TableInfoHelper.initTableInfo(assistant, clazz));
    }

    @Test
    void deleteAccountRemovesAllKnownAccountOwnedData() {
        Long accountId = 7L;
        XianyuKamiConfig kamiConfig = new XianyuKamiConfig();
        kamiConfig.setId(11L);
        XianyuKeywordReplyRule keywordRule = new XianyuKeywordReplyRule();
        keywordRule.setId(13L);
        when(kamiConfigMapper.selectList(any())).thenReturn(List.of(kamiConfig));
        when(keywordReplyRuleMapper.selectList(any())).thenReturn(List.of(keywordRule));

        assertTrue(service.deleteAccountAndRelatedData(accountId));

        verify(kamiUsageRecordMapper).delete(any());
        verify(kamiItemMapper).delete(any());
        verify(kamiConfigMapper).delete(any());
        verify(keywordReplyContentMapper).delete(any());
        verify(keywordReplyRuleMapper).delete(any());
        verify(goodsSkuPropertyMapper).delete(any());
        verify(goodsSkuMapper).delete(any());
        verify(humanInterventionRecordMapper).deleteByAccountId(accountId);
        verify(deliveryBlockRuleMapper).deleteByAccountId(accountId);
        verify(accountMapper).deleteById(accountId);
    }

    @Test
    void updatingCookieRestoresTheAccountAndInvalidatesTheOldWebSocketToken() {
        Long accountId = 7L;
        XianyuAccount account = new XianyuAccount();
        account.setId(accountId);
        account.setStatus(-2);
        account.setUnb("buyer-7");
        XianyuCookie cookie = new XianyuCookie();
        cookie.setXianyuAccountId(accountId);
        cookie.setId(17L);
        cookie.setWebsocketToken("old-token");
        cookie.setTokenExpireTime(Long.MAX_VALUE);
        when(accountMapper.selectById(accountId)).thenReturn(account);
        when(cookieMapper.selectOne(any())).thenReturn(cookie);

        assertTrue(service.updateAccountCookie(
                accountId, "buyer-7", "unb=buyer-7; _m_h5_tk=new-token_123"));

        assertEquals(1, account.getStatus());
        assertEquals(1, account.getWebsocketEnabled());
        assertEquals(1, cookie.getCookieStatus());
        assertNull(cookie.getWebsocketToken());
        assertEquals(0L, cookie.getTokenExpireTime());
        verify(accountMapper).updateById(account);
        verify(cookieMapper).updateById(cookie);
        verify(cookieMapper).update(any(), any());
    }

    @Test
    void savingAnExistingAccountInvalidatesTheOldWebSocketToken() {
        Long accountId = 7L;
        XianyuAccount account = new XianyuAccount();
        account.setId(accountId);
        account.setUnb("buyer-7");
        XianyuCookie cookie = new XianyuCookie();
        cookie.setId(17L);
        cookie.setXianyuAccountId(accountId);
        cookie.setWebsocketToken("old-token");
        cookie.setTokenExpireTime(Long.MAX_VALUE);
        when(accountMapper.selectOne(any())).thenReturn(account);
        when(cookieMapper.selectOne(any())).thenReturn(cookie);

        assertEquals(accountId, service.saveAccountAndCookie(
                "buyer", "buyer-7", "unb=buyer-7; _m_h5_tk=new-token_123", "new-token"));

        assertEquals(1, account.getWebsocketEnabled());
        assertNull(cookie.getWebsocketToken());
        assertEquals(0L, cookie.getTokenExpireTime());
        verify(cookieMapper).updateById(cookie);
        verify(cookieMapper).update(any(), any());
    }
}
