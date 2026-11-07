package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.controller.dto.BatchOffShelfItemsRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.PublishItemReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.PublishItemRespDTO;

import java.util.List;

/**
 * 商品发布/下架服务（卖家运营闭环）
 */
public interface PublishService {

    /**
     * AI 类目识别：根据标题/描述/图片推荐闲鱼类目
     *
     * @param accountId 账号ID
     * @param title 商品标题
     * @param description 商品描述
     * @param images 图片CDN URL列表
     * @return 类目推荐列表
     */
    List<PublishItemRespDTO.CategoryPredict> recommendCategory(Long accountId, String title,
                                                               String description, List<String> images);

    /**
     * 发布商品（支持仅返回类目识别结果供前端预览）
     */
    PublishItemRespDTO publish(PublishItemReqDTO req);

    /**
     * 下架/删除商品
     *
     * @param accountId 账号ID
     * @param itemId 商品ID
     * @return 是否成功
     */
    boolean offShelf(Long accountId, String itemId);

    /**
     * 批量下架商品：逐件调用闲鱼下架接口，件间间隔以规避平台频控。
     *
     * <p>单件失败不影响其余商品，最终返回逐件成功/失败明细。</p>
     *
     * @param accountId 账号ID
     * @param itemIds 商品ID列表
     * @return 批量下架结果
     */
    BatchOffShelfItemsRespDTO batchOffShelf(Long accountId, List<String> itemIds);
}
