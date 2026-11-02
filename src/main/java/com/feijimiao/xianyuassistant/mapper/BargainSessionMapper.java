package com.feijimiao.xianyuassistant.mapper;

import com.feijimiao.xianyuassistant.entity.BargainSession;
import org.apache.ibatis.annotations.*;

/**
 * 议价会话Mapper
 */
@Mapper
public interface BargainSessionMapper {

    @Select("SELECT * FROM bargain_session WHERE xianyu_account_id = #{accountId} AND s_id = #{sId}")
    BargainSession selectByAccountAndSessionId(@Param("accountId") Long accountId, @Param("sId") String sId);

    @Insert("INSERT INTO bargain_session (xianyu_account_id, s_id, xy_goods_id, buyer_user_id, rounds_used, last_offer_price, last_reply_content, status, last_bargain_at) " +
            "VALUES (#{xianyuAccountId}, #{sId}, #{xyGoodsId}, #{buyerUserId}, #{roundsUsed}, #{lastOfferPrice}, #{lastReplyContent}, #{status}, #{lastBargainAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BargainSession session);

    @Update("UPDATE bargain_session SET rounds_used = #{roundsUsed}, last_offer_price = #{lastOfferPrice}, " +
            "last_reply_content = #{lastReplyContent}, status = #{status}, last_bargain_at = #{lastBargainAt}, " +
            "update_time = datetime('now', 'localtime') WHERE id = #{id}")
    int update(BargainSession session);
}
