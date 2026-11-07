package com.feijimiao.xianyuassistant.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import com.feijimiao.xianyuassistant.controller.dto.BatchOffShelfItemsRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.PublishItemReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.PublishItemRespDTO;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuPublishHistory;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuPublishHistoryMapper;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.PublishService;
import com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils;
import com.feijimiao.xianyuassistant.adapter.xianyu.XianyuAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 商品发布/下架服务实现。
 *
 * <p>接口契约参考 goofish-cli（mtop 链路）：</p>
 * <ul>
 *   <li>类目识别：{@code mtop.taobao.idle.kgraph.property.recommend} v2.0</li>
 *   <li>默认地址：{@code mtop.taobao.idle.local.poi.get} v1.0</li>
 *   <li>发布：{@code mtop.idle.pc.idleitem.publish} v1.0</li>
 *   <li>下架：{@code com.taobao.idle.item.delete} v1.1</li>
 * </ul>
 */
@Slf4j
@Service
public class PublishServiceImpl implements PublishService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private XianyuAdapter xianyuAdapter;

    @Autowired
    private AccountService accountService;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private XianyuPublishHistoryMapper publishHistoryMapper;

    @Override
    public List<PublishItemRespDTO.CategoryPredict> recommendCategory(Long accountId, String title,
                                                                      String description, List<String> images) {
        String cookie = requireCookie(accountId);
        Map<String, Object> data = new HashMap<>();
        data.put("title", title == null ? "" : title);
        data.put("description", description == null ? "" : description);
        data.put("lockCpv", false);
        data.put("multiSKU", false);
        data.put("publishScene", "mainPublish");
        data.put("scene", "newPublishChoice");
        data.put("uniqueCode", UUID.randomUUID().toString().replace("-", ""));
        if (images != null && !images.isEmpty()) {
            List<Map<String, String>> imageInfos = new ArrayList<>();
            for (String url : images) {
                Map<String, String> info = new HashMap<>();
                info.put("url", url);
                imageInfos.add(info);
            }
            data.put("imageInfos", imageInfos);
        }

        XianyuApiCallUtils.ApiCallResult result = xianyuAdapter.callApi(
                accountId,
                XianyuProtocolProfile.CATEGORY_RECOMMEND_API,
                data,
                cookie,
                XianyuProtocolProfile.CATEGORY_RECOMMEND_API_VERSION);

        if (!result.isSuccess()) {
            log.warn("【账号{}】AI类目识别失败: {}", accountId, result.getErrorMessage());
            return List.of();
        }

        return parseCategoryPredict(result.extractData());
    }

    @Override
    public PublishItemRespDTO publish(PublishItemReqDTO req) {
        PublishItemRespDTO resp = new PublishItemRespDTO();
        resp.setSuccess(false);
        resp.setPrice(req.getPrice());

        try {
            String cookie = requireCookie(req.getAccountId());

            // 1. AI 类目识别（未指定类目时自动推荐）
            String categoryId = req.getCategoryId();
            List<PublishItemRespDTO.CategoryPredict> categories = List.of();
            if (categoryId == null || categoryId.isBlank()) {
                categories = recommendCategory(
                        req.getAccountId(), req.getTitle(), req.getDescription(), req.getImages());
                if (!categories.isEmpty()) {
                    categoryId = categories.get(0).getCategoryId();
                    resp.setCategories(categories);
                }
            }

            // 仅返回类目识别结果供前端预览
            if (Boolean.TRUE.equals(req.getRecommendCategoryOnly())) {
                resp.setSuccess(true);
                resp.setMessage("类目识别完成");
                resp.setCategories(categories);
                return resp;
            }

            if (categoryId == null || categoryId.isBlank()) {
                resp.setMessage("类目识别失败，请手动指定类目");
                saveHistory(req, null, "PUBLISH", "FAILED", "类目识别失败，请手动指定类目", null);
                return resp;
            }

            // 2. 获取默认地址（可选，失败不阻塞发布）
            Map<String, Object> addr = fetchDefaultAddress(req.getAccountId(), cookie);

            // 3. 构建发布数据
            Map<String, Object> data = buildPublishData(req, categoryId, addr);

            // 4. 调用发布API
            XianyuApiCallUtils.ApiCallResult result = xianyuAdapter.callApi(
                    req.getAccountId(),
                    XianyuProtocolProfile.ITEM_PUBLISH_API,
                    data,
                    cookie,
                    XianyuProtocolProfile.ITEM_PUBLISH_API_VERSION);

            if (!result.isSuccess()) {
                String error = result.getErrorMessage();
                resp.setMessage(error);
                saveHistory(req, null, "PUBLISH", "FAILED", error, result.getResponse());
                return resp;
            }

            // 5. 提取发布成功的商品ID
            String itemId = extractItemId(result.extractData());
            resp.setSuccess(true);
            resp.setItemId(itemId);
            resp.setMessage("发布成功" + (itemId != null ? "，商品ID: " + itemId : ""));
            saveHistory(req, itemId, "PUBLISH", "SUCCESS", null, result.getResponse());
            return resp;

        } catch (Exception e) {
            log.error("【账号{}】发布商品异常: titleLength={}, errorType={}",
                    req.getAccountId(),
                    req.getTitle() != null ? req.getTitle().length() : 0,
                    e.getClass().getSimpleName());
            resp.setMessage("发布异常: " + e.getMessage());
            saveHistory(req, null, "PUBLISH", "FAILED", e.getMessage(), null);
            return resp;
        }
    }

    @Override
    public boolean offShelf(Long accountId, String itemId) {
        String cookie = requireCookie(accountId);
        Map<String, Object> data = new HashMap<>();
        data.put("itemId", itemId);

        XianyuApiCallUtils.ApiCallResult result = xianyuAdapter.callApi(
                accountId,
                XianyuProtocolProfile.ITEM_DELETE_API,
                data,
                cookie,
                XianyuProtocolProfile.ITEM_DELETE_API_VERSION);

        boolean success = result.isSuccess();
        saveOffShelfHistory(accountId, itemId, success,
                success ? null : result.getErrorMessage(), result.getResponse());
        return success;
    }

    @Override
    public BatchOffShelfItemsRespDTO batchOffShelf(Long accountId, List<String> itemIds) {
        List<BatchOffShelfItemsRespDTO.ItemOffShelfResult> details = new ArrayList<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return new BatchOffShelfItemsRespDTO(0, 0, 0, details);
        }

        int successCount = 0;
        int failedCount = 0;
        for (int i = 0; i < itemIds.size(); i++) {
            String itemId = itemIds.get(i);
            try {
                boolean ok = offShelf(accountId, itemId);
                if (ok) {
                    successCount++;
                } else {
                    failedCount++;
                }
                details.add(new BatchOffShelfItemsRespDTO.ItemOffShelfResult(
                        itemId, ok, ok ? "下架成功" : "下架失败，请查看发布历史确认原因"));
            } catch (Exception e) {
                failedCount++;
                details.add(new BatchOffShelfItemsRespDTO.ItemOffShelfResult(
                        itemId, false, "执行异常: " + e.getMessage()));
            }

            // 批处理间歇 1 秒，防平台触发频控
            if (i < itemIds.size() - 1) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return new BatchOffShelfItemsRespDTO(itemIds.size(), successCount, failedCount, details);
    }

    // ==================== 私有方法 ====================

    private String requireCookie(Long accountId) {
        String cookie = accountService.getCookieByAccountId(accountId);
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalArgumentException("账号Cookie为空，请先登录该账号");
        }
        return cookie;
    }

    /**
     * 获取默认地址（mtop.taobao.idle.local.poi.get v1.0）。
     * 返回 map 结构供 itemAddrDTO 使用；失败时返回空 map，不阻塞发布。
     */
    private Map<String, Object> fetchDefaultAddress(Long accountId, String cookie) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("longitude", "");
            data.put("latitude", "");
            XianyuApiCallUtils.ApiCallResult result = xianyuAdapter.callApi(
                    accountId,
                    XianyuProtocolProfile.LOCAL_POI_API,
                    data,
                    cookie,
                    XianyuProtocolProfile.LOCAL_POI_API_VERSION);
            if (!result.isSuccess()) {
                return new HashMap<>();
            }
            Map<String, Object> addrData = result.extractData();
            if (addrData == null) {
                return new HashMap<>();
            }
            // 优先取 selectedPoi，其次 commonAddresses[0]
            Object selected = addrData.get("selectedPoi");
            if (selected instanceof Map) {
                return new HashMap<>((Map<String, Object>) selected);
            }
            Object common = addrData.get("commonAddresses");
            if (common instanceof List && !((List<?>) common).isEmpty()) {
                Object first = ((List<?>) common).get(0);
                if (first instanceof Map) {
                    return new HashMap<>((Map<String, Object>) first);
                }
            }
            return new HashMap<>();
        } catch (Exception e) {
            log.warn("【账号{}】获取默认地址失败: {}", accountId, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 构建发布数据（参考 goofish-cli publish.py 的 _build_publish_data）。
     */
    private Map<String, Object> buildPublishData(PublishItemReqDTO req, String categoryId, Map<String, Object> addr) {
        Map<String, Object> data = new HashMap<>();

        // 商品文本
        Map<String, Object> itemTextDTO = new HashMap<>();
        itemTextDTO.put("title", req.getTitle());
        itemTextDTO.put("description", req.getDescription() == null ? "" : req.getDescription());
        data.put("itemTextDTO", itemTextDTO);

        // 价格
        Map<String, Object> itemPriceDTO = new HashMap<>();
        itemPriceDTO.put("price", req.getPrice() == null ? "0" : req.getPrice().toPlainString());
        data.put("itemPriceDTO", itemPriceDTO);

        // 运费
        Map<String, Object> itemPostFeeDTO = new HashMap<>();
        itemPostFeeDTO.put("postFee", req.getPostFee() == null ? "0" : req.getPostFee().toPlainString());
        data.put("itemPostFeeDTO", itemPostFeeDTO);

        // 地址（兜底空 map，平台会要求补充）
        data.put("itemAddrDTO", addr.isEmpty() ? new HashMap<>() : addr);

        // 类目
        Map<String, Object> itemCatDTO = new HashMap<>();
        itemCatDTO.put("catId", categoryId);
        data.put("itemCatDTO", itemCatDTO);

        // 图片
        List<Map<String, String>> imageInfoDOList = new ArrayList<>();
        if (req.getImages() != null) {
            for (String url : req.getImages()) {
                if (url != null && !url.isBlank()) {
                    Map<String, String> img = new HashMap<>();
                    img.put("url", url);
                    imageInfoDOList.add(img);
                }
            }
        }
        data.put("imageInfoDOList", imageInfoDOList);

        // 其他
        data.put("simpleItem", "true");
        data.put("quantity", String.valueOf(req.getQuantity() == null ? 1 : req.getQuantity()));
        return data;
    }

    private List<PublishItemRespDTO.CategoryPredict> parseCategoryPredict(Map<String, Object> data) {
        List<PublishItemRespDTO.CategoryPredict> result = new ArrayList<>();
        if (data == null) {
            return result;
        }
        try {
            JsonNode root = objectMapper.valueToTree(data);
            // 常见结构: { "categoryPredictResult": [...] } 或 { "predictResult": [...] } 或 data 直接为列表
            JsonNode predictions = root.has("categoryPredictResult")
                    ? root.get("categoryPredictResult")
                    : root.has("predictResult") ? root.get("predictResult") : null;
            if (predictions == null || !predictions.isArray()) {
                return result;
            }
            for (JsonNode node : predictions) {
                PublishItemRespDTO.CategoryPredict predict = new PublishItemRespDTO.CategoryPredict();
                JsonNode catId = node.has("categoryId") ? node.get("categoryId")
                        : node.has("catId") ? node.get("catId") : node.get("id");
                predict.setCategoryId(catId != null ? catId.asText() : null);
                JsonNode name = node.has("categoryName") ? node.get("categoryName")
                        : node.has("name") ? node.get("name") : node.get("title");
                predict.setCategoryName(name != null ? name.asText() : null);
                JsonNode score = node.get("score");
                if (score != null) {
                    predict.setScore(score.asDouble());
                }
                if (predict.getCategoryId() != null && !predict.getCategoryId().isBlank()) {
                    result.add(predict);
                }
            }
        } catch (Exception e) {
            log.warn("解析类目识别结果失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 从发布响应中提取商品ID（兼容多种返回结构）。
     */
    private String extractItemId(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Object direct = data.get("itemId");
        if (direct != null) {
            return String.valueOf(direct);
        }
        Object id = data.get("id");
        if (id != null) {
            return String.valueOf(id);
        }
        Object result = data.get("result");
        if (result instanceof Map) {
            Object resultItemId = ((Map<?, ?>) result).get("itemId");
            if (resultItemId != null) {
                return String.valueOf(resultItemId);
            }
        }
        return null;
    }

    private void saveHistory(PublishItemReqDTO req, String itemId, String operationType,
                             String status, String errorMessage, String rawResponse) {
        try {
            XianyuPublishHistory history = new XianyuPublishHistory();
            history.setAccountId(req.getAccountId());
            history.setTitle(req.getTitle());
            history.setDescription(req.getDescription());
            history.setPrice(req.getPrice());
            history.setQuantity(req.getQuantity() == null ? 1 : req.getQuantity());
            history.setImageCount(req.getImages() == null ? 0 : req.getImages().size());
            history.setCategoryId(req.getCategoryId());
            history.setItemId(itemId);
            history.setOperationType(operationType);
            history.setStatus(status);
            history.setErrorMessage(truncate(errorMessage, 500));
            history.setRawResponse(truncate(rawResponse, 2000));
            history.setCreatedTime(LocalDateTime.now());
            publishHistoryMapper.insert(history);
        } catch (Exception e) {
            log.warn("保存发布历史失败: {}", e.getMessage());
        }
    }

    private void saveOffShelfHistory(Long accountId, String itemId, boolean success,
                                     String errorMessage, String rawResponse) {
        try {
            XianyuAccount account = accountMapper.selectById(accountId);
            XianyuPublishHistory history = new XianyuPublishHistory();
            history.setAccountId(accountId);
            history.setAccountNote(account != null ? account.getAccountNote() : null);
            history.setItemId(itemId);
            history.setOperationType("OFF_SHELF");
            history.setStatus(success ? "SUCCESS" : "FAILED");
            history.setErrorMessage(truncate(errorMessage, 500));
            history.setRawResponse(truncate(rawResponse, 2000));
            history.setCreatedTime(LocalDateTime.now());
            publishHistoryMapper.insert(history);
        } catch (Exception e) {
            log.warn("保存下架历史失败: {}", e.getMessage());
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
