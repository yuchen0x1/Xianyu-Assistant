package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsTemplate;

import java.util.List;
import java.util.Map;

/**
 * 商品发布模板服务（卡片F3：模板CRUD + CSV批量导入上架）
 */
public interface GoodsTemplateService {

    /**
     * 保存模板（id为空则新建，否则更新）
     */
    XianyuGoodsTemplate save(XianyuGoodsTemplate template);

    /**
     * 分页查询模板
     */
    Map<String, Object> page(int pageNum, int pageSize);

    /**
     * 删除模板
     */
    boolean delete(Long id);

    /**
     * 导入CSV创建模板
     *
     * @param csvContent CSV文本（UTF-8，可带BOM），首行可为表头
     * @return 导入结果
     */
    Map<String, Object> importCsv(String csvContent);

    /**
     * 批量上架：将模板按账号发布到闲鱼
     *
     * @param accountId 闲鱼账号ID
     * @param templateIds 模板ID列表
     * @return 每条发布结果
     */
    List<Map<String, Object>> batchPublish(Long accountId, List<Long> templateIds);
}