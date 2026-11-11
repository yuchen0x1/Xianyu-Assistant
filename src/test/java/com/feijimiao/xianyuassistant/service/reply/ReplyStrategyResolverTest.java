package com.feijimiao.xianyuassistant.service.reply;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplyStrategyResolverTest {

    @Mock
    private XianyuGoodsConfigMapper goodsConfigMapper;
    @Mock
    private KeywordReplyStrategy keywordReplyStrategy;
    @Mock
    private KeywordWithAIPolishStrategy keywordWithAIPolishStrategy;
    @Mock
    private AIReplyStrategy aiReplyStrategy;

    private ReplyStrategyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ReplyStrategyResolver();
        ReflectionTestUtils.setField(resolver, "goodsConfigMapper", goodsConfigMapper);
        ReflectionTestUtils.setField(resolver, "keywordReplyStrategy", keywordReplyStrategy);
        ReflectionTestUtils.setField(resolver, "keywordWithAIPolishStrategy", keywordWithAIPolishStrategy);
        ReflectionTestUtils.setField(resolver, "aiReplyStrategy", aiReplyStrategy);
    }

    @Test
    void emptyMessageListDoesNotQueryConfiguration() {
        assertThat(resolver.resolve(List.of())).isNull();
        verify(goodsConfigMapper, never()).selectByAccountAndGoodsId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nullMessageEntryDoesNotThrow() {
        assertThat(resolver.resolve(java.util.Collections.singletonList(null))).isNull();
        verify(goodsConfigMapper, never()).selectByAccountAndGoodsId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void selectsCombinedStrategyWhenBothSwitchesAreEnabled() {
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(7L);
        message.setXyGoodsId("goods-1");
        XianyuGoodsConfig config = new XianyuGoodsConfig();
        config.setXianyuKeywordReplyOn(1);
        config.setXianyuAutoReplyOn(1);
        when(goodsConfigMapper.selectByAccountAndGoodsId(7L, "goods-1")).thenReturn(config);

        assertThat(resolver.resolve(List.of(message))).isSameAs(keywordWithAIPolishStrategy);
    }
}
