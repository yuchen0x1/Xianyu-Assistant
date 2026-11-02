package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.entity.bo.KeywordReplyRuleBO;
import com.feijimiao.xianyuassistant.service.KeywordReplyService;
import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/keyword-reply")
@RequirePermission("auto-reply:read")
public class KeywordReplyController {

    @Autowired
    private KeywordReplyService keywordReplyService;

    @PostMapping("/rules")
    public ResultObject<List<KeywordReplyRuleBO>> getRules(@RequestBody Map<String, Object> params) {
        try {
            Long accountId = Long.valueOf(params.get("xianyuAccountId").toString());
            String xyGoodsId = params.get("xyGoodsId").toString();
            List<KeywordReplyRuleBO> rules = keywordReplyService.getRules(accountId, xyGoodsId);
            return ResultObject.success(rules);
        } catch (Exception e) {
            log.error("获取关键词回复规则失败", e);
            return ResultObject.failed("获取关键词回复规则失败: " + e.getMessage());
        }
    }

    @PostMapping("/addRule")
    @RequirePermission("auto-reply:write")
    public ResultObject<KeywordReplyRuleBO> addRule(@RequestBody Map<String, Object> params) {
        try {
            Long accountId = Long.valueOf(params.get("xianyuAccountId").toString());
            String xyGoodsId = params.get("xyGoodsId").toString();
            String keyword = params.get("keyword").toString();
            KeywordReplyRuleBO rule = keywordReplyService.addRule(accountId, xyGoodsId, keyword);
            return ResultObject.success(rule);
        } catch (Exception e) {
            log.error("添加关键词规则失败", e);
            return ResultObject.failed("添加关键词规则失败: " + e.getMessage());
        }
    }

    @PostMapping("/deleteRule")
    @RequirePermission("auto-reply:write")
    public ResultObject<?> deleteRule(@RequestBody Map<String, Object> params) {
        try {
            Long ruleId = Long.valueOf(params.get("ruleId").toString());
            keywordReplyService.deleteRule(ruleId);
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("删除关键词规则失败", e);
            return ResultObject.failed("删除关键词规则失败: " + e.getMessage());
        }
    }

    @PostMapping("/updateKeyword")
    @RequirePermission("auto-reply:write")
    public ResultObject<?> updateKeyword(@RequestBody Map<String, Object> params) {
        try {
            Long ruleId = Long.valueOf(params.get("ruleId").toString());
            String keyword = params.get("keyword").toString();
            keywordReplyService.updateKeyword(ruleId, keyword);
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("修改关键词失败", e);
            return ResultObject.failed("修改关键词失败: " + e.getMessage());
        }
    }

    @PostMapping("/updateMatchMode")
    @RequirePermission("auto-reply:write")
    public ResultObject<?> updateMatchMode(@RequestBody Map<String, Object> params) {
        try {
            Long ruleId = Long.valueOf(params.get("ruleId").toString());
            Integer matchMode = Integer.valueOf(params.get("matchMode").toString());
            keywordReplyService.updateMatchMode(ruleId, matchMode);
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("更新匹配模式失败", e);
            return ResultObject.failed("更新匹配模式失败: " + e.getMessage());
        }
    }

    @PostMapping("/ensureFallbackRule")
    @RequirePermission("auto-reply:write")
    public ResultObject<KeywordReplyRuleBO> ensureFallbackRule(@RequestBody Map<String, Object> params) {
        try {
            Long accountId = Long.valueOf(params.get("xianyuAccountId").toString());
            String xyGoodsId = params.get("xyGoodsId").toString();
            KeywordReplyRuleBO rule = keywordReplyService.ensureFallbackRule(accountId, xyGoodsId);
            return ResultObject.success(rule);
        } catch (Exception e) {
            log.error("获取未匹配回复规则失败", e);
            return ResultObject.failed("获取未匹配回复规则失败: " + e.getMessage());
        }
    }

    @PostMapping("/addContent")
    @RequirePermission("auto-reply:write")
    public ResultObject<KeywordReplyRuleBO.KeywordReplyContentBO> addContent(@RequestBody Map<String, Object> params) {
        try {
            Long ruleId = Long.valueOf(params.get("ruleId").toString());
            String replyText = params.get("replyText") != null ? params.get("replyText").toString() : null;
            String replyImageUrl = params.get("replyImageUrl") != null ? params.get("replyImageUrl").toString() : null;
            KeywordReplyRuleBO.KeywordReplyContentBO content = keywordReplyService.addContent(ruleId, replyText, replyImageUrl);
            return ResultObject.success(content);
        } catch (Exception e) {
            log.error("添加关键词回复内容失败", e);
            return ResultObject.failed("添加关键词回复内容失败: " + e.getMessage());
        }
    }

    @PostMapping("/updateContent")
    @RequirePermission("auto-reply:write")
    public ResultObject<?> updateContent(@RequestBody Map<String, Object> params) {
        try {
            Long contentId = Long.valueOf(params.get("contentId").toString());
            String replyText = params.get("replyText") != null ? params.get("replyText").toString() : null;
            String replyImageUrl = params.get("replyImageUrl") != null ? params.get("replyImageUrl").toString() : null;
            keywordReplyService.updateContent(contentId, replyText, replyImageUrl);
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("更新关键词回复内容失败", e);
            return ResultObject.failed("更新关键词回复内容失败: " + e.getMessage());
        }
    }

    @PostMapping("/deleteContent")
    @RequirePermission("auto-reply:write")
    public ResultObject<?> deleteContent(@RequestBody Map<String, Object> params) {
        try {
            Long contentId = Long.valueOf(params.get("contentId").toString());
            keywordReplyService.deleteContent(contentId);
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("删除关键词回复内容失败", e);
            return ResultObject.failed("删除关键词回复内容失败: " + e.getMessage());
        }
    }
}
