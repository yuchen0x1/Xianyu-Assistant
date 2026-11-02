package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 获取商品列表响应DTO。
 */
@Data
public class ItemListRespDTO {
    /**
     * 是否成功。
     */
    private Boolean success;

    /**
     * 页码。
     */
    private Integer pageNumber;

    /**
     * 每页数量。
     */
    private Integer pageSize;

    /**
     * 当前页商品数量。
     */
    private Integer currentCount;

    /**
     * 已保存数量。单页请求不再落库，该字段仅保留兼容性并始终表示当前页条数。
     */
    private Integer savedCount;

    /**
     * 官方接口的权威分页终止标志。
     */
    private Boolean nextPage;

    /**
     * 后续页请求需原样回传的分页游标，保持 Object 避免数值和对象形态丢失。
     */
    private Object nextPageModel;

    /**
     * 后续页请求需原样回传的页序号或游标编号。
     */
    private Object nextPageNum;

    /**
     * 首屏动态返回的商品分组上下文。
     */
    private List<Map<String, Object>> itemGroupList;

    /**
     * 商品列表。
     */
    private List<ItemDTO> items;
}
