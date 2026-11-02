package com.feijimiao.xianyuassistant.service.bargain;

import com.feijimiao.xianyuassistant.entity.BargainSession;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 议价决策测试：同意/还价/坚持底价的决策矩阵与提示词约束
 */
class BargainPlanServiceTest {

    private final BargainPlanService service = new BargainPlanService();
    private final BargainDetector detector = new BargainDetector();

    private XianyuGoodsConfig config(String floor, String step, Integer maxRounds) {
        XianyuGoodsConfig config = new XianyuGoodsConfig();
        config.setBargainOn(1);
        config.setBargainFloorPrice(floor);
        config.setBargainStepPrice(step);
        config.setBargainMaxRounds(maxRounds);
        return config;
    }

    private BargainSession session(int roundsUsed) {
        BargainSession session = new BargainSession();
        session.setRoundsUsed(roundsUsed);
        session.setStatus(BargainSession.STATUS_ACTIVE);
        return session;
    }

    private BargainDetector.BargainIntent intent(String price) {
        BargainDetector.BargainIntent intent = new BargainDetector.BargainIntent();
        intent.setBargain(true);
        if (price != null) {
            intent.setOfferedPrice(new BigDecimal(price));
        }
        return intent;
    }

    @Test
    void 出价不低于保底价应直接同意() {
        BargainDecision decision = service.decide(config("80", "5", 3), session(0), intent("85"), new BigDecimal("100"));
        assertThat(decision.getAction()).isEqualTo(BargainDecision.Action.ACCEPT);
        assertThat(decision.getCounterPrice()).isEqualByComparingTo("85");
    }

    @Test
    void 第一轮应按步长还价() {
        BargainDecision decision = service.decide(config("80", "5", 3), session(0), intent("50"), new BigDecimal("100"));
        assertThat(decision.getAction()).isEqualTo(BargainDecision.Action.COUNTER);
        // 售价100 - 步长5×第1轮 = 95
        assertThat(decision.getCounterPrice()).isEqualByComparingTo("95");
    }

    @Test
    void 还价不应低于保底价() {
        BargainDecision decision = service.decide(config("80", "50", 3), session(0), intent("50"), new BigDecimal("100"));
        assertThat(decision.getAction()).isEqualTo(BargainDecision.Action.COUNTER);
        assertThat(decision.getCounterPrice()).isEqualByComparingTo("80");
    }

    @Test
    void 买家出价达到本轮可让价格应直接同意() {
        // 售价100，步长10，第1轮可让到90；买家出90应直接同意
        BargainDecision decision = service.decide(config("80", "10", 3), session(0), intent("90"), new BigDecimal("100"));
        assertThat(decision.getAction()).isEqualTo(BargainDecision.Action.ACCEPT);
    }

    @Test
    void 超过最大轮数应坚持底价() {
        BargainDecision decision = service.decide(config("80", "5", 3), session(2), intent("50"), new BigDecimal("100"));
        assertThat(decision.getAction()).isEqualTo(BargainDecision.Action.FIRM);
        assertThat(decision.getCounterPrice()).isEqualByComparingTo("80");
    }

    @Test
    void 纯砍价话术无出价也应给出还价() {
        BargainDecision decision = service.decide(config("80", "5", 3), session(0), intent(null), new BigDecimal("100"));
        assertThat(decision.getAction()).isEqualTo(BargainDecision.Action.COUNTER);
        assertThat(decision.getCounterPrice()).isEqualByComparingTo("95");
    }

    @Test
    void 无售价时应从保底价向上让价() {
        // 无售价：第1轮 = 80 + 5×(3-1) = 90，最后轮 = 80
        BargainDecision first = service.decide(config("80", "5", 3), session(0), intent("50"), null);
        assertThat(first.getAction()).isEqualTo(BargainDecision.Action.COUNTER);
        assertThat(first.getCounterPrice()).isEqualByComparingTo("90");

        BargainDecision last = service.decide(config("80", "5", 3), session(1), intent("50"), null);
        assertThat(last.getCounterPrice()).isEqualByComparingTo("85");
    }

    @Test
    void 未配置步长时应按售价与保底价均分() {
        // 售价100，保底80，3轮均分：每轮让 (100-80)/3 向上取整 = 6.7，第1轮报 93.3
        BargainDecision decision = service.decide(config("80", null, 3), session(0), intent("50"), new BigDecimal("100"));
        assertThat(decision.getAction()).isEqualTo(BargainDecision.Action.COUNTER);
        assertThat(decision.getCounterPrice()).isEqualByComparingTo("93.3");
    }

    @Test
    void 未配置最大轮数时默认3轮() {
        BargainDecision decision = service.decide(config("80", "5", null), session(2), intent("50"), new BigDecimal("100"));
        assertThat(decision.getAction()).isEqualTo(BargainDecision.Action.FIRM);
        assertThat(decision.getMaxRounds()).isEqualTo(3);
    }

    @Test
    void 提示词应包含决策与价格且不得泄露系统字眼以外的价格控制() {
        BargainDecision decision = service.decide(config("80", "5", 3), session(0), intent("50"), new BigDecimal("100"));
        String prompt = service.buildPromptSection(decision);
        assertThat(prompt).contains("95").contains("1/3").contains("保底价：80");
        assertThat(prompt).contains("仅当买家砍价或询问最低价时应用");
    }

    @Test
    void 降级模板应包含报价() {
        BargainDecision counter = service.decide(config("80", "5", 3), session(0), intent("50"), new BigDecimal("100"));
        assertThat(service.buildFallbackReply(counter)).contains("95");

        BargainDecision accept = service.decide(config("80", "5", 3), session(0), intent("90"), new BigDecimal("100"));
        assertThat(service.buildFallbackReply(accept)).contains("90");

        BargainDecision firm = service.decide(config("80", "5", 3), session(2), intent("50"), new BigDecimal("100"));
        assertThat(service.buildFallbackReply(firm)).contains("80");
    }

    @Test
    void 让价差额应结合售价换算为出价() {
        BargainDetector.BargainIntent deductionIntent = detector.detect("能不能少20");
        BargainDecision decision = service.decide(config("80", "5", 3), session(0), deductionIntent, new BigDecimal("100"));
        // 换算出价 100-20=80，达到保底价 → 直接同意
        assertThat(decision.getAction()).isEqualTo(BargainDecision.Action.ACCEPT);
        assertThat(decision.getCounterPrice()).isEqualByComparingTo("80");
    }
}
