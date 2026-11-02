package com.feijimiao.xianyuassistant.mapper;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoReplyRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 商品自动回复记录Mapper
 */
@Mapper
public interface XianyuGoodsAutoReplyRecordMapper {
    
    /**
     * 插入记录
     */
    @Insert("INSERT INTO xianyu_goods_auto_reply_record (xianyu_account_id, xianyu_goods_id, xy_goods_id, s_id, pnm_id, buyer_user_id, buyer_user_name, buyer_message, reply_content, reply_type, matched_keyword, trigger_context, state) " +
            "VALUES (#{xianyuAccountId}, #{xianyuGoodsId}, #{xyGoodsId}, #{sId}, #{pnmId}, #{buyerUserId}, #{buyerUserName}, #{buyerMessage}, #{replyContent}, #{replyType}, #{matchedKeyword}, #{triggerContext}, #{state})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(XianyuGoodsAutoReplyRecord record);
    
    /**
     * 更新记录状态和回复内容
     */
    @Update("UPDATE xianyu_goods_auto_reply_record SET state = #{state}, reply_content = #{replyContent} WHERE id = #{id}")
    int updateStateAndContent(@Param("id") Long id, @Param("state") Integer state, @Param("replyContent") String replyContent);
    
    /**
     * 更新触发上下文
     */
    @Update("UPDATE xianyu_goods_auto_reply_record SET trigger_context = #{triggerContext} WHERE id = #{id}")
    int updateTriggerContext(@Param("id") Long id, @Param("triggerContext") String triggerContext);

    /**
     * 根据ID查询记录
     */
    @Select("SELECT * FROM xianyu_goods_auto_reply_record WHERE id = #{id}")
    XianyuGoodsAutoReplyRecord selectById(@Param("id") Long id);

    /**
     * 根据账号 / 会话 / 消息 pnmId 查询回复记录（唯一索引对应的定位方式）。
     */
    @Select("SELECT * FROM xianyu_goods_auto_reply_record WHERE xianyu_account_id = #{accountId} "
            + "AND s_id = #{sId} AND pnm_id = #{pnmId} LIMIT 1")
    XianyuGoodsAutoReplyRecord selectByAccountSIdPnm(@Param("accountId") Long accountId,
                                                     @Param("sId") String sId,
                                                     @Param("pnmId") String pnmId);

    /**
     * 把明确失败（state=-1）的记录重新打开为待回复，允许同一 pnmId 再执行一次。
     * 条件更新保证只有失败记录会被重开，不会重复发送已成功的回复。
     */
    @Update("UPDATE xianyu_goods_auto_reply_record SET state = 0, reply_content = NULL "
            + "WHERE id = #{id} AND state = -1")
    int reopenFailed(@Param("id") Long id);

    /**
     * 分页查询待人工确认的记录（state=2）
     */
    @Select("SELECT * FROM xianyu_goods_auto_reply_record WHERE state = 2 AND xianyu_account_id = #{accountId} ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<XianyuGoodsAutoReplyRecord> selectPendingConfirm(@Param("accountId") Long accountId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * 统计待人工确认的记录数（state=2）
     */
    @Select("SELECT COUNT(*) FROM xianyu_goods_auto_reply_record WHERE state = 2 AND xianyu_account_id = #{accountId}")
    int countPendingConfirm(@Param("accountId") Long accountId);
    
    /**
     * 根据账号ID查询记录
     */
    @Select("SELECT * FROM xianyu_goods_auto_reply_record WHERE xianyu_account_id = #{accountId} ORDER BY create_time DESC")
    List<XianyuGoodsAutoReplyRecord> selectByAccountId(@Param("accountId") Long accountId);
    
    /**
     * 根据账号ID和会话ID查询最新记录
     */
    @Select("SELECT * FROM xianyu_goods_auto_reply_record WHERE xianyu_account_id = #{accountId} AND s_id = #{sId} ORDER BY create_time DESC LIMIT 1")
    XianyuGoodsAutoReplyRecord selectLatestByAccountIdAndSId(@Param("accountId") Long accountId, @Param("sId") String sId);
    
    /**
     * 根据账号ID删除记录
     */
    @Delete("DELETE FROM xianyu_goods_auto_reply_record WHERE xianyu_account_id = #{accountId}")
    int deleteByAccountId(@Param("accountId") Long accountId);
    
    /**
     * 根据账号ID和商品ID分页查询记录
     */
    @Select("SELECT * FROM xianyu_goods_auto_reply_record WHERE xianyu_account_id = #{accountId} AND xy_goods_id = #{xyGoodsId} ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<XianyuGoodsAutoReplyRecord> selectByAccountIdAndGoodsId(@Param("accountId") Long accountId, @Param("xyGoodsId") String xyGoodsId, @Param("limit") int limit, @Param("offset") int offset);
    
    /**
     * 根据账号ID和商品ID查询记录总数
     */
    @Select("SELECT COUNT(*) FROM xianyu_goods_auto_reply_record WHERE xianyu_account_id = #{accountId} AND xy_goods_id = #{xyGoodsId}")
    int countByAccountIdAndGoodsId(@Param("accountId") Long accountId, @Param("xyGoodsId") String xyGoodsId);

    @Select("SELECT COUNT(*) FROM xianyu_goods_auto_reply_record WHERE date(create_time) = date('now', '-1 day', 'localtime')")
    int countYesterdayAiReplies();

    @Select("SELECT COUNT(*) FROM xianyu_goods_auto_reply_record")
    int countAllReplies();

    @Select("<script>SELECT COUNT(*) FROM xianyu_goods_auto_reply_record <where><choose><when test='scope.accountIds == null'></when><when test='scope.accountIds.isEmpty()'>1 = 0</when><otherwise>xianyu_account_id IN <foreach collection='scope.accountIds' item='accountId' open='(' separator=',' close=')'>#{accountId}</foreach></otherwise></choose></where></script>")
    int countAllReplies(@Param("scope") com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope);

    @Select("SELECT COUNT(*) FROM xianyu_goods_auto_reply_record WHERE date(create_time) = #{date}")
    int countAiRepliesByDate(@Param("date") String date);

    @Select("<script>SELECT COUNT(*) FROM xianyu_goods_auto_reply_record WHERE date(create_time) = #{date} <choose><when test='scope.accountIds == null'></when><when test='scope.accountIds.isEmpty()'>AND 1 = 0</when><otherwise>AND xianyu_account_id IN <foreach collection='scope.accountIds' item='accountId' open='(' separator=',' close=')'>#{accountId}</foreach></otherwise></choose></script>")
    int countAiRepliesByDate(@Param("date") String date, @Param("scope") com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope);

    /**
     * 按账号分组的AI回复统计（指定日期）
     */
    @Select("SELECT xianyu_account_id AS accountId, COUNT(*) AS replyCount " +
            "FROM xianyu_goods_auto_reply_record WHERE date(create_time) = #{date} " +
            "GROUP BY xianyu_account_id ORDER BY replyCount DESC")
    List<java.util.Map<String, Object>> selectAccountRepliesByDate(@Param("date") String date);

    @Select("<script>SELECT xianyu_account_id AS accountId, COUNT(*) AS replyCount FROM xianyu_goods_auto_reply_record WHERE date(create_time) = #{date} <choose><when test='scope.accountIds == null'></when><when test='scope.accountIds.isEmpty()'>AND 1 = 0</when><otherwise>AND xianyu_account_id IN <foreach collection='scope.accountIds' item='accountId' open='(' separator=',' close=')'>#{accountId}</foreach></otherwise></choose> GROUP BY xianyu_account_id ORDER BY replyCount DESC</script>")
    List<java.util.Map<String, Object>> selectAccountRepliesByDate(@Param("date") String date, @Param("scope") com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope);

    /**
     * 关键词命中排行（指定日期，按 matched_keyword 聚合）
     * 统计命中次数、成功/失败/待回复数与涉及买家数
     */
    @Select("SELECT matched_keyword AS keyword, " +
            "COUNT(*) AS hitCount, " +
            "SUM(CASE WHEN state = 1 THEN 1 ELSE 0 END) AS successCount, " +
            "SUM(CASE WHEN state = -1 THEN 1 ELSE 0 END) AS failCount, " +
            "SUM(CASE WHEN state = 0 THEN 1 ELSE 0 END) AS pendingCount, " +
            "COUNT(DISTINCT buyer_user_id) AS buyerCount " +
            "FROM xianyu_goods_auto_reply_record " +
            "WHERE date(create_time) = #{date} " +
            "AND matched_keyword IS NOT NULL AND matched_keyword != '' " +
            "GROUP BY matched_keyword ORDER BY hitCount DESC LIMIT #{limit}")
    List<java.util.Map<String, Object>> selectKeywordRankByDate(@Param("date") String date, @Param("limit") int limit);

    @Select("<script>SELECT matched_keyword AS keyword, COUNT(*) AS hitCount, SUM(CASE WHEN state = 1 THEN 1 ELSE 0 END) AS successCount, SUM(CASE WHEN state = -1 THEN 1 ELSE 0 END) AS failCount, SUM(CASE WHEN state = 0 THEN 1 ELSE 0 END) AS pendingCount, COUNT(DISTINCT buyer_user_id) AS buyerCount FROM xianyu_goods_auto_reply_record WHERE date(create_time) = #{date} <choose><when test='scope.accountIds == null'></when><when test='scope.accountIds.isEmpty()'>AND 1 = 0</when><otherwise>AND xianyu_account_id IN <foreach collection='scope.accountIds' item='accountId' open='(' separator=',' close=')'>#{accountId}</foreach></otherwise></choose> AND matched_keyword IS NOT NULL AND matched_keyword != '' GROUP BY matched_keyword ORDER BY hitCount DESC LIMIT #{limit}</script>")
    List<java.util.Map<String, Object>> selectKeywordRankByDate(@Param("date") String date, @Param("limit") int limit, @Param("scope") com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope);
}