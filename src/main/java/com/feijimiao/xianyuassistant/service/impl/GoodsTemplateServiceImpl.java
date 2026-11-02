package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.controller.dto.PublishItemReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.PublishItemRespDTO;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsTemplate;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsTemplateMapper;
import com.feijimiao.xianyuassistant.service.GoodsTemplateService;
import com.feijimiao.xianyuassistant.service.PublishService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品发布模板服务实现（卡片F3：模板CRUD + CSV批量导入上架）
 */
@Slf4j
@Service
public class GoodsTemplateServiceImpl implements GoodsTemplateService {

    private static final int MAX_IMPORT_ROWS = 500;

    @Autowired
    private XianyuGoodsTemplateMapper templateMapper;

    @Autowired
    private PublishService publishService;

    @Override
    public XianyuGoodsTemplate save(XianyuGoodsTemplate template) {
        if (template == null || template.getTitle() == null || template.getTitle().trim().isEmpty()) {
            throw new IllegalArgumentException("模板标题不能为空");
        }
        template.setTitle(template.getTitle().trim());
        if (template.getPricePlaceholder() != null) {
            template.setPricePlaceholder(template.getPricePlaceholder().trim());
        }
        if (template.getId() == null) {
            template.setCreateTime(LocalDateTime.now());
            template.setUpdateTime(LocalDateTime.now());
            templateMapper.insert(template);
        } else {
            template.setUpdateTime(LocalDateTime.now());
            templateMapper.update(template);
        }
        return template;
    }

    @Override
    public Map<String, Object> page(int pageNum, int pageSize) {
        if (pageNum < 1) pageNum = 1;
        if (pageSize < 1 || pageSize > 200) pageSize = 20;
        int offset = (pageNum - 1) * pageSize;
        List<XianyuGoodsTemplate> list = templateMapper.selectPage(pageSize, offset);
        int total = templateMapper.countAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("totalCount", total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        return result;
    }

    @Override
    @Transactional
    public boolean delete(Long id) {
        if (id == null) {
            return false;
        }
        return templateMapper.deleteById(id) > 0;
    }

    @Override
    @Transactional
    public Map<String, Object> importCsv(String csvContent) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (csvContent == null || csvContent.trim().isEmpty()) {
            result.put("successCount", 0);
            result.put("failCount", 0);
            result.put("failures", new ArrayList<String>());
            return result;
        }
        String text = csvContent;
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\\r?\\n");
        int successCount = 0;
        int failCount = 0;
        List<String> failures = new ArrayList<>();
        int rowIndex = 0;
        for (String rawLine : lines) {
            rowIndex++;
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            // 跳过表头（第一行含任一表头关键字：标题/title/价格/price/描述/description/图片/image）
            String lower = line.toLowerCase();
            if (rowIndex == 1 && (lower.contains("标题") || lower.contains("title")
                    || lower.contains("价格") || lower.contains("price")
                    || lower.contains("描述") || lower.contains("description")
                    || lower.contains("图片") || lower.contains("image"))) {
                continue;
            }
            try {
                XianyuGoodsTemplate template = parseCsvLine(line);
                if (template == null) {
                    continue;
                }
                save(template);
                successCount++;
            } catch (Exception e) {
                failCount++;
                failures.add("第" + rowIndex + "行: " + e.getMessage());
            }
            if (successCount + failCount >= MAX_IMPORT_ROWS) {
                failures.add("已达到单次导入上限" + MAX_IMPORT_ROWS + "行，剩余行已跳过");
                break;
            }
        }
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("failures", failures);
        return result;
    }

    private XianyuGoodsTemplate parseCsvLine(String line) {
        String[] cols = splitCsvLine(line);
        String title = cols.length > 0 ? cols[0].trim() : "";
        if (title.isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        XianyuGoodsTemplate template = new XianyuGoodsTemplate();
        template.setTitle(title);
        template.setDescription(cols.length > 1 ? cols[1].trim() : null);
        template.setPricePlaceholder(cols.length > 2 ? cols[2].trim() : null);
        template.setImageUrls(cols.length > 3 ? cols[3].trim() : null);
        template.setCategoryId(cols.length > 4 ? cols[4].trim() : null);
        template.setRemark(cols.length > 5 ? cols[5].trim() : null);
        return template;
    }

    /**
     * 简单的CSV行拆分：支持双引号包裹的字段（含逗号/换行外的字符）。
     */
    private String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    @Override
    @Transactional
    public List<Map<String, Object>> batchPublish(Long accountId, List<Long> templateIds) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (accountId == null || templateIds == null || templateIds.isEmpty()) {
            return results;
        }
        for (Long templateId : templateIds) {
            Map<String, Object> itemResult = new LinkedHashMap<>();
            itemResult.put("templateId", templateId);
            try {
                XianyuGoodsTemplate template = templateMapper.selectById(templateId);
                if (template == null) {
                    itemResult.put("success", false);
                    itemResult.put("message", "模板不存在");
                    results.add(itemResult);
                    continue;
                }
                PublishItemReqDTO req = new PublishItemReqDTO();
                req.setAccountId(accountId);
                req.setTitle(template.getTitle());
                req.setDescription(template.getDescription());
                String priceText = template.getPricePlaceholder();
                if (priceText == null || priceText.trim().isEmpty()) {
                    itemResult.put("success", false);
                    itemResult.put("message", "模板未设置价格");
                    results.add(itemResult);
                    continue;
                }
                String cleaned = priceText.trim().replaceAll("[¥￥元]", "").trim();
                BigDecimal price;
                try {
                    price = new BigDecimal(cleaned);
                } catch (NumberFormatException e) {
                    itemResult.put("success", false);
                    itemResult.put("message", "价格不是数字: " + priceText);
                    results.add(itemResult);
                    continue;
                }
                if (price.signum() <= 0) {
                    itemResult.put("success", false);
                    itemResult.put("message", "价格必须大于0");
                    results.add(itemResult);
                    continue;
                }
                req.setPrice(price);
                req.setQuantity(1);
                List<String> images = splitImages(template.getImageUrls());
                if (!images.isEmpty()) {
                    req.setImages(images);
                }
                if (template.getCategoryId() != null && !template.getCategoryId().trim().isEmpty()) {
                    req.setCategoryId(template.getCategoryId().trim());
                }
                PublishItemRespDTO resp = publishService.publish(req);
                itemResult.put("success", resp.isSuccess());
                itemResult.put("itemId", resp.getItemId());
                itemResult.put("message", resp.getMessage());
                if (resp.isSuccess()) {
                    log.info("模板批量上架成功: templateId={}, itemId={}", templateId, resp.getItemId());
                }
            } catch (Exception e) {
                log.error("模板批量上架异常: templateId={}", templateId, e);
                itemResult.put("success", false);
                itemResult.put("message", e.getMessage());
            }
            results.add(itemResult);
        }
        return results;
    }

    private List<String> splitImages(String imageUrls) {
        List<String> result = new ArrayList<>();
        if (imageUrls == null || imageUrls.trim().isEmpty()) {
            return result;
        }
        for (String url : imageUrls.split("[,;；]")) {
            String trimmed = url.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}