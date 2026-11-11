package com.feijimiao.xianyuassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feijimiao.xianyuassistant.entity.XianyuOperationLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 操作记录 Mapper
 */
@Mapper
public interface XianyuOperationLogMapper extends BaseMapper<XianyuOperationLog> {
    
    /**
     * 分页查询操作记录
     */
    @Select("<script>" +
            "SELECT * FROM xianyu_operation_log " +
            "WHERE xianyu_account_id = #{accountId} " +
            "<if test='operationType != null and operationType != \"\"'>" +
            "  AND operation_type = #{operationType} " +
            "</if>" +
            "<if test='operationModule != null and operationModule != \"\"'>" +
            "  AND operation_module = #{operationModule} " +
            "</if>" +
            "<if test='operationStatus != null'>" +
            "  AND operation_status = #{operationStatus} " +
            "</if>" +
            "ORDER BY create_time DESC " +
            "LIMIT #{pageSize} OFFSET #{offset}" +
            "</script>")
    List<XianyuOperationLog> selectByPage(
            @Param("accountId") Long accountId,
            @Param("operationType") String operationType,
            @Param("operationModule") String operationModule,
            @Param("operationStatus") Integer operationStatus,
            @Param("pageSize") Integer pageSize,
            @Param("offset") Integer offset
    );
    
    /**
     * 统计操作记录数量
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM xianyu_operation_log " +
            "WHERE xianyu_account_id = #{accountId} " +
            "<if test='operationType != null and operationType != \"\"'>" +
            "  AND operation_type = #{operationType} " +
            "</if>" +
            "<if test='operationModule != null and operationModule != \"\"'>" +
            "  AND operation_module = #{operationModule} " +
            "</if>" +
            "<if test='operationStatus != null'>" +
            "  AND operation_status = #{operationStatus} " +
            "</if>" +
            "</script>")
    Integer countByCondition(
            @Param("accountId") Long accountId,
            @Param("operationType") String operationType,
            @Param("operationModule") String operationModule,
            @Param("operationStatus") Integer operationStatus
    );
    
    /**
     * 根据账号ID删除操作记录
     */
    @Delete("DELETE FROM xianyu_operation_log WHERE xianyu_account_id = #{accountId}")
    int deleteByAccountId(@Param("accountId") Long accountId);

    /**
     * 操作审计统计（指定日期，按 operation_type 聚合）
     * 统计总次数、成功/失败/部分成功数与平均耗时
     */
    @Select("SELECT operation_type AS typeName, " +
            "COUNT(*) AS totalCount, " +
            "SUM(CASE WHEN operation_status = 1 THEN 1 ELSE 0 END) AS successCount, " +
            "SUM(CASE WHEN operation_status = 0 THEN 1 ELSE 0 END) AS failCount, " +
            "SUM(CASE WHEN operation_status = 2 THEN 1 ELSE 0 END) AS partialCount, " +
            "AVG(duration_ms) AS avgDurationMs " +
            "FROM xianyu_operation_log " +
            "WHERE date(create_time / 1000, 'unixepoch', 'localtime') = #{date} " +
            "AND operation_type IS NOT NULL AND operation_type != '' " +
            "GROUP BY operation_type ORDER BY totalCount DESC LIMIT #{limit}")
    List<java.util.Map<String, Object>> selectOperationStatsByDate(@Param("date") String date, @Param("limit") int limit);

    /**
     * 操作类型统计（指定日期，按账号范围过滤）。
     *
     * <p><b>方法名必须唯一</b>：MyBatis 语句 ID 不含参数签名，同名重载会让后声明的方法被静默丢弃，
     * 账号范围过滤随之失效。带范围的查询统一用 {@code Scoped} 后缀。</p>
     */
    @Select("<script>SELECT operation_type AS typeName, COUNT(*) AS totalCount, SUM(CASE WHEN operation_status = 1 THEN 1 ELSE 0 END) AS successCount, SUM(CASE WHEN operation_status = 0 THEN 1 ELSE 0 END) AS failCount, SUM(CASE WHEN operation_status = 2 THEN 1 ELSE 0 END) AS partialCount, AVG(duration_ms) AS avgDurationMs FROM xianyu_operation_log WHERE date(create_time / 1000, 'unixepoch', 'localtime') = #{date} <choose><when test='scope.accountIds == null'></when><when test='scope.accountIds.isEmpty()'>AND 1 = 0</when><otherwise>AND xianyu_account_id IN <foreach collection='scope.accountIds' item='accountId' open='(' separator=',' close=')'>#{accountId}</foreach></otherwise></choose> AND operation_type IS NOT NULL AND operation_type != '' GROUP BY operation_type ORDER BY totalCount DESC LIMIT #{limit}</script>")
    List<java.util.Map<String, Object>> selectOperationStatsByDateScoped(@Param("date") String date, @Param("limit") int limit, @Param("scope") com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope);

    /**
     * 失败原因排行（指定日期，按 error_message 聚合失败记录，按账号范围过滤）
     * 统计各失败原因出现次数与涉及的操作类型
     */
    @Select("<script>SELECT CASE WHEN error_message IS NULL OR error_message = '' THEN '(未知原因)' ELSE error_message END AS reason, COUNT(*) AS failCount, GROUP_CONCAT(DISTINCT operation_type) AS relatedTypes FROM xianyu_operation_log WHERE date(create_time / 1000, 'unixepoch', 'localtime') = #{date} AND operation_status = 0 <choose><when test='scope.accountIds == null'></when><when test='scope.accountIds.isEmpty()'>AND 1 = 0</when><otherwise>AND xianyu_account_id IN <foreach collection='scope.accountIds' item='accountId' open='(' separator=',' close=')'>#{accountId}</foreach></otherwise></choose> GROUP BY reason ORDER BY failCount DESC LIMIT #{limit}</script>")
    List<java.util.Map<String, Object>> selectFailureRankByDateScoped(@Param("date") String date, @Param("limit") int limit, @Param("scope") com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope);
}
