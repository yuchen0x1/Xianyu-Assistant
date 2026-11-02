package com.feijimiao.xianyuassistant.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feijimiao.xianyuassistant.monitor.entity.AlertEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 告警事件 Mapper。
 * <p>原子去重：{@link #insertIfAbsent} 使用 INSERT OR IGNORE 保证唯一键并发安全；
 * 发送窗口通过条件 UPDATE 抢占，失败不消耗抑制窗口。</p>
 */
@Mapper
public interface AlertEventMapper extends BaseMapper<AlertEvent> {

    @Insert("INSERT OR IGNORE INTO alert_event "
            + "(alert_key, title, message, level, account_id, status, first_seen_at, last_seen_at, "
            + " occurrences, send_attempts, created_time, updated_time) "
            + "VALUES (#{alertKey}, #{title}, #{message}, #{level}, #{accountId}, 'ACTIVE', "
            + " datetime('now', 'localtime'), datetime('now', 'localtime'), 1, 0, "
            + " datetime('now', 'localtime'), datetime('now', 'localtime'))")
    int insertIfAbsent(@Param("alertKey") String alertKey, @Param("title") String title,
                       @Param("message") String message, @Param("level") String level,
                       @Param("accountId") Long accountId);

    /**
     * 仅在抑制窗口已过且事件未静默时允许发送：抢占成功后返回受影响行数。
     * 发送失败不会走到此处更新 last_sent_at / next_send_at，因此不消耗窗口。
     */
    @Update("UPDATE alert_event SET occurrences = occurrences + 1, last_seen_at = datetime('now', 'localtime') "
            + "WHERE alert_key = #{alertKey} AND status IN ('ACTIVE', 'ACKED') "
            + "AND (next_send_at IS NULL OR next_send_at <= datetime('now', 'localtime'))")
    int bumpIfSendable(@Param("alertKey") String alertKey);

    /**
     * 事件复发时把已恢复（RESOLVED）的告警重新打开为 ACTIVE 并清空抑制窗口，
     * 让下一轮 {@link #bumpIfSendable} 能立即重新发送。
     *
     * <p>旧实现里 {@code insertIfAbsent} 遇到已存在的 alert_key 直接忽略，
     * 而 {@code bumpIfSendable} 又排除了 RESOLVED，运维点过"恢复"之后同一问题
     * 再次发生就永久静音了。</p>
     */
    @Update("UPDATE alert_event SET status = 'ACTIVE', next_send_at = NULL, "
            + "last_sent_at = NULL, message = #{message}, level = #{level}, "
            + "updated_time = datetime('now', 'localtime') "
            + "WHERE alert_key = #{alertKey} AND status = 'RESOLVED'")
    int reopenIfResolved(@Param("alertKey") String alertKey, @Param("message") String message,
                         @Param("level") String level);

    /** 发送成功后记录发送时间并推进抑制窗口。 */
    @Update("UPDATE alert_event SET last_sent_at = datetime('now', 'localtime'), "
            + "next_send_at = datetime('now', 'localtime', printf('+%d seconds', #{windowSeconds})) "
            + "WHERE alert_key = #{alertKey}")
    int markSent(@Param("alertKey") String alertKey, @Param("windowSeconds") int windowSeconds);

    /** 发送失败累计尝试次数（供重试和概览）。 */
    @Update("UPDATE alert_event SET send_attempts = send_attempts + 1 "
            + "WHERE alert_key = #{alertKey}")
    int bumpSendAttempts(@Param("alertKey") String alertKey);

    @Select("SELECT COUNT(*) FROM alert_event WHERE status = 'ACTIVE'")
    long countActive();

    @Select("<script>SELECT COUNT(*) FROM alert_event WHERE status = 'ACTIVE' "
            + "<if test='accountIds != null'>AND (account_id IS NULL "
            + "<if test='!accountIds.isEmpty()'>OR account_id IN "
            + "<foreach collection='accountIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</if>)</if></script>")
    long countActiveByAccountIds(@Param("accountIds") java.util.List<Long> accountIds);

    /** 供测试直接设置状态。 */
    @Update("UPDATE alert_event SET status = #{status} WHERE alert_key = #{alertKey}")
    int updateStatusTo(@Param("alertKey") String alertKey, @Param("status") String status);

    @Select("SELECT * FROM alert_event WHERE alert_key = #{alertKey} LIMIT 1")
    AlertEvent selectAlertByKey(@Param("alertKey") String alertKey);

    @Select("<script>SELECT * FROM alert_event WHERE alert_key = #{alertKey} "
            + "<if test='accountIds != null'>AND (account_id IS NULL "
            + "<choose><when test='accountIds.isEmpty()'></when>"
            + "<otherwise>OR account_id IN "
            + "<foreach collection='accountIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</otherwise></choose>)</if> LIMIT 1</script>")
    AlertEvent selectAlertByKeyInScope(@Param("alertKey") String alertKey,
                                       @Param("accountIds") java.util.List<Long> accountIds);

    @Update("<script>UPDATE alert_event SET status = #{status} WHERE alert_key = #{alertKey} "
            + "<if test='accountIds != null'>AND (account_id IS NULL "
            + "<if test='!accountIds.isEmpty()'>OR account_id IN "
            + "<foreach collection='accountIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</if>)</if></script>")
    int updateStatusToInScope(@Param("alertKey") String alertKey, @Param("status") String status,
                              @Param("accountIds") java.util.List<Long> accountIds);

    @Select("<script>SELECT * FROM alert_event "
            + "<where><if test='status != null and status != \"\"'>AND status = #{status}</if>"
            + "<choose><when test='accountId != null'>AND account_id = #{accountId}</when>"
            + "<when test='accountIds != null'>AND (account_id IS NULL "
            + "<if test='!accountIds.isEmpty()'>OR account_id IN "
            + "<foreach collection='accountIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</if>)</when></choose></where>"
            + "ORDER BY first_seen_at DESC LIMIT #{limit}</script>")
    java.util.List<AlertEvent> selectAlertsByScope(@Param("status") String status,
                                                   @Param("accountId") Long accountId,
                                                   @Param("accountIds") java.util.List<Long> accountIds,
                                                   @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM alert_event WHERE status IN ('ACTIVE', 'ACKED') AND send_attempts > 0 "
            + "AND last_sent_at IS NULL")
    long countDeliveryFailures();

    @Select("<script>SELECT COUNT(*) FROM alert_event "
            + "WHERE status IN ('ACTIVE', 'ACKED') AND send_attempts > 0 AND last_sent_at IS NULL "
            + "<if test='accountIds != null'>AND (account_id IS NULL "
            + "<if test='!accountIds.isEmpty()'>OR account_id IN "
            + "<foreach collection='accountIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</if>)</if></script>")
    long countDeliveryFailuresByAccountIds(@Param("accountIds") java.util.List<Long> accountIds);

    @Select("SELECT COUNT(*) FROM alert_event WHERE status IN ('ACTIVE', 'ACKED', 'SILENCED')")
    long countPending();
}