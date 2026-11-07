package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.controller.dto.TodoRespDTO;

/**
 * 待办中心服务接口。
 *
 * <p>按当前用户可见账号范围（RBAC）聚合卖家日常待办：
 * 待发货订单、待回复会话、发货结果待人工复核、自动评价待确认。</p>
 */
public interface TodoService {

    /**
     * 获取待办中心聚合数据。
     *
     * @param limit 每类待办最多返回条数
     * @return 待办中心聚合响应
     */
    TodoRespDTO overview(int limit);
}