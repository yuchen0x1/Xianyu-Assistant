package com.feijimiao.xianyuassistant.mapper;

import com.feijimiao.xianyuassistant.entity.XianyuChatMessage;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 闲鱼聊天消息Mapper
 */
@Mapper
public interface XianyuChatMessageMapper {
    
    /**
     * 插入聊天消息
     */
    @Insert("INSERT INTO xianyu_chat_message (" +
            "xianyu_account_id, lwp, pnm_id, s_id, " +
            "content_type, msg_content, " +
            "sender_user_name, sender_user_id, sender_app_v, sender_os_type, " +
            "reminder_url, xy_goods_id, complete_msg, message_time" +
            ") VALUES (" +
            "#{xianyuAccountId}, #{lwp}, #{pnmId}, #{sId}, " +
            "#{contentType}, #{msgContent}, " +
            "#{senderUserName}, #{senderUserId}, #{senderAppV}, #{senderOsType}, " +
            "#{reminderUrl}, #{xyGoodsId}, #{completeMsg}, #{messageTime}" +
            ")")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(XianyuChatMessage message);
    
    /**
     * 根据pnm_id查询（防止重复）
     */
    @Select("SELECT * FROM xianyu_chat_message " +
            "WHERE xianyu_account_id = #{accountId} AND pnm_id = #{pnmId}")
    XianyuChatMessage findByPnmId(@Param("accountId") Long accountId, 
                                  @Param("pnmId") String pnmId);
    
    /**
     * 查询账号的所有消息
     */
    @Select("SELECT * FROM xianyu_chat_message " +
            "WHERE xianyu_account_id = #{accountId} " +
            "ORDER BY message_time DESC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<XianyuChatMessage> findByAccountId(@Param("accountId") Long accountId,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset);
    
    /**
     * 按主键游标批量读取（备份导出用）。
     *
     * <p>相比 {@code LIMIT #{limit} OFFSET #{offset}}，游标翻页不会因为 OFFSET
     * 越来越大而退化成全表扫描：每次都是 {@code id > afterId} 的范围扫描，
     * 十万级数据下总耗时是线性的而不是平方级。</p>
     */
    @Select("SELECT * FROM xianyu_chat_message WHERE id > #{afterId} ORDER BY id ASC LIMIT #{limit}")
    List<XianyuChatMessage> selectBatchAfterId(@Param("afterId") Long afterId,
                                               @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM xianyu_chat_message")
    long countAll();

    /**
     * 根据s_id查询会话的消息
     */
    @Select("SELECT * FROM xianyu_chat_message " +
            "WHERE s_id = #{sId} " +
            "ORDER BY message_time ASC")
    List<XianyuChatMessage> findBySId(@Param("sId") String sId);
    
    /**
     * 根据发送者ID查询消息
     */
    @Select("SELECT * FROM xianyu_chat_message " +
            "WHERE sender_user_id = #{senderUserId} " +
            "ORDER BY message_time DESC")
    List<XianyuChatMessage> findBySenderUserId(@Param("senderUserId") String senderUserId);
    
    /**
     * 根据账号ID删除消息
     */
    @Delete("DELETE FROM xianyu_chat_message WHERE xianyu_account_id = #{accountId}")
    int deleteByAccountId(@Param("accountId") Long accountId);
    
    /**
     * 分页查询消息（支持按xy_goods_id过滤和sender_user_id过滤）
     *
     * @param accountId 账号ID（必选）
     * @param xyGoodsId 商品ID（可选，为null时不过滤）
     * @param senderUserId 发送者用户ID（可选，为null时不过滤）
     * @param limit 每页数量
     * @param offset 偏移量
     * @return 消息列表
     */
    @Select("<script>" +
            "SELECT * FROM xianyu_chat_message " +
            "WHERE xianyu_account_id = #{accountId} " +
            "<if test='xyGoodsId != null and xyGoodsId != \"\"'>" +
            "AND xy_goods_id = #{xyGoodsId} " +
            "</if>" +
            "<if test='senderUserId != null and senderUserId != \"\"'>" +
            "AND sender_user_id != #{senderUserId} " +
            "</if>" +
            "ORDER BY message_time DESC " +
            "LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<XianyuChatMessage> findMessagesByPage(@Param("accountId") Long accountId,
                                               @Param("xyGoodsId") String xyGoodsId,
                                               @Param("senderUserId") String senderUserId,
                                               @Param("limit") int limit,
                                               @Param("offset") int offset);
    
    /**
     * 统计消息总数（支持按xy_goods_id过滤和sender_user_id过滤）
     *
     * @param accountId 账号ID（必选）
     * @param xyGoodsId 商品ID（可选，为null时不过滤）
     * @param senderUserId 发送者用户ID（可选，为null时不过滤）
     * @return 消息总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM xianyu_chat_message " +
            "WHERE xianyu_account_id = #{accountId} " +
            "<if test='xyGoodsId != null and xyGoodsId != \"\"'>" +
            "AND xy_goods_id = #{xyGoodsId} " +
            "</if>" +
            "<if test='senderUserId != null and senderUserId != \"\"'>" +
            "AND sender_user_id != #{senderUserId} " +
            "</if>" +
            "</script>")
    int countMessages(@Param("accountId") Long accountId,
                     @Param("xyGoodsId") String xyGoodsId,
                     @Param("senderUserId") String senderUserId);
    
    /**
     * 根据会话ID查询最近N条消息（支持分页）
     *
     * @param sId 会话ID
     * @param limit 限制条数
     * @param offset 偏移量
     * @return 消息列表
     */
    @Select("SELECT * FROM xianyu_chat_message " +
            "WHERE s_id = #{sId} " +
            "ORDER BY message_time DESC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<XianyuChatMessage> findRecentBySId(@Param("sId") String sId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * 咨询商品排行（按买家消息数降序）
     * 排除系统账号自己发送的消息
     */
    @Select("SELECT xy_goods_id AS goodsId, COUNT(*) AS msgCount " +
            "FROM xianyu_chat_message " +
            "WHERE xy_goods_id IS NOT NULL AND xy_goods_id != '' " +
            "GROUP BY xy_goods_id ORDER BY msgCount DESC LIMIT #{limit}")
    List<java.util.Map<String, Object>> selectTopGoodsByMessages(@Param("limit") int limit);

    @Select("<script>SELECT xy_goods_id AS goodsId, COUNT(*) AS msgCount FROM xianyu_chat_message WHERE xy_goods_id IS NOT NULL AND xy_goods_id != '' <choose><when test='scope.accountIds == null'></when><when test='scope.accountIds.isEmpty()'>AND 1 = 0</when><otherwise>AND xianyu_account_id IN <foreach collection='scope.accountIds' item='accountId' open='(' separator=',' close=')'>#{accountId}</foreach></otherwise></choose> GROUP BY xy_goods_id ORDER BY msgCount DESC LIMIT #{limit}</script>")
    List<java.util.Map<String, Object>> selectTopGoodsByMessages(@Param("limit") int limit, @Param("scope") com.feijimiao.xianyuassistant.service.DataPanelAccountScope scope);

    /**
     * 会话聚合查询：按 s_id 分组取每个会话最新一条消息（含消息数），支持关键词过滤与分页
     *
     * @param accountId 账号ID（必选）
     * @param keyword   关键词（可选，匹配买家名称或消息内容）
     * @param limit     每页数量
     * @param offset    偏移量
     * @return 会话列表（每行含最新消息字段 + messageCount）
     */
    @Select("<script>" +
            "SELECT m.s_id AS sid, " +
            "       m.sender_user_name AS buyerName, " +
            "       m.sender_user_id AS buyerUserId, " +
            "       m.xy_goods_id AS xyGoodsId, " +
            "       m.msg_content AS lastContent, " +
            "       m.message_time AS lastTime, " +
            "       c.msg_count AS messageCount " +
            "FROM xianyu_chat_message m " +
            "INNER JOIN (" +
            "  SELECT s_id, MAX(id) AS max_id, COUNT(*) AS msg_count " +
            "  FROM xianyu_chat_message " +
            "  WHERE xianyu_account_id = #{accountId} " +
            "  <if test='keyword != null and keyword != \"\"'>" +
            "  AND (sender_user_name LIKE '%' || #{keyword} || '%' OR msg_content LIKE '%' || #{keyword} || '%') " +
            "  </if>" +
            "  GROUP BY s_id" +
            ") c ON m.id = c.max_id " +
            "WHERE m.xianyu_account_id = #{accountId} " +
            "ORDER BY m.message_time DESC " +
            "LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<java.util.Map<String, Object>> findSessions(@Param("accountId") Long accountId,
                                                     @Param("keyword") String keyword,
                                                     @Param("limit") int limit,
                                                     @Param("offset") int offset);

    /**
     * 统计会话总数（支持关键词过滤）
     *
     * @param accountId 账号ID（必选）
     * @param keyword   关键词（可选，匹配买家名称或消息内容）
     * @return 会话总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM (" +
            "  SELECT s_id FROM xianyu_chat_message " +
            "  WHERE xianyu_account_id = #{accountId} " +
            "  <if test='keyword != null and keyword != \"\"'>" +
            "  AND (sender_user_name LIKE '%' || #{keyword} || '%' OR msg_content LIKE '%' || #{keyword} || '%') " +
            "  </if>" +
            "  GROUP BY s_id" +
            ")" +
            "</script>")
    int countSessions(@Param("accountId") Long accountId,
                      @Param("keyword") String keyword);
}