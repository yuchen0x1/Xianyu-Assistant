package com.feijimiao.xianyuassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feijimiao.xianyuassistant.entity.XianyuNotification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 站内通知 Mapper
 */
@Mapper
public interface XianyuNotificationMapper extends BaseMapper<XianyuNotification> {

    /**
     * 查询当前用户可见的最近通知；历史收件人为空的数据按广播通知处理。
     * 广播通知的已读状态来自当前用户回执，不读取通知行上的共享状态。
     */
    @Select("SELECT n.id, n.title, n.content, n.notify_type, n.level, "
            + "n.recipient_user_id, n.account_id, n.broadcast, "
            + "CASE WHEN (n.broadcast = 1 OR n.recipient_user_id IS NULL) "
            + "THEN CASE WHEN n.read_status = 1 OR r.notification_id IS NOT NULL THEN 1 ELSE 0 END "
            + "ELSE n.read_status END AS read_status, n.created_time "
            + "FROM xianyu_notification n "
            + "LEFT JOIN notification_read_receipt r "
            + "ON r.notification_id = n.id AND r.user_id = #{userId} "
            + "WHERE (n.broadcast = 1 OR n.recipient_user_id = #{userId} OR n.recipient_user_id IS NULL) "
            + "ORDER BY n.id DESC LIMIT #{limit}")
    List<XianyuNotification> selectRecent(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 统计当前用户可见的未读通知数量。
     */
    @Select("SELECT COUNT(*) FROM xianyu_notification n "
            + "WHERE (n.broadcast = 1 OR n.recipient_user_id = #{userId} OR n.recipient_user_id IS NULL) "
            + "AND (((n.broadcast = 1 OR n.recipient_user_id IS NULL) AND n.read_status = 0 "
            + "AND NOT EXISTS (SELECT 1 FROM notification_read_receipt r "
            + "WHERE r.notification_id = n.id AND r.user_id = #{userId})) "
            + "OR (n.broadcast = 0 AND n.recipient_user_id = #{userId} AND n.read_status = 0))")
    long countUnread(@Param("userId") Long userId);

    /**
     * 为广播/历史系统通知写入当前用户的已读回执。INSERT OR IGNORE 保证重复点击幂等。
     */
    @Insert("INSERT OR IGNORE INTO notification_read_receipt (notification_id, user_id) "
            + "SELECT id, #{userId} FROM xianyu_notification "
            + "WHERE id = #{id} AND (broadcast = 1 OR recipient_user_id IS NULL)")
    int markBroadcastRead(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 查询当前用户是否已有广播通知回执，用于让重复标记保持成功语义。
     */
    @Select("SELECT COUNT(*) FROM notification_read_receipt "
            + "WHERE notification_id = #{id} AND user_id = #{userId}")
    boolean hasReadReceipt(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 标记当前用户的单条定向通知已读。
     */
    @Update("UPDATE xianyu_notification SET read_status = 1 "
            + "WHERE id = #{id} AND broadcast = 0 AND recipient_user_id = #{userId} AND read_status = 0")
    int markDirectRead(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 为当前用户将所有可见广播/历史系统通知写入已读回执。
     */
    @Insert("INSERT OR IGNORE INTO notification_read_receipt (notification_id, user_id) "
            + "SELECT n.id, #{userId} FROM xianyu_notification n "
            + "WHERE (n.broadcast = 1 OR n.recipient_user_id IS NULL) "
            + "AND NOT EXISTS (SELECT 1 FROM notification_read_receipt r "
            + "WHERE r.notification_id = n.id AND r.user_id = #{userId})")
    int markAllBroadcastRead(@Param("userId") Long userId);

    /**
     * 将当前用户所有可见定向通知标记已读。
     */
    @Update("UPDATE xianyu_notification SET read_status = 1 "
            + "WHERE broadcast = 0 AND recipient_user_id = #{userId} AND read_status = 0")
    int markAllDirectRead(@Param("userId") Long userId);
}
