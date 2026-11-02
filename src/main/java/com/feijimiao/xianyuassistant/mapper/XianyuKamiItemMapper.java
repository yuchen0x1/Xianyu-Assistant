package com.feijimiao.xianyuassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface XianyuKamiItemMapper extends BaseMapper<XianyuKamiItem> {

    @Select("SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status = 0 ORDER BY sort_order ASC LIMIT 1")
    XianyuKamiItem findNextUnused(@Param("kamiConfigId") Long kamiConfigId);

    @Select("SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status = 1 AND order_id = #{reservationKey} LIMIT 1")
    XianyuKamiItem findByReservationKey(@Param("kamiConfigId") Long kamiConfigId,
                                        @Param("reservationKey") String reservationKey);

    @Select("<script>" +
            "SELECT ki.* FROM xianyu_kami_item ki " +
            "LEFT JOIN xianyu_kami_usage_record ur " +
            "ON ur.kami_item_id = ki.id AND ur.order_id = #{legacyOrderId} " +
            "WHERE ki.status = 1 " +
            "AND ki.kami_config_id IN " +
            "<foreach collection='configIds' item='configId' open='(' separator=',' close=')'>" +
            "#{configId}" +
            "</foreach> " +
            "AND (ki.order_id = #{legacyOrderId} " +
            "OR substr(ki.order_id, 1, length(#{actionPrefix})) = #{actionPrefix}) " +
            "ORDER BY COALESCE(ur.create_time, ki.used_time, ki.create_time) ASC, " +
            "COALESCE(ur.id, ki.id) ASC, ki.id ASC" +
            "</script>")
    List<XianyuKamiItem> findOrderReservations(
            @Param("configIds") List<Long> configIds,
            @Param("legacyOrderId") String legacyOrderId,
            @Param("actionPrefix") String actionPrefix);

    @Update("UPDATE xianyu_kami_item SET order_id = #{reservationKey} " +
            "WHERE id = #{id} AND status = 1 AND order_id = #{legacyOrderId}")
    int migrateLegacyReservation(@Param("id") Long id,
                                 @Param("legacyOrderId") String legacyOrderId,
                                 @Param("reservationKey") String reservationKey);

    @Select("SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status = 0 ORDER BY RANDOM() LIMIT 1")
    XianyuKamiItem findRandomUnused(@Param("kamiConfigId") Long kamiConfigId);

    @Select("SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} ORDER BY sort_order ASC")
    List<XianyuKamiItem> findByConfigId(@Param("kamiConfigId") Long kamiConfigId);

    @Select("<script>" +
            "SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} " +
            "<if test='status != null'>" +
            "AND status = #{status} " +
            "</if>" +
            "<if test='pendingOnly'>" +
            "AND status = 1 AND delivered_time IS NULL " +
            "</if>" +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND kami_content LIKE '%' || #{keyword} || '%' " +
            "</if>" +
            "ORDER BY sort_order ASC" +
            "</script>")
    List<XianyuKamiItem> findByConfigIdWithFilter(
            @Param("kamiConfigId") Long kamiConfigId,
            @Param("status") Integer status,
            @Param("pendingOnly") boolean pendingOnly,
            @Param("keyword") String keyword);

    @Select("SELECT COUNT(*) FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status = 0")
    int countUnused(@Param("kamiConfigId") Long kamiConfigId);

    @Select("SELECT COUNT(*) FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status = 1")
    int countUsed(@Param("kamiConfigId") Long kamiConfigId);

    @Select("SELECT COUNT(*) FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId}")
    int countByConfigId(@Param("kamiConfigId") Long kamiConfigId);

    @Select("SELECT COUNT(*) FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND kami_content = #{kamiContent}")
    int countByConfigIdAndContent(@Param("kamiConfigId") Long kamiConfigId, @Param("kamiContent") String kamiContent);

    @Update("UPDATE xianyu_kami_item SET status = 1, order_id = #{orderId}, used_time = datetime('now', 'localtime'), delivered_time = NULL WHERE id = #{id} AND status = 0")
    int markUsed(@Param("id") Long id, @Param("orderId") String orderId);

    @Update("UPDATE xianyu_kami_item SET status = 0, order_id = NULL, used_time = NULL, delivered_time = NULL WHERE id = #{id} AND status = 1")
    int markUnused(@Param("id") Long id);

    @Update("UPDATE xianyu_kami_item SET status = 0, order_id = NULL, used_time = NULL, delivered_time = NULL WHERE id = #{id} AND status = 1 AND order_id = #{reservationKey}")
    int markUnusedIfReserved(@Param("id") Long id, @Param("reservationKey") String reservationKey);

    /**
     * 确认该预占确实已交付给买家。只对「仍在预占、尚未确认」的记录生效，重复调用不会覆盖首次确认时间。
     */
    @Update("UPDATE xianyu_kami_item SET delivered_time = datetime('now', 'localtime') " +
            "WHERE id = #{id} AND status = 1 AND order_id = #{reservationKey} AND delivered_time IS NULL")
    int markDelivered(@Param("id") Long id, @Param("reservationKey") String reservationKey);

    /**
     * 滞留预占：已占用但从未确认交付，且预占时间早于 cutoff。
     * 用于回收进程崩溃、响应丢失等原因停在 status=1 的卡密。
     */
    @Select("SELECT * FROM xianyu_kami_item " +
            "WHERE status = 1 AND delivered_time IS NULL " +
            "AND used_time IS NOT NULL AND used_time <= #{cutoff} " +
            "ORDER BY used_time ASC, id ASC LIMIT #{limit}")
    List<XianyuKamiItem> findStaleReservations(@Param("cutoff") String cutoff,
                                               @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM xianyu_kami_item " +
            "WHERE kami_config_id = #{kamiConfigId} AND status = 1 AND delivered_time IS NULL")
    int countPendingDelivery(@Param("kamiConfigId") Long kamiConfigId);

    @Select("SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status = #{status} ORDER BY sort_order ASC")
    List<XianyuKamiItem> findByConfigIdAndStatus(@Param("kamiConfigId") Long kamiConfigId, @Param("status") Integer status);
}
