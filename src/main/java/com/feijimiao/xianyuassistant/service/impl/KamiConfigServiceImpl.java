package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.*;
import com.feijimiao.xianyuassistant.entity.XianyuKamiConfig;
import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiItemMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiUsageRecordMapper;
import com.feijimiao.xianyuassistant.service.EmailNotifyService;
import com.feijimiao.xianyuassistant.service.KamiConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KamiConfigServiceImpl implements KamiConfigService {

    @Autowired
    private XianyuKamiConfigMapper kamiConfigMapper;

    @Autowired
    private XianyuKamiItemMapper kamiItemMapper;

    @Autowired
    private XianyuKamiUsageRecordMapper kamiUsageRecordMapper;

    @Autowired
    private EmailNotifyService emailNotifyService;

    @Autowired
    private com.feijimiao.xianyuassistant.monitor.AlertService alertService;

    @Autowired
    private com.feijimiao.xianyuassistant.webhook.service.WebhookService webhookService;

    @Autowired
    private com.feijimiao.xianyuassistant.rbac.service.RbacService rbacService;

    /**
     * 卡密配置归属校验：受限用户访问可见范围之外的账号配置时抛 403。
     * 覆盖按 configId/itemId 直达、绕过账号范围切面的访问形态。
     * 内部调用链（发货引擎/任务线程无 UserContext）天然放行。
     */
    private void assertConfigVisible(Long configId) {
        if (configId == null) {
            rbacService.assertAccountVisible(
                    com.feijimiao.xianyuassistant.context.UserContext.getUserId(), null);
            return;
        }
        XianyuKamiConfig config = kamiConfigMapper.selectById(configId);
        Long accountId = config != null ? config.getXianyuAccountId() : null;
        rbacService.assertAccountVisible(
                com.feijimiao.xianyuassistant.context.UserContext.getUserId(), accountId);
    }

    private final ReentrantLock acquireLock = new ReentrantLock();

    private final ConcurrentHashMap<Long, ReentrantLock> configLocks = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, Long> stockOutEmailSentTime = new ConcurrentHashMap<>();

    private static final long STOCK_OUT_EMAIL_INTERVAL_MS = 10 * 60 * 1000L;

    @Override
    public ResultObject<KamiConfigRespDTO> createOrUpdateConfig(KamiConfigReqDTO reqDTO) {
        try {
            XianyuKamiConfig config;
            if (reqDTO.getId() != null) {
                assertConfigVisible(reqDTO.getId());
                config = kamiConfigMapper.selectById(reqDTO.getId());
                if (config == null) {
                    return ResultObject.failed("卡密配置不存在");
                }
            } else {
                rbacService.assertAccountVisible(
                        com.feijimiao.xianyuassistant.context.UserContext.getUserId(),
                        reqDTO.getXianyuAccountId());
                config = new XianyuKamiConfig();
                config.setXianyuAccountId(reqDTO.getXianyuAccountId());
                config.setTotalCount(0);
                config.setUsedCount(0);
            }
            if (reqDTO.getAliasName() != null) {
                config.setAliasName(reqDTO.getAliasName());
            }
            if (reqDTO.getAlertEnabled() != null) {
                config.setAlertEnabled(reqDTO.getAlertEnabled());
            }
            if (reqDTO.getAlertThresholdType() != null) {
                config.setAlertThresholdType(reqDTO.getAlertThresholdType());
            }
            if (reqDTO.getAlertThresholdValue() != null) {
                config.setAlertThresholdValue(reqDTO.getAlertThresholdValue());
            }
            if (reqDTO.getAlertEmail() != null) {
                config.setAlertEmail(reqDTO.getAlertEmail());
            }
            if (reqDTO.getId() != null) {
                kamiConfigMapper.updateById(config);
            } else {
                kamiConfigMapper.insert(config);
            }
            return ResultObject.success(toConfigRespDTO(config));
        } catch (Exception e) {
            log.error("创建/更新卡密配置失败", e);
            return ResultObject.failed("创建/更新卡密配置失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<List<KamiConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId) {
        try {
            List<XianyuKamiConfig> configs = kamiConfigMapper.findByAccountId(xianyuAccountId);
            List<KamiConfigRespDTO> result = configs.stream()
                    .map(this::toConfigRespDTO)
                    .collect(Collectors.toList());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("查询卡密配置列表失败", e);
            return ResultObject.failed("查询卡密配置列表失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<KamiConfigRespDTO> getConfigById(Long id) {
        try {
            assertConfigVisible(id);
            XianyuKamiConfig config = kamiConfigMapper.selectById(id);
            if (config == null) {
                return ResultObject.failed("卡密配置不存在");
            }
            return ResultObject.success(toConfigRespDTO(config));
        } catch (Exception e) {
            log.error("查询卡密配置失败", e);
            return ResultObject.failed("查询卡密配置失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<Void> deleteConfig(Long id) {
        try {
            assertConfigVisible(id);
            List<XianyuKamiItem> items = kamiItemMapper.findByConfigId(id);
            for (XianyuKamiItem item : items) {
                kamiItemMapper.deleteById(item.getId());
            }
            kamiConfigMapper.deleteById(id);
            return ResultObject.success(null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 事务方法内吞异常返回 failed 会让事务正常提交，前面的删除留下半成品
            log.error("删除卡密配置失败", e);
            throw new BusinessException("删除卡密配置失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public ResultObject<KamiItemRespDTO> addKamiItem(KamiItemReqDTO reqDTO) {
        try {
            assertConfigVisible(reqDTO.getKamiConfigId());
            XianyuKamiConfig config = kamiConfigMapper.selectById(reqDTO.getKamiConfigId());
            if (config == null) {
                return ResultObject.failed("卡密配置不存在");
            }
            XianyuKamiItem item = new XianyuKamiItem();
            item.setKamiConfigId(reqDTO.getKamiConfigId());
            String content = reqDTO.getKamiContent().trim();
            item.setKamiContent(content);
            item.setStatus(0);
            item.setSortOrder(kamiItemMapper.countByConfigId(reqDTO.getKamiConfigId()));

            boolean duplicated = kamiItemMapper.countByConfigIdAndContent(reqDTO.getKamiConfigId(), content) > 0;
            kamiItemMapper.insert(item);
            refreshConfigCounts(reqDTO.getKamiConfigId());

            if (duplicated) {
                return ResultObject.success(toItemRespDTO(item), "卡密内容重复，已导入");
            }
            return ResultObject.success(toItemRespDTO(item));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("添加卡密失败", e);
            throw new BusinessException("添加卡密失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public ResultObject<Integer> batchImportKamiItems(KamiBatchImportReqDTO reqDTO) {
        try {
            assertConfigVisible(reqDTO.getKamiConfigId());
            XianyuKamiConfig config = kamiConfigMapper.selectById(reqDTO.getKamiConfigId());
            if (config == null) {
                return ResultObject.failed("卡密配置不存在");
            }
            String[] lines = reqDTO.getKamiContents().split("\\r?\\n");
            int baseOrder = kamiItemMapper.countByConfigId(reqDTO.getKamiConfigId());
            int added = 0;
            int duplicated = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                boolean dup = kamiItemMapper.countByConfigIdAndContent(reqDTO.getKamiConfigId(), trimmed) > 0;
                if (dup) duplicated++;

                XianyuKamiItem item = new XianyuKamiItem();
                item.setKamiConfigId(reqDTO.getKamiConfigId());
                item.setKamiContent(trimmed);
                item.setStatus(0);
                item.setSortOrder(baseOrder + added);
                kamiItemMapper.insert(item);
                added++;
            }
            refreshConfigCounts(reqDTO.getKamiConfigId());
            String msg = duplicated > 0
                    ? String.format("成功导入%d条，其中重复%d条", added, duplicated)
                    : String.format("成功导入%d条", added);
            return ResultObject.success(added, msg);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 批量导入中途失败必须整体回滚：否则前 N-1 条已落库，用户重试又导一遍，
            // 卡密表里出现重复条目并影响库存统计。
            log.error("批量导入卡密失败", e);
            throw new BusinessException("批量导入卡密失败: " + e.getMessage(), e);
        }
    }

    @Override
    public ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigId(Long kamiConfigId) {
        try {
            assertConfigVisible(kamiConfigId);
            List<XianyuKamiItem> items = kamiItemMapper.findByConfigId(kamiConfigId);
            List<KamiItemRespDTO> result = items.stream()
                    .map(this::toItemRespDTO)
                    .collect(Collectors.toList());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("查询卡密列表失败", e);
            return ResultObject.failed("查询卡密列表失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigIdWithFilter(KamiItemQueryReqDTO reqDTO) {
        try {
            assertConfigVisible(reqDTO.getKamiConfigId());
            List<XianyuKamiItem> items = kamiItemMapper.findByConfigIdWithFilter(
                    reqDTO.getKamiConfigId(), 
                    reqDTO.getStatus(), 
                    Boolean.TRUE.equals(reqDTO.getPendingOnly()),
                    reqDTO.getKeyword());
            List<KamiItemRespDTO> result = items.stream()
                    .map(this::toItemRespDTO)
                    .collect(Collectors.toList());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("查询卡密列表失败", e);
            return ResultObject.failed("查询卡密列表失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<Void> deleteKamiItem(Long id) {
        try {
            XianyuKamiItem item = kamiItemMapper.selectById(id);
            if (item != null) {
                assertConfigVisible(item.getKamiConfigId());
            }
            if (item == null) {
                return ResultObject.failed("卡密不存在");
            }
            kamiItemMapper.deleteById(id);
            refreshConfigCounts(item.getKamiConfigId());
            return ResultObject.success(null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("删除卡密失败", e);
            throw new BusinessException("删除卡密失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public ResultObject<Void> resetKamiItem(Long id) {
        try {
            XianyuKamiItem item = kamiItemMapper.selectById(id);
            if (item != null) {
                assertConfigVisible(item.getKamiConfigId());
            }
            if (item != null && item.getDeliveredTime() != null) {
                // 已确认交付的卡密被重置回未使用，等于同一张卡可以再卖一次。
                // 保留该能力供人工纠错，但必须留下可追溯的记录。
                log.warn("[卡密重置] 已交付卡密被重置为未使用，请确认是否属于人工纠错: itemId={}, orderId={}, operator={}",
                        id, item.getOrderId(), com.feijimiao.xianyuassistant.context.UserContext.getUserId());
            }
            int rows = kamiItemMapper.markUnused(id);
            if (rows == 0) {
                return ResultObject.failed("卡密状态重置失败，可能已是未使用状态");
            }
            return ResultObject.success(null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("重置卡密状态失败", e);
            throw new BusinessException("重置卡密状态失败: " + e.getMessage(), e);
        }
    }

    @Override
    public XianyuKamiItem acquireKami(Long kamiConfigId, String orderId) {
        ReentrantLock configLock = configLocks.computeIfAbsent(kamiConfigId, k -> new ReentrantLock());
        configLock.lock();
        try {
            XianyuKamiConfig config = kamiConfigMapper.selectById(kamiConfigId);
            if (config == null) {
                log.warn("卡密配置不存在: kamiConfigId={}", kamiConfigId);
                return null;
            }
            if (orderId != null && !orderId.isBlank()) {
                XianyuKamiItem reservedItem = kamiItemMapper.findByReservationKey(kamiConfigId, orderId);
                if (reservedItem != null) {
                    log.info("复用同一发货动作已预占的卡密: configId={}, itemId={}", kamiConfigId, reservedItem.getId());
                    return reservedItem;
                }
            }
            XianyuKamiItem item = kamiItemMapper.findNextUnused(kamiConfigId);

            if (item == null) {
                log.warn("卡密配置[{}]无可用卡密，不触发发货流程", kamiConfigId);
                runAfterCommit(() -> sendStockOutEmailIfNeeded(config, kamiConfigId, orderId));
                return null;
            }

            int marked = kamiItemMapper.markUsed(item.getId(), orderId);
            if (marked == 0) {
                log.warn("卡密[{}]已被其他订单占用，并发冲突，尝试重新获取", item.getId());
                item = kamiItemMapper.findNextUnused(kamiConfigId);
                if (item == null) {
                    log.warn("卡密配置[{}]并发冲突后无可用卡密", kamiConfigId);
                    runAfterCommit(() -> sendStockOutEmailIfNeeded(config, kamiConfigId, orderId));
                    return null;
                }
                marked = kamiItemMapper.markUsed(item.getId(), orderId);
                if (marked == 0) {
                    log.warn("卡密[{}]二次并发冲突，放弃本次获取", item.getId());
                    return null;
                }
            }

            refreshConfigCounts(kamiConfigId);
            // 邮件/告警放到事务提交后：SMTP 超时期间会一直持有 SQLite 写锁，
            // busy_timeout 一过其他写入全部 SQLITE_BUSY，并发发货会连环失败。
            runAfterCommit(() -> checkAndSendAlert(config, kamiConfigId));
            return item;
        } finally {
            configLock.unlock();
        }
    }

    @Override
    public XianyuKamiItem findReservedKami(Long kamiConfigId, String reservationKey) {
        if (reservationKey == null || reservationKey.isBlank()) {
            return null;
        }
        return kamiItemMapper.findByReservationKey(kamiConfigId, reservationKey);
    }

    @Override
    @Transactional
    public XianyuKamiItem findOrMigrateLegacyReservation(List<Long> configIds, String legacyOrderId,
                                                          String reservationKey, int unitIndex) {
        if (configIds == null || configIds.isEmpty() || legacyOrderId == null || legacyOrderId.isBlank()
                || reservationKey == null || reservationKey.isBlank() || unitIndex < 0) {
            return null;
        }

        acquireLock.lock();
        try {
            String actionPrefix = legacyOrderId + "#delivery-";
            List<XianyuKamiItem> reservations = kamiItemMapper.findOrderReservations(
                    configIds, legacyOrderId, actionPrefix);
            if (reservations == null || unitIndex >= reservations.size()) {
                return null;
            }

            XianyuKamiItem selected = reservations.get(unitIndex);
            if (reservationKey.equals(selected.getOrderId())) {
                return selected;
            }
            if (!legacyOrderId.equals(selected.getOrderId())) {
                throw new IllegalStateException("卡密历史预占顺序冲突，拒绝跨件复用: itemId=" + selected.getId());
            }

            int migrated = kamiItemMapper.migrateLegacyReservation(
                    selected.getId(), legacyOrderId, reservationKey);
            if (migrated == 1) {
                selected.setOrderId(reservationKey);
                log.info("迁移旧版卡密预占动作: itemId={}, legacyOrderId={}, actionId={}",
                        selected.getId(), legacyOrderId, reservationKey);
                return selected;
            }

            for (Long configId : configIds) {
                XianyuKamiItem concurrentlyMigrated = kamiItemMapper.findByReservationKey(configId, reservationKey);
                if (concurrentlyMigrated != null) {
                    return concurrentlyMigrated;
                }
            }
            throw new IllegalStateException("卡密旧版预占迁移发生并发冲突: itemId=" + selected.getId());
        } finally {
            acquireLock.unlock();
        }
    }

    @Override
    @Transactional
    public void releaseKamiReservation(Long kamiItemId, String reservationKey, String usageOrderId) {
        XianyuKamiItem item = kamiItemMapper.selectById(kamiItemId);
        if (item == null || item.getStatus() == null || item.getStatus() != 1
                || !reservationKey.equals(item.getOrderId())) {
            throw new IllegalStateException("卡密预占状态已变化，拒绝释放: itemId=" + kamiItemId);
        }

        int deleted = kamiUsageRecordMapper.deleteByItemIdAndOrderId(kamiItemId, usageOrderId);
        if (deleted != 1) {
            throw new IllegalStateException("卡密使用记录释放失败: itemId=" + kamiItemId);
        }

        int released = kamiItemMapper.markUnusedIfReserved(kamiItemId, reservationKey);
        if (released != 1) {
            throw new IllegalStateException("卡密预占释放失败: itemId=" + kamiItemId);
        }
        refreshConfigCounts(item.getKamiConfigId());
    }

    @Override
    @Transactional
    public ResultObject<Void> confirmKamiItemDelivery(Long id) {
        try {
            XianyuKamiItem item = kamiItemMapper.selectById(id);
            if (item != null) {
                assertConfigVisible(item.getKamiConfigId());
            }
            if (item == null) {
                return ResultObject.failed("卡密不存在");
            }
            if (item.getStatus() == null || item.getStatus() != 1) {
                return ResultObject.failed("卡密当前不处于占用状态");
            }
            if (item.getDeliveredTime() != null) {
                return ResultObject.failed("该卡密已确认交付，无需重复确认");
            }
            confirmKamiDelivered(id, item.getOrderId());
            return ResultObject.success(null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("确认卡密交付失败: id={}", id, e);
            throw new BusinessException("确认卡密交付失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public ResultObject<Void> releaseKamiItemReservation(Long id) {
        try {
            XianyuKamiItem item = kamiItemMapper.selectById(id);
            if (item != null) {
                assertConfigVisible(item.getKamiConfigId());
            }
            if (item == null) {
                return ResultObject.failed("卡密不存在");
            }
            if (item.getStatus() == null || item.getStatus() != 1) {
                return ResultObject.failed("卡密当前不处于占用状态");
            }
            if (item.getDeliveredTime() != null) {
                return ResultObject.failed("该卡密已确认交付，释放会把它再次卖出，请改用重置并核实买家");
            }
            boolean released = releaseStaleKamiReservation(id, item.getOrderId(), "人工确认未交付");
            return released ? ResultObject.success(null) : ResultObject.failed("释放失败：预占状态已变化");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("释放卡密预占失败: id={}", id, e);
            throw new BusinessException("释放卡密预占失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public void confirmKamiDelivered(Long kamiItemId, String reservationKey) {
        if (kamiItemId == null || reservationKey == null || reservationKey.isBlank()) {
            return;
        }
        int confirmed = kamiItemMapper.markDelivered(kamiItemId, reservationKey);
        if (confirmed == 1) {
            log.info("卡密交付确认: itemId={}, actionId={}", kamiItemId, reservationKey);
        } else {
            // 已确认过或预占已被释放，属于正常竞态，不抛异常影响已成功的发货。
            log.debug("卡密交付确认跳过（状态已变化）: itemId={}, actionId={}", kamiItemId, reservationKey);
        }
    }

    @Override
    @Transactional
    public boolean releaseStaleKamiReservation(Long kamiItemId, String reservationKey, String reason) {
        if (kamiItemId == null || reservationKey == null || reservationKey.isBlank()) {
            return false;
        }
        XianyuKamiItem item = kamiItemMapper.selectById(kamiItemId);
        if (item == null || item.getStatus() == null || item.getStatus() != 1
                || item.getDeliveredTime() != null
                || !reservationKey.equals(item.getOrderId())) {
            log.info("滞留预占释放跳过（状态已变化）: itemId={}, actionId={}", kamiItemId, reservationKey);
            return false;
        }

        int deleted = kamiUsageRecordMapper.deleteByItemId(kamiItemId);
        int released = kamiItemMapper.markUnusedIfReserved(kamiItemId, reservationKey);
        if (released != 1) {
            throw new IllegalStateException("滞留预占释放失败: itemId=" + kamiItemId);
        }
        refreshConfigCounts(item.getKamiConfigId());
        log.warn("释放滞留卡密预占: itemId={}, configId={}, actionId={}, 清理使用记录={}, 原因={}",
                kamiItemId, item.getKamiConfigId(), reservationKey, deleted, reason);
        return true;
    }

    /**
     * 有事务时延迟到提交后执行，否则立即执行。
     *
     * <p>用于把 SMTP / Webhook 等慢外呼移出事务边界：SQLite 是单写者模型，
     * 事务内做秒级 IO 会让其他写入撞上 busy_timeout。</p>
     */
    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void sendStockOutEmailIfNeeded(XianyuKamiConfig config, Long kamiConfigId, String orderId) {
        Long lastSentTime = stockOutEmailSentTime.get(kamiConfigId);
        long now = System.currentTimeMillis();
        if (lastSentTime != null && (now - lastSentTime) < STOCK_OUT_EMAIL_INTERVAL_MS) {
            log.debug("卡密库存不足邮件10分钟内已发送过，跳过: configId={}", kamiConfigId);
            return;
        }
        stockOutEmailSentTime.put(kamiConfigId, now);
        String configName = config.getAliasName() != null ? config.getAliasName() : "卡密配置" + kamiConfigId;
        emailNotifyService.sendKamiStockOutEmail(config.getAlertEmail(), configName, orderId);
    }

    @Override
    public XianyuKamiConfig getConfig(Long kamiConfigId) {
        return kamiConfigMapper.selectById(kamiConfigId);
    }

    @Override
    public ResultObject<List<KamiItemRespDTO>> exportKamiItems(KamiExportReqDTO reqDTO) {
        try {
            assertConfigVisible(reqDTO.getKamiConfigId());
            List<XianyuKamiItem> items = new ArrayList<>();
            boolean includeUnused = reqDTO.getIncludeUnused() != null && reqDTO.getIncludeUnused();
            boolean includeUsed = reqDTO.getIncludeUsed() != null && reqDTO.getIncludeUsed();

            if (includeUnused && includeUsed) {
                items = kamiItemMapper.findByConfigId(reqDTO.getKamiConfigId());
            } else if (includeUnused) {
                items = kamiItemMapper.findByConfigIdAndStatus(reqDTO.getKamiConfigId(), 0);
            } else if (includeUsed) {
                items = kamiItemMapper.findByConfigIdAndStatus(reqDTO.getKamiConfigId(), 1);
            }

            List<KamiItemRespDTO> result = items.stream()
                    .map(this::toItemRespDTO)
                    .collect(Collectors.toList());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("导出卡密失败", e);
            return ResultObject.failed("导出卡密失败: " + e.getMessage());
        }
    }

    private void refreshConfigCounts(Long kamiConfigId) {
        int total = kamiItemMapper.countByConfigId(kamiConfigId);
        int used = kamiItemMapper.countUsed(kamiConfigId);
        XianyuKamiConfig config = kamiConfigMapper.selectById(kamiConfigId);
        if (config != null) {
            config.setTotalCount(total);
            config.setUsedCount(used);
            kamiConfigMapper.updateById(config);
        }
    }

    private KamiConfigRespDTO toConfigRespDTO(XianyuKamiConfig config) {
        KamiConfigRespDTO dto = new KamiConfigRespDTO();
        dto.setId(config.getId());
        dto.setXianyuAccountId(config.getXianyuAccountId());
        dto.setAliasName(config.getAliasName());
        dto.setAlertEnabled(config.getAlertEnabled());
        dto.setAlertThresholdType(config.getAlertThresholdType());
        dto.setAlertThresholdValue(config.getAlertThresholdValue());
        dto.setAlertEmail(config.getAlertEmail());
        dto.setTotalCount(config.getTotalCount());
        dto.setUsedCount(config.getUsedCount());
        int unused = kamiItemMapper.countUnused(config.getId());
        dto.setAvailableCount(unused);
        dto.setPendingCount(kamiItemMapper.countPendingDelivery(config.getId()));
        dto.setCreateTime(config.getCreateTime());
        dto.setUpdateTime(config.getUpdateTime());
        return dto;
    }

    private KamiItemRespDTO toItemRespDTO(XianyuKamiItem item) {
        KamiItemRespDTO dto = new KamiItemRespDTO();
        dto.setId(item.getId());
        dto.setKamiConfigId(item.getKamiConfigId());
        dto.setKamiContent(item.getKamiContent());
        dto.setStatus(item.getStatus());
        dto.setOrderId(item.getOrderId());
        dto.setUsedTime(item.getUsedTime());
        dto.setDeliveredTime(item.getDeliveredTime());
        dto.setReservationState(resolveReservationState(item));
        dto.setSortOrder(item.getSortOrder());
        dto.setCreateTime(item.getCreateTime());
        return dto;
    }

    private String resolveReservationState(XianyuKamiItem item) {
        Integer status = item.getStatus();
        if (status == null || status == 0) {
            return "UNUSED";
        }
        return item.getDeliveredTime() != null ? "DELIVERED" : "PENDING_CONFIRM";
    }

    private void checkAndSendAlert(XianyuKamiConfig config, Long kamiConfigId) {
        if (config == null || config.getAlertEnabled() == null || config.getAlertEnabled() != 1) {
            return;
        }

        int availableCount = kamiItemMapper.countUnused(kamiConfigId);
        int totalCount = config.getTotalCount() != null ? config.getTotalCount() : 0;
        int thresholdValue = config.getAlertThresholdValue() != null ? config.getAlertThresholdValue() : 10;
        int thresholdType = config.getAlertThresholdType() != null ? config.getAlertThresholdType() : 1;

        boolean shouldAlert = false;
        if (thresholdType == 1) {
            shouldAlert = availableCount < thresholdValue;
        } else {
            if (totalCount > 0) {
                int percentage = (availableCount * 100) / totalCount;
                shouldAlert = percentage < thresholdValue;
            }
        }

        if (shouldAlert) {
            log.info("卡密库存触发预警: configId={}, available={}, total={}, thresholdType={}, thresholdValue={}",
                    kamiConfigId, availableCount, totalCount, thresholdType, thresholdValue);
            // 接入告警事件管线（去重/抑制由 AlertService 持久化处理）
            try {
                alertService.checkKamiStock(
                        config.getAliasName() != null ? config.getAliasName() : String.valueOf(kamiConfigId),
                        Math.min(availableCount, thresholdValue), thresholdValue);
            } catch (Exception e) {
                log.warn("卡密库存告警持久化失败: configId={}, errorType={}", kamiConfigId, e.getClass().getSimpleName());
            }
            emailNotifyService.sendKamiAlertEmail(
                    config.getAlertEmail(),
                    config.getAliasName(),
                    availableCount,
                    totalCount
            );
            try {
                webhookService.dispatch("kami.low_stock", java.util.Map.of(
                        "accountId", config.getXianyuAccountId(),
                        "kamiConfigId", kamiConfigId,
                        "availableCount", availableCount,
                        "totalCount", totalCount));
            } catch (Exception e) {
                log.warn("Webhook 卡密库存预警推送失败: configId={}, error={}", kamiConfigId, e.getMessage());
            }
        }
    }
}
