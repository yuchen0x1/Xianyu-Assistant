package com.feijimiao.xianyuassistant.controller;
import com.feijimiao.xianyuassistant.service.AutoReplyRecordService;
import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.annotation.RateLimit;
import jakarta.validation.Valid;
import com.feijimiao.xianyuassistant.controller.dto.*;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoReplyRecord;
import com.feijimiao.xianyuassistant.service.ItemService;
import com.feijimiao.xianyuassistant.service.ItemDetailSyncService;
import com.feijimiao.xianyuassistant.service.AutoDeliveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/items")
@RequirePermission("goods:read")
public class ItemController {

    @Autowired
    private com.feijimiao.xianyuassistant.rbac.service.RbacService rbacService;

    @Autowired
    private ItemService itemService;
    @Autowired
    private AutoReplyRecordService autoReplyRecordService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.AutoReplyDecisionService autoReplyDecisionService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.AutoReplyService autoReplyService;
    
    @Autowired
    private ItemDetailSyncService itemDetailSyncService;

    @Autowired
    private AutoDeliveryService autoDeliveryService;
    
    
    /**
     * 刷新商品数据
     * 从闲鱼API获取最新商品信息并更新到数据库
     *
     * @param reqDTO 请求参数
     * @return 更新成功的商品ID列表
     */
    @PostMapping("/refresh")
    @RequirePermission("goods:write")
    public ResultObject<RefreshItemsRespDTO> refreshItems(@RequestBody AllItemsReqDTO reqDTO) {
        try {
            log.info("刷新商品数据请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            return itemService.refreshItems(reqDTO);
        } catch (Exception e) {
            log.error("刷新商品数据失败", e);
            return ResultObject.failed("刷新商品数据失败: " + e.getMessage());
        }
    }

    /**
     * 从数据库获取商品列表
     *
     * @param reqDTO 请求参数
     * @return 商品列表
     */
    @PostMapping("/list")
    public ResultObject<ItemListFromDbRespDTO> getItemsFromDb(@RequestBody ItemListFromDbReqDTO reqDTO) {
        try {
            log.info("从数据库获取商品列表: onlyOnSale={}, pageNum={}, pageSize={}", 
                    reqDTO.getOnlyOnSale(), reqDTO.getPageNum(), reqDTO.getPageSize());
            return itemService.getItemsFromDb(reqDTO);
        } catch (Exception e) {
            log.error("获取数据库商品失败", e);
            return ResultObject.failed("获取数据库商品失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取商品详情
     *
     * @param reqDTO 请求参数
     * @return 商品详情
     */
    @PostMapping("/detail")
    public ResultObject<ItemDetailRespDTO> getItemDetail(@Valid @RequestBody ItemDetailReqDTO reqDTO) {
        try {
            log.info("获取商品详情: xyGoodId={}", reqDTO.getXyGoodId());
            return itemService.getItemDetail(reqDTO);
        } catch (Exception e) {
            log.error("获取商品详情失败", e);
            return ResultObject.failed("获取商品详情失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新商品自动发货状态
     *
     * @param reqDTO 请求参数
     * @return 更新结果
     */
    @PostMapping("/updateAutoDeliveryStatus")
    @RequirePermission("goods:write")
    public ResultObject<UpdateAutoDeliveryRespDTO> updateAutoDeliveryStatus(@Valid @RequestBody UpdateAutoDeliveryReqDTO reqDTO) {
        try {
            log.info("更新商品自动发货状态请求: xianyuAccountId={}, xyGoodsId={}, status={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getXianyuAutoDeliveryOn());
            return itemService.updateAutoDeliveryStatus(reqDTO);
        } catch (Exception e) {
            log.error("更新商品自动发货状态失败", e);
            return ResultObject.failed("更新商品自动发货状态失败: " + e.getMessage());
        }
    }

    @PostMapping("/updateAutoConfirmShipment")
    @RequirePermission("goods:write")
    public ResultObject<String> updateAutoConfirmShipment(@RequestBody java.util.Map<String, Object> params) {
        try {
            Long accountId = Long.parseLong(params.get("xianyuAccountId").toString());
            String xyGoodsId = params.get("xyGoodsId").toString();
            Integer autoConfirmShipment = Integer.parseInt(params.get("autoConfirmShipment").toString());
            rbacService.assertAccountVisible(com.feijimiao.xianyuassistant.context.UserContext.getUserId(), accountId);
            log.info("更新自动确认发货状态: xianyuAccountId={}, xyGoodsId={}, autoConfirmShipment={}", accountId, xyGoodsId, autoConfirmShipment);
            autoDeliveryService.updateAutoConfirmShipment(accountId, xyGoodsId, autoConfirmShipment);
            return ResultObject.success("更新成功");
        } catch (BusinessException e) {
            return ResultObject.failed(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("更新自动确认发货状态失败", e);
            return ResultObject.failed("更新失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新商品自动回复状态
     *
     * @param reqDTO 请求参数
     * @return 更新结果
     */
    @PostMapping("/updateAutoReplyStatus")
    @RequirePermission("goods:write")
    public ResultObject<UpdateAutoReplyRespDTO> updateAutoReplyStatus(@RequestBody UpdateAutoReplyReqDTO reqDTO) {
        try {
            log.info("更新商品自动回复状态请求: xianyuAccountId={}, xyGoodsId={}, status={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getXianyuAutoReplyOn());
            return itemService.updateAutoReplyStatus(reqDTO);
        } catch (Exception e) {
            log.error("更新商品自动回复状态失败", e);
            return ResultObject.failed("更新商品自动回复状态失败: " + e.getMessage());
        }
    }

    /**
     * 批量更新商品自动化配置（自动发货/自动回复开关）
     *
     * @param reqDTO 请求参数
     * @return 批量更新结果
     */
    @PostMapping("/batchUpdateGoodsConfig")
    @RequirePermission("goods:write")
    public ResultObject<BatchUpdateGoodsConfigRespDTO> batchUpdateGoodsConfig(@Valid @RequestBody BatchUpdateGoodsConfigReqDTO reqDTO) {
        try {
            log.info("批量更新商品自动化配置请求: xianyuAccountId={}, 商品数={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsIds() != null ? reqDTO.getXyGoodsIds().size() : 0);
            return itemService.batchUpdateGoodsConfig(reqDTO);
        } catch (Exception e) {
            log.error("批量更新商品自动化配置失败", e);
            return ResultObject.failed("批量更新商品自动化配置失败: " + e.getMessage());
        }
    }
    
    /**
     * 删除商品
     *
     * @param reqDTO 请求参数
     * @return 删除结果
     */
    @PostMapping("/delete")
    @RequirePermission("goods:write")
    public ResultObject<DeleteItemRespDTO> deleteItem(@Valid @RequestBody DeleteItemReqDTO reqDTO) {
        try {
            log.info("删除商品请求: xianyuAccountId={}, xyGoodsId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
            return itemService.deleteItem(reqDTO);
        } catch (Exception e) {
            log.error("删除商品失败", e);
            return ResultObject.failed("删除商品失败: " + e.getMessage());
        }
    }

    /**
     * 更新商品本地资料（标题、描述、价格和图片）。
     * 远程闲鱼编辑需要官方页面确认，本接口不会伪造远程成功状态。
     */
    @PostMapping("/updateInfo")
    @RequirePermission("goods:write")
    public ResultObject<com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo> updateItemInfo(
            @RequestBody UpdateItemInfoReqDTO reqDTO) {
        try {
            return itemService.updateItemInfo(reqDTO);
        } catch (Exception e) {
            log.error("更新商品资料失败", e);
            return ResultObject.failed("更新商品资料失败: " + e.getMessage());
        }
    }

    /**
     * 批量更新本地商品售价；不调用闲鱼远程改价接口。
     */
    @PostMapping("/batchUpdatePrice")
    @RequirePermission("goods:write")
    @RateLimit(bucket = "batch_update_price", limit = 3, message = "批量改价过于频繁")
    public ResultObject<BatchUpdatePriceRespDTO> batchUpdatePrice(
            @Valid @RequestBody BatchUpdatePriceReqDTO reqDTO) {
        try {
            rbacService.assertAccountVisible(
                    com.feijimiao.xianyuassistant.context.UserContext.getUserId(),
                    reqDTO.getXianyuAccountId());
            return itemService.batchUpdatePrice(reqDTO);
        } catch (BusinessException e) {
            return ResultObject.failed(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("批量更新商品售价失败", e);
            return ResultObject.failed("批量更新商品售价失败: " + e.getMessage());
        }
    }

    /**
     * 创建本地商品资料草稿。
     */
    @PostMapping("/draft")
    @RequirePermission("goods:write")
    public ResultObject<com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo> createItemDraft(
            @RequestBody CreateItemDraftReqDTO reqDTO) {
        try {
            return itemService.createItemDraft(reqDTO);
        } catch (Exception e) {
            log.error("创建商品草稿失败", e);
            return ResultObject.failed("创建商品草稿失败: " + e.getMessage());
        }
    }

    @PostMapping("/syncSingle")
    @RequirePermission("goods:write")
    public ResultObject<String> syncSingleItem(@RequestBody java.util.Map<String, Object> params) {
        try {
            Long accountId = Long.parseLong(params.get("xianyuAccountId").toString());
            String xyGoodsId = params.get("xyGoodsId").toString();
            rbacService.assertAccountVisible(com.feijimiao.xianyuassistant.context.UserContext.getUserId(), accountId);
            log.info("同步单个商品: xianyuAccountId={}, xyGoodsId={}", accountId, xyGoodsId);
            boolean success = itemDetailSyncService.syncSingleItem(accountId, xyGoodsId);
            if (success) {
                return ResultObject.success("同步成功");
            } else {
                return ResultObject.failed("同步失败");
            }
        } catch (BusinessException e) {
            return ResultObject.failed(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("同步单个商品失败", e);
            return ResultObject.failed("同步失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取自动发货记录
     *
     * @param reqDTO 请求参数
     * @return 自动发货记录列表
     */
    @PostMapping("/autoDeliveryRecords")
    public ResultObject<AutoDeliveryRecordRespDTO> getAutoDeliveryRecords(@RequestBody AutoDeliveryRecordReqDTO reqDTO) {
        try {
            log.info("获取自动发货记录: xianyuAccountId={}, xyGoodsId={}, pageNum={}, pageSize={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getPageNum(), reqDTO.getPageSize());
            AutoDeliveryRecordRespDTO respDTO = autoDeliveryService.getAutoDeliveryRecords(reqDTO);
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取自动发货记录失败", e);
            return ResultObject.failed("获取自动发货记录失败: " + e.getMessage());
        }
    }

    /**
     * 导出自动发货记录（CSV）
     * 按当前筛选条件导出全部记录（不分页，最多10000条）
     */
    @PostMapping("/autoDeliveryRecords/export")
    public void exportAutoDeliveryRecords(@RequestBody AutoDeliveryRecordReqDTO reqDTO,
                                          jakarta.servlet.http.HttpServletResponse response) {
        try {
            // 导出全部记录：将页大小设置为足够大
            reqDTO.setPageNum(1);
            reqDTO.setPageSize(10000);
            AutoDeliveryRecordRespDTO respDTO = autoDeliveryService.getAutoDeliveryRecords(reqDTO);
            List<AutoDeliveryRecordDTO> records = respDTO != null ? respDTO.getRecords() : null;
            if (records == null) records = java.util.Collections.emptyList();

            response.setContentType("text/csv;charset=UTF-8");
            String filename = "delivery_records_" + java.time.LocalDate.now() + ".csv";
            String encodedName = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encodedName);

            var writer = response.getWriter();
            writer.write('\uFEFF'); // BOM，避免 Excel 中文乱码
            writer.write("ID,商品标题,买家,SKU,订单ID,发货内容,发货状态,确认发货,下单时间,支付时间,发货时间,金额,数量,记录时间\n");

            String dateFmt = "yyyy-MM-dd HH:mm:ss";
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(dateFmt);

            for (AutoDeliveryRecordDTO r : records) {
                writer.write(csv(r.getId() != null ? String.valueOf(r.getId()) : ""));
                writer.write(",");
                writer.write(csv(r.getGoodsTitle()));
                writer.write(",");
                writer.write(csv(r.getBuyerUserName()));
                writer.write(",");
                writer.write(csv(r.getSkuName()));
                writer.write(",");
                writer.write(csv(r.getOrderId()));
                writer.write(",");
                writer.write(csv(r.getContent()));
                writer.write(",");
                writer.write(csv(stateText(r.getState())));
                writer.write(",");
                writer.write(csv(r.getConfirmState() != null && r.getConfirmState() == 1 ? "已确认" : "未确认"));
                writer.write(",");
                writer.write(csv(r.getOrderCreateTime()));
                writer.write(",");
                writer.write(csv(r.getPaySuccessTime()));
                writer.write(",");
                writer.write(csv(r.getConsignTime()));
                writer.write(",");
                writer.write(csv(r.getTotalPrice()));
                writer.write(",");
                writer.write(csv(r.getBuyNum() != null ? String.valueOf(r.getBuyNum()) : ""));
                writer.write(",");
                writer.write(csv(r.getCreateTime()));
                writer.write("\n");
            }
            writer.flush();
            log.info("导出自动发货记录成功: 共 {} 条", records.size());
        } catch (Exception e) {
            log.error("导出自动发货记录失败", e);
        }
    }

    private static String csv(String value) {
        if (value == null) return "";
        // 含逗号、引号、换行的字段需要加引号转义
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String stateText(Integer state) {
        if (state == null) return "";
        return switch (state) {
            case 1 -> "成功";
            case -1 -> "失败";
            case 0 -> "待人工核对";
            default -> String.valueOf(state);
        };
    }
    
    /**
     * 获取自动回复配置
     *
     * @param reqDTO 请求参数
     * @return 自动回复配置
     */
    @PostMapping("/getRagAutoReplyConfig")
    public ResultObject<RagAutoReplyConfigRespDTO> getRagAutoReplyConfig(@RequestBody RagAutoReplyConfigReqDTO reqDTO) {
        try {
            log.info("获取自动回复配置: xianyuAccountId={}, xyGoodsId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
            return itemService.getRagAutoReplyConfig(reqDTO);
        } catch (Exception e) {
            log.error("获取自动回复配置失败", e);
            return ResultObject.failed("获取自动回复配置失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新自动回复配置
     *
     * @param reqDTO 请求参数
     * @return 更新结果
     */
    @PostMapping("/updateRagAutoReplyConfig")
    @RequirePermission("goods:write")
    public ResultObject<?> updateRagAutoReplyConfig(@RequestBody UpdateRagAutoReplyConfigReqDTO reqDTO) {
        try {
            log.info("更新自动回复配置: xianyuAccountId={}, xyGoodsId={}, ragDelaySeconds={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getRagDelaySeconds());
            return itemService.updateRagAutoReplyConfig(reqDTO);
        } catch (Exception e) {
            log.error("更新自动回复配置失败", e);
            return ResultObject.failed("更新自动回复配置失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取自动回复记录
     */
    @PostMapping("/autoReplyRecords")
    public ResultObject<Map<String, Object>> getAutoReplyRecords(@RequestBody Map<String, Object> params) {
        try {
            Long accountId = Long.valueOf(params.get("xianyuAccountId").toString());
            String xyGoodsId = params.get("xyGoodsId").toString();
            int pageNum = params.containsKey("pageNum") ? Integer.parseInt(params.get("pageNum").toString()) : 1;
            int pageSize = params.containsKey("pageSize") ? Integer.parseInt(params.get("pageSize").toString()) : 20;
            
            int offset = (pageNum - 1) * pageSize;
            List<XianyuGoodsAutoReplyRecord> records = autoReplyRecordService.selectByAccountIdAndGoodsId(accountId, xyGoodsId, pageSize, offset);
            int totalCount = autoReplyRecordService.countByAccountIdAndGoodsId(accountId, xyGoodsId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("list", records);
            result.put("totalCount", totalCount);
            result.put("pageNum", pageNum);
            result.put("pageSize", pageSize);
            
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("获取自动回复记录失败", e);
            return ResultObject.failed("获取自动回复记录失败: " + e.getMessage());
        }
    }


    /**
     * 获取自动回复决策日志（记录"为什么这条消息没被自动回复"：人工接管/商品未同步/无策略/未生成内容）
     */
    @PostMapping("/autoReplyDecisions")
    public ResultObject<Map<String, Object>> getAutoReplyDecisions(@RequestBody Map<String, Object> params) {
        try {
            Long accountId = params.get("xianyuAccountId") != null
                    && !params.get("xianyuAccountId").toString().isBlank()
                    ? Long.valueOf(params.get("xianyuAccountId").toString()) : null;
            String reasonCode = params.get("reasonCode") != null && !params.get("reasonCode").toString().isBlank()
                    ? params.get("reasonCode").toString() : null;
            int pageNum = params.containsKey("pageNum") ? Integer.parseInt(params.get("pageNum").toString()) : 1;
            int pageSize = params.containsKey("pageSize") ? Integer.parseInt(params.get("pageSize").toString()) : 20;

            // accountId 为空意味着跨账号全量查询，受限用户必须且只能查可见账号
            rbacService.assertAccountVisible(com.feijimiao.xianyuassistant.context.UserContext.getUserId(), accountId);
            return ResultObject.success(autoReplyDecisionService.queryDecisions(accountId, reasonCode, pageNum, pageSize));
        } catch (NumberFormatException e) {
            return ResultObject.failed(400, "参数格式错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("获取自动回复决策日志失败", e);
            return ResultObject.failed("获取自动回复决策日志失败: " + e.getMessage());
        }
    }

    /**
     * 获取待人工确认的AI回复草稿列表（state=2）
     */
    @PostMapping("/autoReplyPendingConfirm")
    public ResultObject<Map<String, Object>> getAutoReplyPendingConfirm(@RequestBody Map<String, Object> params) {
        try {
            Long accountId = params.get("xianyuAccountId") != null
                    ? Long.valueOf(params.get("xianyuAccountId").toString()) : null;
            if (accountId == null) {
                return ResultObject.failed("xianyuAccountId不能为空");
            }
            int pageNum = params.containsKey("pageNum") ? Integer.parseInt(params.get("pageNum").toString()) : 1;
            int pageSize = params.containsKey("pageSize") ? Integer.parseInt(params.get("pageSize").toString()) : 20;

            int offset = (pageNum - 1) * pageSize;
            rbacService.assertAccountVisible(com.feijimiao.xianyuassistant.context.UserContext.getUserId(), accountId);
            List<XianyuGoodsAutoReplyRecord> records = autoReplyRecordService.selectPendingConfirm(accountId, pageSize, offset);
            int totalCount = autoReplyRecordService.countPendingConfirm(accountId);

            Map<String, Object> result = new HashMap<>();
            result.put("list", records);
            result.put("totalCount", totalCount);
            result.put("pageNum", pageNum);
            result.put("pageSize", pageSize);
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("获取待人工确认回复草稿失败", e);
            return ResultObject.failed("获取待人工确认回复草稿失败: " + e.getMessage());
        }
    }

    /**
     * 确认并发送人工确认的AI回复草稿（内容可编辑）
     */
    @PostMapping("/autoReplyConfirm")
    @RequirePermission("goods:write")
    public ResultObject<String> confirmAutoReply(@RequestBody Map<String, Object> params) {
        try {
            if (params.get("recordId") == null) {
                return ResultObject.failed("recordId不能为空");
            }
            Long recordId = Long.valueOf(params.get("recordId").toString());
            String content = params.get("replyContent") != null ? params.get("replyContent").toString() : null;
            return autoReplyService.confirmAndSendReply(recordId, content);
        } catch (Exception e) {
            log.error("确认发送回复草稿失败", e);
            return ResultObject.failed("确认发送回复草稿失败: " + e.getMessage());
        }
    }

    /**
     * 忽略待人工确认的AI回复草稿
     */
    @PostMapping("/autoReplyDismiss")
    @RequirePermission("goods:write")
    public ResultObject<String> dismissAutoReply(@RequestBody Map<String, Object> params) {
        try {
            if (params.get("recordId") == null) {
                return ResultObject.failed("recordId不能为空");
            }
            Long recordId = Long.valueOf(params.get("recordId").toString());
            autoReplyService.dismissPendingReply(recordId);
            return ResultObject.success("已忽略该草稿");
        } catch (Exception e) {
            log.error("忽略回复草稿失败", e);
            return ResultObject.failed("忽略回复草稿失败: " + e.getMessage());
        }
    }

    @GetMapping("/syncProgress/{syncId}")
    public ResultObject<SyncProgressRespDTO> getSyncProgress(@PathVariable String syncId) {
        try {
            SyncProgressRespDTO progress = itemDetailSyncService.getProgress(syncId);
            rbacService.assertAccountVisible(
                    com.feijimiao.xianyuassistant.context.UserContext.getUserId(),
                    progress == null ? null : progress.getAccountId());
            if (progress == null) {
                return ResultObject.failed("同步任务不存在");
            }
            return ResultObject.success(progress);
        } catch (BusinessException e) {
            return ResultObject.failed(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("获取同步进度失败", e);
            return ResultObject.failed("获取同步进度失败");
        }
    }

    @GetMapping("/syncing/{accountId}")
    public ResultObject<Boolean> isSyncing(@PathVariable Long accountId) {
        try {
            rbacService.assertAccountVisible(com.feijimiao.xianyuassistant.context.UserContext.getUserId(), accountId);
            return ResultObject.success(itemDetailSyncService.isSyncing(accountId));
        } catch (BusinessException e) {
            return ResultObject.failed(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("检查同步状态失败", e);
            return ResultObject.failed("检查同步状态失败");
        }
    }

    /**
     * 擦亮单个商品
     */
    @PostMapping("/polish")
    @RequirePermission("goods:write")
    public ResultObject<PolishItemRespDTO> polishItem(@Valid @RequestBody PolishItemReqDTO reqDTO) {
        try {
            rbacService.assertAccountVisible(com.feijimiao.xianyuassistant.context.UserContext.getUserId(), reqDTO.getXianyuAccountId());
            log.info("擦亮商品请求: accountId={}, itemId={}", reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
            return itemService.polishItem(reqDTO);
        } catch (BusinessException e) {
            return ResultObject.failed(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("擦亮商品失败", e);
            return ResultObject.failed("擦亮商品失败: " + e.getMessage());
        }
    }

    /**
     * 批量擦亮商品（若未指定商品列表则擦亮全部在售商品）
     */
    @PostMapping("/batchPolish")
    @RequirePermission("goods:write")
    @RateLimit(bucket = "batch_polish", limit = 3, message = "批量擦亮过于频繁")
    public ResultObject<PolishItemRespDTO> batchPolishItems(@Valid @RequestBody BatchPolishItemsReqDTO reqDTO) {
        try {
            rbacService.assertAccountVisible(com.feijimiao.xianyuassistant.context.UserContext.getUserId(), reqDTO.getXianyuAccountId());
            log.info("批量擦亮商品请求: accountId={}, itemIds={}", reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsIds());
            return itemService.batchPolishItems(reqDTO);
        } catch (BusinessException e) {
            return ResultObject.failed(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("批量擦亮商品失败", e);
            return ResultObject.failed("批量擦亮商品失败: " + e.getMessage());
        }
    }
}
