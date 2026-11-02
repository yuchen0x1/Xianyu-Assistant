package com.feijimiao.xianyuassistant.service.delivery;

import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;
import com.feijimiao.xianyuassistant.entity.XianyuKamiUsageRecord;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiUsageRecordMapper;
import com.feijimiao.xianyuassistant.service.KamiConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡密发货策略（deliveryMode=2）
 *
 * <p>从卡密仓库获取可用卡密，用模板替换 {kmKey} 占位符后返回发货内容。</p>
 *
 * <h3>流程：</h3>
 * <ol>
 *   <li>遍历绑定的卡密配置ID列表（逗号分隔）</li>
 *   <li>调用 {@link KamiConfigService#acquireKami} 获取一条未使用卡密</li>
 *   <li>记录卡密使用记录</li>
 *   <li>用模板替换 {kmKey} 占位符</li>
 * </ol>
 */
@Slf4j
@Component
public class KamiDeliveryStrategy implements DeliveryContentStrategy {

    @Autowired
    private KamiConfigService kamiConfigService;

    @Autowired
    private XianyuKamiUsageRecordMapper kamiUsageRecordMapper;

    @Override
    public boolean supports(int deliveryMode) {
        return deliveryMode == 2;
    }

    @Override
    @Transactional
    public String resolve(DeliveryContext context) {
        context.setReservation(DeliveryReservation.none());
        String content = acquireKamiContent(context);
        if (content == null) {
            log.warn("【账号{}】卡密发货模式下无可用卡密: xyGoodsId={}, kamiConfigIds={}",
                    context.getAccountId(), context.getXyGoodsId(), context.getDeliveryConfig().getKamiConfigIds());
            return null;
        }
        log.info("【账号{}】卡密发货模式: content长度={}", context.getAccountId(), content.length());
        return content;
    }

    private String acquireKamiContent(DeliveryContext context) {
        String kamiConfigIds = context.getDeliveryConfig().getKamiConfigIds();
        Long accountId = context.getAccountId();
        String xyGoodsId = context.getXyGoodsId();
        if (kamiConfigIds == null || kamiConfigIds.trim().isEmpty()) {
            log.warn("【账号{}】卡密发货未绑定卡密配置: xyGoodsId={}", accountId, xyGoodsId);
            return null;
        }

        String actionId = context.getActionId();
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalStateException("卡密发货缺少幂等动作标识");
        }
        String usageOrderId = context.getOrderId();
        if (usageOrderId == null || usageOrderId.isBlank()) {
            usageOrderId = actionId;
        }
        String cid = context.getSId() != null ? context.getSId().replace("@goofish", "") : null;

        List<Long> configIds = parseConfigIds(kamiConfigIds, accountId);
        XianyuKamiItem kamiItem = null;
        boolean reusedExistingReservation = false;

        // Search every bound warehouse before taking new stock. Otherwise a retry could
        // consume a new card from an earlier warehouse instead of reusing its old action.
        for (Long configId : configIds) {
            kamiItem = kamiConfigService.findReservedKami(configId, actionId);
            if (kamiItem != null) {
                reusedExistingReservation = true;
                break;
            }
        }

        if (kamiItem == null && !usageOrderId.equals(actionId)) {
            int unitIndex = parseUnitIndex(actionId);
            kamiItem = kamiConfigService.findOrMigrateLegacyReservation(
                    configIds, usageOrderId, actionId, unitIndex);
            reusedExistingReservation = kamiItem != null;
        }

        if (kamiItem == null) {
            for (Long configId : configIds) {
                kamiItem = kamiConfigService.acquireKami(configId, actionId);
                if (kamiItem != null) {
                    break;
                }
            }
        }

        if (kamiItem == null) {
            return null;
        }

        XianyuKamiUsageRecord existingUsage = kamiUsageRecordMapper.findByItemIdAndOrderId(
                kamiItem.getId(), usageOrderId);
        if (existingUsage == null) {
            XianyuKamiUsageRecord usageRecord = new XianyuKamiUsageRecord();
            usageRecord.setKamiConfigId(kamiItem.getKamiConfigId());
            usageRecord.setKamiItemId(kamiItem.getId());
            usageRecord.setXianyuAccountId(accountId);
            usageRecord.setXyGoodsId(xyGoodsId);
            usageRecord.setOrderId(usageOrderId);
            usageRecord.setKamiContent(kamiItem.getKamiContent());
            usageRecord.setBuyerUserId(cid);
            usageRecord.setBuyerUserName(context.getBuyerUserName());
            int inserted = kamiUsageRecordMapper.insert(usageRecord);
            if (inserted != 1) {
                throw new IllegalStateException("卡密使用记录写入失败: itemId=" + kamiItem.getId());
            }

            if (reusedExistingReservation) {
                // 复用历史预占：它可能已经被买家看到，因此只允许确认交付，绝不允许释放回库存。
                context.setReservation(DeliveryReservation.commitWith(confirmAction(kamiItem.getId(), actionId)));
            } else {
                Long reservedItemId = kamiItem.getId();
                String reservedActionId = actionId;
                String reservedUsageOrderId = usageOrderId;
                context.setReservation(DeliveryReservation.rollbackAndCommitWith(
                        () -> kamiConfigService.releaseKamiReservation(
                                reservedItemId, reservedActionId, reservedUsageOrderId),
                        confirmAction(reservedItemId, reservedActionId)));
            }
            log.info("【账号{}】卡密预占成功: configId={}, itemId={}, actionId={}",
                    accountId, kamiItem.getKamiConfigId(), kamiItem.getId(), actionId);
        } else {
            // A previously recorded action is already committed. Retrying it must not
            // release or consume another card if the transport fails again.
            context.setReservation(DeliveryReservation.commitWith(confirmAction(kamiItem.getId(), actionId)));
            log.info("【账号{}】复用已记录的卡密发货动作: itemId={}, actionId={}",
                    accountId, kamiItem.getId(), actionId);
        }

        String kamiContent = kamiItem.getKamiContent();
        String template = context.getDeliveryConfig().getKamiDeliveryTemplate();
        if (template != null && !template.trim().isEmpty()) {
            kamiContent = template.replace("{kmKey}", kamiContent);
        }
        return kamiContent;
    }

    /**
     * 确认交付动作：发货成功后把预占转为「已交付」终态。
     * 确认失败不能影响已经成功的发货，因此这里只记录日志，由滞留巡检按订单终态兜底补记。
     */
    private Runnable confirmAction(Long kamiItemId, String reservationKey) {
        return () -> {
            try {
                kamiConfigService.confirmKamiDelivered(kamiItemId, reservationKey);
            } catch (RuntimeException e) {
                log.warn("卡密交付确认写入失败，留待巡检补记: itemId={}, actionId={}, errorType={}",
                        kamiItemId, reservationKey, e.getClass().getSimpleName());
            }
        };
    }

    private int parseUnitIndex(String actionId) {        int markerIndex = actionId.lastIndexOf("#delivery-");
        if (markerIndex < 0) {
            throw new IllegalStateException("卡密发货动作标识缺少件序号: " + actionId);
        }
        try {
            int oneBasedIndex = Integer.parseInt(actionId.substring(markerIndex + "#delivery-".length()));
            if (oneBasedIndex <= 0) {
                throw new NumberFormatException("件序号必须大于0");
            }
            return oneBasedIndex - 1;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("卡密发货动作件序号无效: " + actionId, e);
        }
    }

    private List<Long> parseConfigIds(String rawConfigIds, Long accountId) {
        List<Long> configIds = new ArrayList<>();
        for (String rawConfigId : rawConfigIds.split(",")) {
            try {
                configIds.add(Long.parseLong(rawConfigId.trim()));
            } catch (NumberFormatException e) {
                log.warn("【账号{}】卡密配置ID格式错误: {}", accountId, rawConfigId);
            }
        }
        return configIds;
    }
}
