package com.feijimiao.xianyuassistant.controller;

import com.alibaba.excel.EasyExcel;
import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.ItemListFromDbReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.ItemListFromDbRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.KamiItemRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.KamiConfigRespDTO;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.service.ItemService;
import com.feijimiao.xianyuassistant.service.KamiConfigService;
import com.feijimiao.xianyuassistant.service.OrderQueryService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Excel 导出控制器（卡片 F4）：订单 / 库存 / 卡密一键导出 .xlsx。
 */
@Slf4j
@RestController
@RequestMapping("/api/export")
@RequirePermission("order:read")
public class ExportController {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int EXPORT_LIMIT = 100000;

    @Autowired
    private OrderQueryService orderQueryService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private KamiConfigService kamiConfigService;

    @Autowired
    private RbacService rbacService;

    /** 导出订单（全部字段），支持按账号/状态/关键字过滤。 */
    @GetMapping("/orders.xlsx")
    public void exportOrders(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            HttpServletResponse response) throws IOException {
        checkAccountVisible(accountId);
        List<XianyuGoodsOrder> orders = orderQueryService.selectByConditionWithPage(
                accountId, null, status, keyword, EXPORT_LIMIT, 0);
        List<List<String>> head = head("订单ID", "账号ID", "商品ID", "闲鱼商品ID", "PNM ID",
                "买家ID", "买家昵称", "SID", "留言内容", "状态", "失败原因", "确认发货",
                "商品标题", "SKU", "创建时间", "下单时间", "付款时间", "发货时间", "总价", "数量");
        List<List<Object>> rows = new ArrayList<>();
        for (XianyuGoodsOrder order : orders) {
            rows.add(Arrays.asList(
                    order.getOrderId(), order.getXianyuAccountId(), order.getXianyuGoodsId(),
                    order.getXyGoodsId(), order.getPnmId(), order.getBuyerUserId(),
                    order.getBuyerUserName(), order.getSid(), order.getContent(),
                    order.getState(), order.getFailReason(), order.getConfirmState(),
                    order.getGoodsTitle(), order.getSkuName(), fmt(order.getCreateTime()),
                    fmt(order.getOrderCreateTime()), order.getPaySuccessTime(),
                    order.getConsignTime(), order.getTotalPrice(), order.getBuyNum()));
        }
        write(response, "orders", head, rows);
    }

    /** 导出库存商品（全部字段），支持按账号/状态过滤。 */
    @GetMapping("/goods.xlsx")
    public void exportGoods(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Integer status,
            HttpServletResponse response) throws IOException {
        checkAccountVisible(accountId);
        ItemListFromDbReqDTO req = new ItemListFromDbReqDTO();
        req.setOnlyOnSale(false);
        req.setStatus(status);
        req.setXianyuAccountId(accountId);
        req.setPageNum(1);
        req.setPageSize(EXPORT_LIMIT);
        ResultObject<ItemListFromDbRespDTO> result = itemService.getItemsFromDb(req);
        List<XianyuGoodsInfo> goods = result != null && result.getData() != null
                ? result.getData().getItems() : List.of();
        List<List<String>> head = head("ID", "闲鱼商品ID", "标题", "价格", "SKU数", "状态",
                "账号ID", "封面图", "详情URL", "创建时间", "更新时间");
        List<List<Object>> rows = new ArrayList<>();
        for (XianyuGoodsInfo item : goods) {
            rows.add(Arrays.asList(
                    item.getId(), item.getXyGoodId(), item.getTitle(), item.getSoldPrice(),
                    item.getSkuCount(), item.getStatus(), item.getXianyuAccountId(),
                    item.getCoverPic(), item.getDetailUrl(), fmt(item.getCreatedTime()),
                    fmt(item.getUpdatedTime())));
        }
        write(response, "goods", head, rows);
    }

    /** 导出卡密清单（按卡密配置）。卡密等同资产，读取权限独立于订单。 */
    @GetMapping("/kami.xlsx")
    @RequirePermission("kami:read")
    public void exportKami(@RequestParam Long configId, HttpServletResponse response) throws IOException {
        ResultObject<KamiConfigRespDTO> configResult = kamiConfigService.getConfigById(configId);
        KamiConfigRespDTO config = configResult != null ? configResult.getData() : null;
        checkAccountVisible(config != null ? config.getXianyuAccountId() : null);
        ResultObject<List<KamiItemRespDTO>> result = kamiConfigService.getKamiItemsByConfigId(configId);
        List<KamiItemRespDTO> items = result != null && result.getData() != null ? result.getData() : List.of();
        List<List<String>> head = head("ID", "卡密配置ID", "卡密内容", "状态", "订单ID", "使用时间", "排序", "创建时间");
        List<List<Object>> rows = new ArrayList<>();
        for (KamiItemRespDTO item : items) {
            rows.add(Arrays.asList(
                    item.getId(), item.getKamiConfigId(), item.getKamiContent(),
                    item.getStatus(), item.getOrderId(), fmt(item.getUsedTime()),
                    item.getSortOrder(), fmt(item.getCreateTime())));
        }
        write(response, "kami", head, rows);
    }

    private List<List<String>> head(String... titles) {
        List<List<String>> head = new ArrayList<>();
        for (String title : titles) {
            head.add(Arrays.asList(title));
        }
        return head;
    }

    /**
     * 账号范围兜底：accountId 为空表示"全部账号"，切面只拦截显式传入的
     * xianyuAccountId/accountId 参数名，这里按当前用户可见范围显式校验，
     * 防止受限用户借空参导出全量数据。管理员（可见范围为 null）不受限。
     */
    private void checkAccountVisible(Long accountId) {
        List<Long> visible = rbacService.getVisibleAccountIds(UserContext.getUserId());
        if (visible == null) {
            return;
        }
        if (accountId == null) {
            throw new BusinessException(403, "受限账号必须指定账号ID");
        }
        if (!visible.contains(accountId)) {
            throw new BusinessException(403, "无权访问该账号的数据");
        }
    }

    private void write(HttpServletResponse response, String fileName, List<List<String>> head, List<List<Object>> rows)
            throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded + ".xlsx");
        EasyExcel.write(response.getOutputStream()).head(head).sheet(fileName).doWrite(rows);
    }

    private static String fmt(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME);
    }
}