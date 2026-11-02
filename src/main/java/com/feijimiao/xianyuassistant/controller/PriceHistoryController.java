package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.controller.vo.PriceHistoryTrendVO;
import com.feijimiao.xianyuassistant.service.GoodsInfoService;
import com.feijimiao.xianyuassistant.service.PriceHistoryService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品价格历史控制器（F6.1 价格趋势）
 */
@Slf4j
@RestController
@RequestMapping("/api/price-history")
@RequirePermission("goods:read")
public class PriceHistoryController {

    @Autowired
    private PriceHistoryService priceHistoryService;

    @Autowired
    private GoodsInfoService goodsInfoService;

    @Autowired
    private RbacService rbacService;

    private void assertGoodsVisible(String xyGoodsId) {
        XianyuGoodsInfo goods = goodsInfoService.getByXyGoodId(xyGoodsId);
        rbacService.assertAccountVisible(UserContext.getUserId(), goods == null ? null : goods.getXianyuAccountId());
    }

    /**
     * 查询商品价格趋势
     */
    @PostMapping("/trend")
    public ResultObject<List<PriceHistoryTrendVO>> trend(@RequestBody TrendReq req) {
        try {
            if (req == null || req.getXyGoodId() == null || req.getXyGoodId().isBlank()) {
                return ResultObject.validateFailed("商品ID不能为空");
            }
            assertGoodsVisible(req.getXyGoodId());
            int days = req.getDays() != null && req.getDays() > 0 ? req.getDays() : 30;
            List<PriceHistoryTrendVO> trend = priceHistoryService.queryTrend(req.getXyGoodId(), days);
            return ResultObject.success(trend);
        } catch (BusinessException e) {
            return ResultObject.failed(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("查询价格趋势失败: xyGoodId={}", req != null ? req.getXyGoodId() : "null", e);
            return ResultObject.failed("查询价格趋势失败: " + e.getMessage());
        }
    }

    /**
     * 价格趋势查询请求参数
     */
    @Data
    public static class TrendReq {
        /** 闲鱼商品ID */
        private String xyGoodId;

        /** 最近天数（默认30） */
        private Integer days;
    }
}
