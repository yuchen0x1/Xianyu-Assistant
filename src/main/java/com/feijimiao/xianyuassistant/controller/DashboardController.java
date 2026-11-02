package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.service.DashboardStatsService;
import com.feijimiao.xianyuassistant.service.DataPanelAccountScope;
import com.feijimiao.xianyuassistant.controller.dto.DashboardStatsRespDTO;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 首页仪表板控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequirePermission("dashboard:read")
public class DashboardController {

    @Autowired
    private DashboardStatsService dashboardStatsService;

    @Autowired
    private RbacService rbacService;
    /**
     * 获取首页统计数据
     */
    @PostMapping("/stats")
    public ResultObject<DashboardStatsRespDTO> getDashboardStats() {
        try {
            log.info("获取首页统计数据");
            
            Long userId = UserContext.getUserId();
            java.util.List<Long> visibleAccountIds = rbacService.getCommandVisibleAccountIds(userId);
            DataPanelAccountScope scope = visibleAccountIds == null
                    ? DataPanelAccountScope.all(userId)
                    : DataPanelAccountScope.restricted(userId, visibleAccountIds);

            // 获取当前用户可见账号范围内的统计
            int accountCount = (int) dashboardStatsService.countAccounts(scope);
            int itemCount = (int) dashboardStatsService.countItems(scope);
            int sellingItemCount = (int) dashboardStatsService.countSellingItems(scope);
            int offShelfItemCount = (int) dashboardStatsService.countOffShelfItems(scope);
            int soldItemCount = (int) dashboardStatsService.countSoldItems(scope);
            
            // 构造响应数据
            DashboardStatsRespDTO respDTO = new DashboardStatsRespDTO();
            respDTO.setAccountCount(accountCount);
            respDTO.setItemCount(itemCount);
            respDTO.setSellingItemCount(sellingItemCount);
            respDTO.setOffShelfItemCount(offShelfItemCount);
            respDTO.setSoldItemCount(soldItemCount);
            
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取首页统计数据失败", e);
            return ResultObject.failed("获取首页统计数据失败");
        }
    }
}
