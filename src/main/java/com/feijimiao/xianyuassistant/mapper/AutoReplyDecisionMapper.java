package com.feijimiao.xianyuassistant.mapper;

import com.feijimiao.xianyuassistant.entity.AutoReplyDecision;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 自动回复决策日志 Mapper
 */
@Mapper
public interface AutoReplyDecisionMapper {

    /**
     * 插入决策记录
     */
    @Insert("INSERT INTO auto_reply_decision (xianyu_account_id, s_id, xy_goods_id, buyer_user_id, buyer_message, reason_code, detail) " +
            "VALUES (#{xianyuAccountId}, #{sId}, #{xyGoodsId}, #{buyerUserId}, #{buyerMessage}, #{reasonCode}, #{detail})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AutoReplyDecision decision);

    /**
     * 查询同一会话同一原因在指定分钟内的已有记录（用于去重，避免人工接管会话内每条消息都落一条）
     */
    @Select("SELECT COUNT(*) FROM auto_reply_decision " +
            "WHERE xianyu_account_id = #{accountId} AND s_id = #{sId} AND reason_code = #{reasonCode} " +
            "AND create_time >= datetime('now', 'localtime', '-' || #{minutes} || ' minutes')")
    int countRecent(@Param("accountId") Long accountId, @Param("sId") String sId,
                    @Param("reasonCode") String reasonCode, @Param("minutes") int minutes);

    /**
     * 按条件分页查询（条件可为空）
     */
    @Select("<script>SELECT * FROM auto_reply_decision " +
            "<where>" +
            "<if test='accountId != null'>AND xianyu_account_id = #{accountId}</if>" +
            "<if test='reasonCode != null and reasonCode != \"\"'>AND reason_code = #{reasonCode}</if>" +
            "</where> ORDER BY create_time DESC, id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<AutoReplyDecision> selectPage(@Param("accountId") Long accountId, @Param("reasonCode") String reasonCode,
                                       @Param("limit") int limit, @Param("offset") int offset);

    /**
     * 按条件统计总数
     */
    @Select("<script>SELECT COUNT(*) FROM auto_reply_decision " +
            "<where>" +
            "<if test='accountId != null'>AND xianyu_account_id = #{accountId}</if>" +
            "<if test='reasonCode != null and reasonCode != \"\"'>AND reason_code = #{reasonCode}</if>" +
            "</where></script>")
    int countPage(@Param("accountId") Long accountId, @Param("reasonCode") String reasonCode);

    /**
     * 清理指定天数之前的记录（供后续保留策略使用）
     */
    @Delete("DELETE FROM auto_reply_decision WHERE create_time < datetime('now', 'localtime', '-' || #{days} || ' days')")
    int deleteOlderThan(@Param("days") int days);
}
