package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.*;
import com.feijimiao.xianyuassistant.entity.XianyuKamiConfig;
import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;

import java.util.List;

public interface KamiConfigService {

    ResultObject<KamiConfigRespDTO> createOrUpdateConfig(KamiConfigReqDTO reqDTO);

    ResultObject<List<KamiConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId);

    ResultObject<KamiConfigRespDTO> getConfigById(Long id);

    ResultObject<Void> deleteConfig(Long id);

    ResultObject<KamiItemRespDTO> addKamiItem(KamiItemReqDTO reqDTO);

    ResultObject<Integer> batchImportKamiItems(KamiBatchImportReqDTO reqDTO);

    ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigId(Long kamiConfigId);

    ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigIdWithFilter(KamiItemQueryReqDTO reqDTO);

    ResultObject<Void> deleteKamiItem(Long id);

    ResultObject<Void> resetKamiItem(Long id);

    /**
     * 人工确认「预占中的卡密确实已交付」。
     */
    ResultObject<Void> confirmKamiItemDelivery(Long id);

    /**
     * 人工确认「预占中的卡密并未交付」，释放回库存。
     */
    ResultObject<Void> releaseKamiItemReservation(Long id);

    XianyuKamiItem acquireKami(Long kamiConfigId, String orderId);

    XianyuKamiItem findReservedKami(Long kamiConfigId, String reservationKey);

    XianyuKamiItem findOrMigrateLegacyReservation(List<Long> configIds, String legacyOrderId,
                                                   String reservationKey, int unitIndex);

    void releaseKamiReservation(Long kamiItemId, String reservationKey, String usageOrderId);

    /**
     * 确认预占的卡密已交付给买家：status 保持 1，写入交付确认时间。
     */
    void confirmKamiDelivered(Long kamiItemId, String reservationKey);

    /**
     * 释放滞留预占（订单已明确失败、或人工判定未交付）。与 {@link #releaseKamiReservation}
     * 的区别是允许使用记录缺失，用于修复崩溃留下的半成品数据。
     *
     * @return 是否真的释放成功
     */
    boolean releaseStaleKamiReservation(Long kamiItemId, String reservationKey, String reason);

    XianyuKamiConfig getConfig(Long kamiConfigId);

    ResultObject<List<KamiItemRespDTO>> exportKamiItems(KamiExportReqDTO reqDTO);
}
