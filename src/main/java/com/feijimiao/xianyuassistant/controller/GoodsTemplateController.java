package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsTemplate;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.service.GoodsTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 商品发布模板控制器（卡片F3：模板CRUD + CSV批量导入上架）
 */
@Slf4j
@RestController
@RequestMapping("/api/templates")
@RequirePermission("publish:read")
public class GoodsTemplateController {

    @Autowired
    private GoodsTemplateService goodsTemplateService;

    /**
     * 保存模板（新建/更新）
     */
    @PostMapping("/save")
    @RequirePermission("publish:write")
    public ResultObject<XianyuGoodsTemplate> save(@RequestBody XianyuGoodsTemplate template) {
        try {
            return ResultObject.success(goodsTemplateService.save(template));
        } catch (Exception e) {
            log.error("保存商品模板失败", e);
            return ResultObject.failed("保存模板失败: " + e.getMessage());
        }
    }

    /**
     * 分页查询模板
     */
    @PostMapping("/list")
    public ResultObject<Map<String, Object>> list(@RequestBody Map<String, Object> params) {
        try {
            int pageNum = params.get("pageNum") != null ? Integer.parseInt(params.get("pageNum").toString()) : 1;
            int pageSize = params.get("pageSize") != null ? Integer.parseInt(params.get("pageSize").toString()) : 20;
            return ResultObject.success(goodsTemplateService.page(pageNum, pageSize));
        } catch (Exception e) {
            log.error("查询商品模板失败", e);
            return ResultObject.failed("查询模板失败: " + e.getMessage());
        }
    }

    /**
     * 删除模板
     */
    @PostMapping("/delete")
    @RequirePermission("publish:write")
    public ResultObject<String> delete(@RequestBody Map<String, Object> params) {
        try {
            if (params.get("id") == null) {
                return ResultObject.failed("id不能为空");
            }
            Long id = Long.valueOf(params.get("id").toString());
            boolean ok = goodsTemplateService.delete(id);
            return ok ? ResultObject.success("删除成功") : ResultObject.failed("模板不存在");
        } catch (Exception e) {
            log.error("删除商品模板失败", e);
            return ResultObject.failed("删除模板失败: " + e.getMessage());
        }
    }

    /**
     * 导入CSV创建模板（列：标题,描述,价格,图片URL,类目ID,备注）
     */
    @PostMapping("/importCsv")
    @RequirePermission("publish:write")
    public ResultObject<Map<String, Object>> importCsv(@RequestBody Map<String, Object> params) {
        try {
            String content = params.get("content") != null ? params.get("content").toString() : null;
            return ResultObject.success(goodsTemplateService.importCsv(content));
        } catch (Exception e) {
            log.error("导入模板CSV失败", e);
            return ResultObject.failed("导入失败: " + e.getMessage());
        }
    }

    /**
     * 批量上架：将所选模板发布到闲鱼
     */
    @PostMapping("/batchPublish")
    @RequirePermission("publish:write")
    public ResultObject<List<Map<String, Object>>> batchPublish(@RequestBody Map<String, Object> params) {
        try {
            if (params.get("accountId") == null) {
                return ResultObject.failed("accountId不能为空");
            }
            Long accountId = Long.valueOf(params.get("accountId").toString());
            List<Long> templateIds = new ArrayList<>();
            if (params.get("templateIds") instanceof List<?> rawList) {
                for (Object o : rawList) {
                    templateIds.add(Long.valueOf(o.toString()));
                }
            }
            if (templateIds.isEmpty()) {
                return ResultObject.failed("请至少选择一个模板");
            }
            return ResultObject.success(goodsTemplateService.batchPublish(accountId, templateIds));
        } catch (Exception e) {
            log.error("模板批量上架失败", e);
            return ResultObject.failed("批量上架失败: " + e.getMessage());
        }
    }
}