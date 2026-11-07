package com.feijimiao.xianyuassistant.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.annotation.RateLimit;
import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.constants.OperationConstants;
import com.feijimiao.xianyuassistant.controller.dto.BatchOffShelfItemsReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.BatchOffShelfItemsRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.OffShelfReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.PublishItemReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.PublishItemRespDTO;
import com.feijimiao.xianyuassistant.entity.XianyuPublishHistory;
import com.feijimiao.xianyuassistant.mapper.XianyuPublishHistoryMapper;
import com.feijimiao.xianyuassistant.service.OperationLogService;
import com.feijimiao.xianyuassistant.service.PublishService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品发布/下架控制器（卖家运营闭环）
 */
@Slf4j
@RestController
@RequestMapping("/api/publish")
@RequirePermission("publish:read")
public class PublishController {

    @Autowired
    private PublishService publishService;

    @Autowired
    private XianyuPublishHistoryMapper publishHistoryMapper;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private RbacService rbacService;

    /**
     * AI 类目识别（不产生写操作，不限流）
     */
    @PostMapping("/recommendCategory")
    public ResultObject<List<PublishItemRespDTO.CategoryPredict>> recommendCategory(
            @RequestBody PublishItemReqDTO req) {
        try {
            if (req.getAccountId() == null) {
                return ResultObject.validateFailed("账号ID不能为空");
            }
            if (req.getTitle() == null || req.getTitle().isBlank()) {
                return ResultObject.validateFailed("商品标题不能为空");
            }
            List<PublishItemRespDTO.CategoryPredict> categories = publishService.recommendCategory(
                    req.getAccountId(), req.getTitle(), req.getDescription(), req.getImages());
            return ResultObject.success(categories);
        } catch (Exception e) {
            log.error("AI类目识别失败: accountId={}", req.getAccountId(), e);
            return ResultObject.failed("类目识别失败，请稍后重试");
        }
    }

    /**
     * 发布商品（写操作，限流：默认 1 次/分钟/账号）
     */
    @PostMapping("/item")
    @RequirePermission("publish:write")
    @RateLimit(bucket = "item_publish", message = "发布商品过于频繁")
    public ResultObject<PublishItemRespDTO> publish(@Valid @RequestBody PublishItemReqDTO req) {
        try {
            PublishItemRespDTO resp = publishService.publish(req);
            if (resp.isSuccess()) {
                operationLogService.log(req.getAccountId(),
                        OperationConstants.Type.PUBLISH,
                        OperationConstants.Module.GOODS,
                        "商品发布成功: accountId=" + req.getAccountId() + ", itemId=" + resp.getItemId(),
                        OperationConstants.Status.SUCCESS,
                        OperationConstants.TargetType.GOODS,
                        String.valueOf(resp.getItemId()),
                        null, null, null, null);
                return ResultObject.success(resp);
            }
            operationLogService.log(req.getAccountId(),
                    OperationConstants.Type.PUBLISH,
                    OperationConstants.Module.GOODS,
                    "商品发布失败: accountId=" + req.getAccountId(),
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.GOODS,
                    null,
                    null, null, resp.getMessage(), null);
            return ResultObject.failed(resp.getMessage());
        } catch (Exception e) {
            log.error("商品发布异常: accountId={}", req.getAccountId(), e);
            operationLogService.log(req.getAccountId(),
                    OperationConstants.Type.PUBLISH,
                    OperationConstants.Module.GOODS,
                    "商品发布异常: accountId=" + req.getAccountId(),
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.GOODS,
                    null,
                    null, null, e.getMessage(), null);
            throw e;
        }
    }

    /**
     * 下架/删除商品（写操作，限流）
     */
    @PostMapping("/offShelf")
    @RequirePermission("publish:write")
    @RateLimit(bucket = "item_off_shelf", message = "下架商品过于频繁")
    public ResultObject<Boolean> offShelf(@Valid @RequestBody OffShelfReqDTO req) {
        try {
            boolean success = publishService.offShelf(req.getAccountId(), req.getItemId());
            if (success) {
                operationLogService.log(req.getAccountId(),
                        OperationConstants.Type.OFF_SHELF,
                        OperationConstants.Module.GOODS,
                        "商品下架成功: accountId=" + req.getAccountId() + ", itemId=" + req.getItemId(),
                        OperationConstants.Status.SUCCESS,
                        OperationConstants.TargetType.GOODS,
                        String.valueOf(req.getItemId()),
                        null, null, null, null);
                return ResultObject.success(true);
            }
            operationLogService.log(req.getAccountId(),
                    OperationConstants.Type.OFF_SHELF,
                    OperationConstants.Module.GOODS,
                    "商品下架失败: accountId=" + req.getAccountId(),
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.GOODS,
                    String.valueOf(req.getItemId()),
                    null, null, "下架失败，请查看发布历史确认原因", null);
            return ResultObject.failed("下架失败，请查看发布历史确认原因");
        } catch (Exception e) {
            log.error("商品下架异常: accountId={}", req.getAccountId(), e);
            operationLogService.log(req.getAccountId(),
                    OperationConstants.Type.OFF_SHELF,
                    OperationConstants.Module.GOODS,
                    "商品下架异常: accountId=" + req.getAccountId(),
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.GOODS,
                    String.valueOf(req.getItemId()),
                    null, null, e.getMessage(), null);
            throw e;
        }
    }

    /**
     * 批量下架商品（写操作，独立限流桶）
     *
     * <p>逐件调用闲鱼下架接口并返回成功/失败明细；单件失败不影响其余商品。</p>
     */
    @PostMapping("/batchOffShelf")
    @RequirePermission("publish:write")
    @RateLimit(bucket = "batch_off_shelf", limit = 3, message = "批量下架过于频繁")
    public ResultObject<BatchOffShelfItemsRespDTO> batchOffShelf(
            @Valid @RequestBody BatchOffShelfItemsReqDTO req) {
        try {
            Long accountId = req.getXianyuAccountId();
            rbacService.assertAccountVisible(UserContext.getUserId(), accountId);

            BatchOffShelfItemsRespDTO resp = publishService.batchOffShelf(accountId, req.getXyGoodsIds());

            operationLogService.log(accountId,
                    OperationConstants.Type.OFF_SHELF,
                    OperationConstants.Module.GOODS,
                    "批量下架商品: accountId=" + accountId
                            + ", total=" + resp.getTotal()
                            + ", success=" + resp.getSuccess()
                            + ", failed=" + resp.getFailed(),
                    resp.getFailed() == null || resp.getFailed() == 0
                            ? OperationConstants.Status.SUCCESS
                            : OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.GOODS,
                    null,
                    null, null,
                    resp.getFailed() != null && resp.getFailed() > 0 ? "部分商品下架失败" : null,
                    null);

            return ResultObject.success(resp);
        } catch (Exception e) {
            log.error("批量下架商品异常: accountId={}", req.getXianyuAccountId(), e);
            throw e;
        }
    }

    /**
     * 发布/下架历史记录
     */
    @GetMapping("/history")
    public ResultObject<List<XianyuPublishHistory>> history(@RequestParam(required = false) Long accountId,
                                                            @RequestParam(defaultValue = "50") int limit) {
        Long userId = UserContext.getUserId();
        if (accountId != null) {
            rbacService.assertAccountVisible(userId, accountId);
        }
        List<Long> visibleAccountIds = rbacService.getCommandVisibleAccountIds(userId);
        return ResultObject.success(publishHistoryMapper.selectByAccountScope(
                accountId, visibleAccountIds, Math.max(1, Math.min(limit, 200))));
    }
}
