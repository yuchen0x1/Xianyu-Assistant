package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.service.OperationLogService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;

/**
 * 操作记录控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/operation-log")
@RequirePermission("log:read")
public class OperationLogController {
    
    @Autowired
    private OperationLogService operationLogService;
    
    /**
     * 查询操作记录
     */
    @PostMapping("/query")
    public ResultObject<Map<String, Object>> queryLogs(@Valid @RequestBody QueryLogsReqDTO reqDTO) {
        try {
            log.info("查询操作记录: accountId={}, type={}, module={}, status={}, page={}, pageSize={}",
                    reqDTO.getAccountId(), reqDTO.getOperationType(), reqDTO.getOperationModule(),
                    reqDTO.getOperationStatus(), reqDTO.getPage(), reqDTO.getPageSize());
            
            if (reqDTO.getAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            // 设置默认值
            if (reqDTO.getPage() == null || reqDTO.getPage() < 1) {
                reqDTO.setPage(1);
            }
            if (reqDTO.getPageSize() == null || reqDTO.getPageSize() < 1) {
                reqDTO.setPageSize(20);
            }
            
            Map<String, Object> result = operationLogService.queryLogs(
                    reqDTO.getAccountId(),
                    reqDTO.getOperationType(),
                    reqDTO.getOperationModule(),
                    reqDTO.getOperationStatus(),
                    reqDTO.getPage(),
                    reqDTO.getPageSize()
            );
            
            // 添加调试日志
            log.info("查询结果: total={}, logs={}", result.get("total"), 
                    result.get("logs") != null ? ((java.util.List<?>) result.get("logs")).size() : 0);
            
            return ResultObject.success(result);
            
        } catch (Exception e) {
            log.error("查询操作记录失败", e);
            return ResultObject.failed("查询失败: " + e.getMessage());
        }
    }
    
    /**
     * 删除旧日志
     */
    @PostMapping("/deleteOld")
    @RequirePermission("log:write")
    public ResultObject<Integer> deleteOldLogs(@Valid @RequestBody DeleteOldLogsReqDTO reqDTO) {
        try {
            log.info("删除旧操作记录: days={}", reqDTO.getDays());
            
            if (reqDTO.getDays() == null || reqDTO.getDays() < 1) {
                return ResultObject.failed("天数必须大于0");
            }
            
            int deleted = operationLogService.deleteOldLogs(reqDTO.getDays());
            
            return ResultObject.success(deleted);
            
        } catch (Exception e) {
            log.error("删除旧操作记录失败", e);
            return ResultObject.failed("删除失败: " + e.getMessage());
        }
    }

    /**
     * 导出操作记录（CSV）
     * 按当前筛选条件导出全部记录（不分页，最多10000条）
     */
    @PostMapping("/export")
    public void exportLogs(@RequestBody QueryLogsReqDTO reqDTO,
                           jakarta.servlet.http.HttpServletResponse response) {
        try {
            if (reqDTO.getAccountId() == null) {
                response.setStatus(400);
                response.getWriter().write("账号ID不能为空");
                return;
            }
            reqDTO.setPage(1);
            reqDTO.setPageSize(10000);
            Map<String, Object> result = operationLogService.queryLogs(
                    reqDTO.getAccountId(),
                    reqDTO.getOperationType(),
                    reqDTO.getOperationModule(),
                    reqDTO.getOperationStatus(),
                    reqDTO.getPage(),
                    reqDTO.getPageSize()
            );
            @SuppressWarnings("unchecked")
            java.util.List<com.feijimiao.xianyuassistant.entity.XianyuOperationLog> logs =
                    (java.util.List<com.feijimiao.xianyuassistant.entity.XianyuOperationLog>) result.get("logs");
            if (logs == null) logs = java.util.Collections.emptyList();

            response.setContentType("text/csv;charset=UTF-8");
            String filename = "operation_logs_" + java.time.LocalDate.now() + ".csv";
            String encodedName = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encodedName);

            var writer = response.getWriter();
            writer.write('\uFEFF'); // BOM，避免 Excel 中文乱码
            writer.write("ID,操作类型,操作模块,操作描述,操作状态,目标类型,目标ID,错误信息,IP,耗时(ms),创建时间\n");

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            for (com.feijimiao.xianyuassistant.entity.XianyuOperationLog logEntry : logs) {
                writer.write(csv(logEntry.getId() != null ? String.valueOf(logEntry.getId()) : ""));
                writer.write(",");
                writer.write(csv(logEntry.getOperationType()));
                writer.write(",");
                writer.write(csv(logEntry.getOperationModule()));
                writer.write(",");
                writer.write(csv(logEntry.getOperationDesc()));
                writer.write(",");
                writer.write(csv(statusText(logEntry.getOperationStatus())));
                writer.write(",");
                writer.write(csv(logEntry.getTargetType()));
                writer.write(",");
                writer.write(csv(logEntry.getTargetId()));
                writer.write(",");
                writer.write(csv(logEntry.getErrorMessage()));
                writer.write(",");
                writer.write(csv(logEntry.getIpAddress()));
                writer.write(",");
                writer.write(csv(logEntry.getDurationMs() != null ? String.valueOf(logEntry.getDurationMs()) : ""));
                writer.write(",");
                writer.write(csv(logEntry.getCreateTime() != null ? sdf.format(new java.util.Date(logEntry.getCreateTime())) : ""));
                writer.write("\n");
            }
            writer.flush();
            log.info("导出操作记录成功: 共 {} 条", logs.size());
        } catch (Exception e) {
            log.error("导出操作记录失败", e);
        }
    }

    private static String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String statusText(Integer status) {
        if (status == null) return "";
        return switch (status) {
            case 1 -> "成功";
            case 2 -> "部分成功";
            case 0 -> "失败";
            default -> String.valueOf(status);
        };
    }
    
    /**
     * 查询操作记录请求DTO
     */
    @Data
    public static class QueryLogsReqDTO {
        private Long accountId;           // 账号ID（必填）
        private String operationType;     // 操作类型（可选）
        private String operationModule;   // 操作模块（可选）
        private Integer operationStatus;  // 操作状态（可选）
        private Integer page;             // 页码（默认1）
        private Integer pageSize;         // 每页数量（默认20）
    }
    
    /**
     * 删除旧日志请求DTO
     */
    @Data
    public static class DeleteOldLogsReqDTO {
        private Integer days;  // 删除多少天之前的日志
    }
}
