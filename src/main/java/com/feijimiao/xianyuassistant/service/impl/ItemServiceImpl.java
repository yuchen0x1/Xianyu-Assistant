package com.feijimiao.xianyuassistant.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.*;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsSku;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsSkuProperty;
import com.feijimiao.xianyuassistant.service.ItemService;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.utils.XianyuApiUtils;
import com.feijimiao.xianyuassistant.utils.XianyuSignUtils;
import com.feijimiao.xianyuassistant.utils.ItemDetailUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 商品服务实现类
 */
@Slf4j
@Service
public class ItemServiceImpl implements ItemService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.AccountService accountService;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.GoodsInfoService goodsInfoService;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.GoodsSkuService goodsSkuService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.GoodsSkuPropertyService goodsSkuPropertyService;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.AutoDeliveryService autoDeliveryService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.ItemDetailSyncService itemDetailSyncService;

    @Autowired
    private com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;

    @Autowired
    private com.feijimiao.xianyuassistant.adapter.xianyu.XianyuAdapter xianyuAdapter;

    @Autowired
    private com.feijimiao.xianyuassistant.rbac.service.RbacService rbacService;

    @Autowired
    private com.feijimiao.xianyuassistant.mapper.XianyuGoodsInfoMapper goodsInfoMapper;

    /**
     * 获取指定页的商品信息（内部方法）
     */
    private ResultObject<ItemListRespDTO> getItemList(ItemListReqDTO reqDTO) {
        try {
            log.info("开始获取商品列表: pageNumber={}, pageSize={}",
                    reqDTO.getPageNumber(), reqDTO.getPageSize());

            // 从数据库获取Cookie
            String cookiesStr = getCookieFromDb(reqDTO.getCookieId());
            if (cookiesStr == null || cookiesStr.isEmpty()) {
                log.error("未找到账号Cookie: cookieRefLength={}",
                        reqDTO.getCookieId() != null ? reqDTO.getCookieId().length() : 0);
                return ResultObject.failed("未找到账号Cookie");
            }
            log.info("Cookie获取成功，长度: {}", cookiesStr.length());

            // 检查Cookie中是否包含必需的token
            Map<String, String> cookies = XianyuSignUtils.parseCookies(cookiesStr);
            if (!cookies.containsKey("_m_h5_tk") || cookies.get("_m_h5_tk").isEmpty()) {
                log.error("Cookie中缺少_m_h5_tk字段！请重新登录");
                return ResultObject.failed("Cookie中缺少_m_h5_tk，请重新登录");
            }
            
            Long accountId = getAccountIdFromCookieId(reqDTO.getCookieId());
            if (accountId == null) {
                log.error("无法解析商品同步账号: cookieRefLength={}",
                        reqDTO.getCookieId() != null ? reqDTO.getCookieId().length() : 0);
                return ResultObject.failed("无法识别商品同步账号");
            }

            // 商品列表必须带有效 userId。增量 Cookie 有时只保留登录令牌而不再携带 unb，
            // 此时回退到扫码阶段已验证并持久化的账号身份，保持与 WebSocket 连接链一致。
            String authenticatedUserId = cookies.get("unb");
            if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
                authenticatedUserId = accountService.getXianyuUserId(accountId);
                log.info("Cookie缺少unb，使用已验证账号身份请求商品列表: accountId={}, hasFallback={}",
                        accountId, authenticatedUserId != null && !authenticatedUserId.isBlank());
            }

            // 个人主页商品接口优先使用服务端下发的 kcUserId；解析失败时必须保留已验证的用户ID，
            // 不允许把 null 传入接口，否则服务端会返回 FAIL_BIZ_BAD_REQUEST。
            String goodsUserId = resolveGoodsUserId(accountId, cookiesStr, authenticatedUserId);
            if (goodsUserId == null || goodsUserId.isBlank()) {
                log.error("商品同步缺少已验证用户ID: accountId={}", accountId);
                return ResultObject.failed("商品同步缺少账号身份，请重新扫码登录");
            }
            String latestCookie = accountService.getCookieByAccountId(accountId);
            if (latestCookie != null && !latestCookie.isBlank()) {
                cookiesStr = latestCookie;
            }

            // 构建请求数据。首屏只提交用户、页码和分组元数据开关；
            // 后续页仅透传服务端下发的游标和分组字段，避免恢复历史硬编码分组。
            Map<String, Object> dataMap = new LinkedHashMap<>();
            dataMap.put("needGroupInfo", reqDTO.getPageNumber() == 1);
            dataMap.put("pageNumber", reqDTO.getPageNumber());
            dataMap.put("userId", goodsUserId);
            dataMap.put("pageSize", reqDTO.getPageSize());

            if (reqDTO.getPageNumber() != null && reqDTO.getPageNumber() > 1) {
                Map<String, Object> groupContext = reqDTO.getGroupContext();
                if (groupContext != null) {
                    for (String key : List.of("groupName", "groupId", "defaultGroup", "groupSortId", "filterPanelGroupId")) {
                        Object value = groupContext.get(key);
                        if (value != null) {
                            dataMap.put(key, value);
                        }
                    }
                }
                if (reqDTO.getNextPageModel() != null) {
                    dataMap.put("nextPageModel", reqDTO.getNextPageModel());
                }
                if (reqDTO.getNextPageNum() != null) {
                    dataMap.put("nextPageNum", reqDTO.getNextPageNum());
                }
            }

            log.info("调用商品列表API: accountId={}, pageNumber={}, pageSize={}, officialUserIdResolved={}",
                    accountId, reqDTO.getPageNumber(), reqDTO.getPageSize(), goodsUserId != null && !goodsUserId.isBlank());

            // 使用统一的带 Set-Cookie 吸收与令牌过期重试机制调用商品列表接口。
            // 旧实现只返回响应体，会丢失服务端下发的新 _m_h5_tk，导致首次令牌过期后永久同步失败。
            com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils.ApiCallResult apiResult =
                    callPersonalPageApi(accountId, "mtop.idle.web.xyh.item.list", dataMap, cookiesStr);
            String response = apiResult.getResponse();

            if (!apiResult.isSuccess() || response == null) {
                String apiErrorMessage = apiResult.getErrorMessage();
                log.error("商品列表API调用失败: accountId={}, failureCategory={}",
                        accountId, describeApiFailure(apiErrorMessage));
                return ResultObject.failed(apiErrorMessage != null
                        ? apiErrorMessage
                        : "请求闲鱼API失败");
            }
            
            log.info("API调用成功，响应长度: {}", response.length());
            log.info("API响应已接收: responseLength={}", response.length());

            // 解析响应
            log.info("开始解析响应JSON...");
            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
            log.info("JSON解析成功，响应字段数: {}", responseMap.size());
            
            ItemListRespDTO respDTO = parseItemListResponse(responseMap, reqDTO.getPageNumber(), reqDTO.getPageSize());
            log.info("响应解析完成，success={}, 商品数量={}", respDTO.getSuccess(), respDTO.getCurrentCount());
            
            if (respDTO.getSuccess()) {
                log.info("获取商品列表成功: cookieRefLength={}, 商品数量={}",
                        reqDTO.getCookieId() != null ? reqDTO.getCookieId().length() : 0,
                        respDTO.getCurrentCount());
                
                // 单页请求只负责远端读取与解析。完整刷新统一提交本地状态，
                // 这样后续页失败时不会把不完整远端集合误当作下架快照。
                
                return ResultObject.success(respDTO);
            } else {
                log.error("获取商品列表失败: success=false");
                return ResultObject.failed("获取商品列表失败");
            }
        } catch (com.feijimiao.xianyuassistant.exception.BusinessException e) {
            log.error("业务异常: cookieRefLength={}, code={}",
                    reqDTO.getCookieId() != null ? reqDTO.getCookieId().length() : 0, e.getCode());
            
            // 如果是令牌过期异常，更新cookie状态
            if (e.getMessage().contains("令牌已过期")) {
                try {
                    Long accountId = getAccountIdFromCookieId(reqDTO.getCookieId());
                    if (accountId != null) {
                        accountService.updateCookieStatus(accountId, 2); // 2表示过期
                        log.info("已更新Cookie状态为过期: accountId={}", accountId);
                    }
                } catch (Exception ex) {
                    log.error("更新Cookie状态失败: cookieRefLength={}, errorType={}",
                            reqDTO.getCookieId() != null ? reqDTO.getCookieId().length() : 0,
                            ex.getClass().getSimpleName());
                }
            }
            
            // 重新抛出业务异常，让全局异常处理器处理
            throw e;
        } catch (Exception e) {
            log.error("获取商品列表异常: cookieRefLength={}, errorType={}",
                    reqDTO.getCookieId() != null ? reqDTO.getCookieId().length() : 0,
                    e.getClass().getSimpleName());
            return ResultObject.failed("获取商品列表异常: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<RefreshItemsRespDTO> refreshItems(AllItemsReqDTO reqDTO) {
        try {
            log.info("开始刷新商品数据: xianyuAccountId={}", reqDTO.getXianyuAccountId());

            if (reqDTO.getXianyuAccountId() == null) {
                log.error("账号ID不能为空");
                return ResultObject.failed("账号ID不能为空");
            }

            String cookieStr = accountService.getCookieByAccountId(reqDTO.getXianyuAccountId());
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("未找到账号Cookie: xianyuAccountId={}", reqDTO.getXianyuAccountId());
                return ResultObject.failed("未找到账号Cookie，请先登录");
            }

            int pageSize = reqDTO.getPageSize() != null && reqDTO.getPageSize() > 0
                    ? reqDTO.getPageSize() : 20;
            RefreshItemsRespDTO respDTO = new RefreshItemsRespDTO();
            respDTO.setSuccess(false);
            respDTO.setUpdatedItemIds(new ArrayList<>());

            // 按商品 ID 去重并保持响应顺序，避免动态分组或分页重叠导致重复落库。
            Map<String, ItemDTO> itemsById = new LinkedHashMap<>();
            boolean remoteSnapshotComplete = false;
            Integer failedPageNumber = null;
            int pageNumber = 1;
            Object nextPageModel = null;
            Object nextPageNum = null;
            Map<String, Object> groupContext = null;

            while (true) {
                if (reqDTO.getMaxPages() != null && reqDTO.getMaxPages() > 0 && pageNumber > reqDTO.getMaxPages()) {
                    failedPageNumber = pageNumber;
                    log.warn("商品同步达到最大页数限制，当前远端快照不完整: maxPages={}", reqDTO.getMaxPages());
                    break;
                }

                ItemListReqDTO pageReqDTO = new ItemListReqDTO();
                pageReqDTO.setCookieId(String.valueOf(reqDTO.getXianyuAccountId()));
                pageReqDTO.setPageNumber(pageNumber);
                pageReqDTO.setPageSize(pageSize);
                pageReqDTO.setNextPageModel(nextPageModel);
                pageReqDTO.setNextPageNum(nextPageNum);
                pageReqDTO.setGroupContext(groupContext);

                ResultObject<ItemListRespDTO> pageResult = getItemList(pageReqDTO);
                if (pageResult.getCode() != 200 || pageResult.getData() == null
                        || !Boolean.TRUE.equals(pageResult.getData().getSuccess())) {
                    failedPageNumber = pageNumber;
                    log.error("商品同步第{}页失败，已获得商品仅做增量更新", pageNumber);
                    if (pageNumber == 1) {
                        return ResultObject.failed(pageResult.getMsg() != null ? pageResult.getMsg() : "获取商品列表失败");
                    }
                    break;
                }

                ItemListRespDTO pageData = pageResult.getData();
                List<ItemDTO> pageItems = pageData.getItems() != null ? pageData.getItems() : Collections.emptyList();
                for (ItemDTO item : pageItems) {
                    String itemId = extractItemId(item);
                    if (itemId == null) {
                        log.warn("商品分页响应含缺少商品ID的记录，已跳过: pageNumber={}", pageNumber);
                        continue;
                    }
                    itemsById.put(itemId, item);
                }
                log.info("第{}页解析到{}个商品，去重后累计{}个", pageNumber, pageItems.size(), itemsById.size());

                // 官方 nextPage 是分页终点的权威标志；仅在缺失时兼容旧接口的短页结束语义。
                if (Boolean.FALSE.equals(pageData.getNextPage())) {
                    remoteSnapshotComplete = true;
                    break;
                }
                if (Boolean.TRUE.equals(pageData.getNextPage())) {
                    nextPageModel = pageData.getNextPageModel();
                    nextPageNum = pageData.getNextPageNum();
                    groupContext = selectDynamicGroupContext(pageData.getItemGroupList());
                    pageNumber++;
                    com.feijimiao.xianyuassistant.utils.HumanLikeDelayUtils.pageScrollDelay();
                    continue;
                }
                if (pageItems.size() < pageSize) {
                    // 中文注释：旧响应没有 nextPage 时，短页仍可作为兼容性终点；满页缺少游标则保守停止。
                    remoteSnapshotComplete = true;
                    break;
                }

                failedPageNumber = pageNumber;
                log.warn("第{}页响应缺少分页游标且为满页，停止后续请求并跳过下架处理", pageNumber);
                break;
            }

            List<ItemDTO> allItems = new ArrayList<>(itemsById.values());
            respDTO.setTotalCount(allItems.size());
            Long accountId = reqDTO.getXianyuAccountId();

            // 即使快照不完整，已获得的商品仍可安全做增量保存；下架状态只接受完整远端快照。
            for (ItemDTO item : allItems) {
                String itemId = extractItemId(item);
                try {
                    if (goodsInfoService.saveOrUpdateGoodsInfo(item, accountId) && itemId != null) {
                        respDTO.getUpdatedItemIds().add(itemId);
                    }
                } catch (Exception e) {
                    log.error("保存商品失败: itemId={}", itemId, e);
                }
            }

            if (remoteSnapshotComplete) {
                Set<String> remoteItemIds = new HashSet<>(itemsById.keySet());
                goodsInfoService.markOfflineIfNotInRemote(accountId, remoteItemIds);
            } else {
                log.warn("本次商品同步未形成完整远端快照，跳过下架处理: accountId={}, failedPage={}",
                        accountId, failedPageNumber);
            }

            respDTO.setSuccessCount(respDTO.getUpdatedItemIds().size());
            if (!allItems.isEmpty()) {
                String syncId = itemDetailSyncService.startSync(accountId, allItems);
                respDTO.setSyncId(syncId);
            }

            if (remoteSnapshotComplete) {
                respDTO.setSuccess(true);
                respDTO.setMessage(allItems.isEmpty() ? "同步成功：远端当前没有商品，已更新本地下架状态" : "刷新成功");
            } else if (!allItems.isEmpty()) {
                respDTO.setMessage("部分同步完成：已更新已获取商品，未修改本地下架状态");
            } else {
                respDTO.setMessage("未取得完整远端商品快照，未修改本地下架状态");
            }

            log.info("刷新商品数据结束: accountId={}, totalCount={}, successCount={}, complete={}, failedPage={}",
                    accountId, respDTO.getTotalCount(), respDTO.getSuccessCount(), remoteSnapshotComplete, failedPageNumber);
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("刷新商品数据异常: xianyuAccountId={}", reqDTO.getXianyuAccountId(), e);
            return ResultObject.failed("刷新商品数据异常: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<ItemListFromDbRespDTO> getItemsFromDb(ItemListFromDbReqDTO reqDTO) {
        try {
            log.info("从数据库获取商品列表: onlyOnSale={}, xianyuAccountId={}, pageNum={}, pageSize={}", 
                    reqDTO.getOnlyOnSale(), reqDTO.getXianyuAccountId(), reqDTO.getPageNum(), reqDTO.getPageSize());
            
            // 获取分页参数
            int pageSize = reqDTO.getPageSize() != null ? reqDTO.getPageSize() : 20;
            int pageNum = reqDTO.getPageNum() != null ? reqDTO.getPageNum() : 1;
            
            // 确保页码有效
            if (pageNum < 1) {
                pageNum = 1;
            }
            
            Integer statusFilter = reqDTO.getStatus();
            boolean onlyOnSale = statusFilter == null
                    && (reqDTO.getOnlyOnSale() == null || reqDTO.getOnlyOnSale());
            Integer queryStatus = statusFilter != null ? statusFilter : onlyOnSale ? 0 : null;
            Long accountId = reqDTO.getXianyuAccountId();
            Long userId = UserContext.getUserId();
            if (accountId != null) {
                rbacService.assertAccountVisible(userId, accountId);
            }
            List<Long> visibleAccountIds = rbacService.getCommandVisibleAccountIds(userId);
            int totalCount = goodsInfoMapper.countByAccountScope(queryStatus, accountId, visibleAccountIds);
            
            // 计算总页数
            int totalPage = (int) Math.ceil((double) totalCount / pageSize);
            
            // 如果总页数为0，设置为1
            if (totalPage == 0) {
                totalPage = 1;
            }
            
            // 确保页码不超过总页数
            if (pageNum > totalPage && totalPage > 0) {
                pageNum = totalPage;
            }
            
            // 获取当前页的商品列表，账号范围在 SQL 层过滤
            int offset = (pageNum - 1) * pageSize;
            List<XianyuGoodsInfo> pagedItems = goodsInfoMapper.selectByAccountScope(
                    queryStatus, accountId, visibleAccountIds, offset, pageSize);
            
            // 如果分页查询结果为null，创建空列表
            if (pagedItems == null) {
                pagedItems = new ArrayList<>();
            }
            
            // 为每个商品添加配置信息
            List<ItemWithConfigDTO> itemsWithConfig = new ArrayList<>();
            for (XianyuGoodsInfo item : pagedItems) {
                ItemWithConfigDTO itemWithConfig = new ItemWithConfigDTO();
                itemWithConfig.setItem(item);
                
                // 获取商品配置
                if (item.getXianyuAccountId() != null) {
                    com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig config = 
                            autoDeliveryService.getGoodsConfig(item.getXianyuAccountId(), item.getXyGoodId());
                    
                    if (config != null) {
                        itemWithConfig.setXianyuAutoDeliveryOn(config.getXianyuAutoDeliveryOn());
                        itemWithConfig.setXianyuAutoReplyOn(config.getXianyuAutoReplyOn());
                        itemWithConfig.setXianyuAutoReplyContextOn(config.getXianyuAutoReplyContextOn());
                        itemWithConfig.setXianyuKeywordReplyOn(config.getXianyuKeywordReplyOn());
                        itemWithConfig.setHumanInterventionOn(config.getHumanInterventionOn());
                        itemWithConfig.setHumanInterventionMinutes(config.getHumanInterventionMinutes());
                itemWithConfig.setReplyManualConfirmOn(config.getReplyManualConfirmOn());
                        itemWithConfig.setBargainOn(config.getBargainOn());
                        itemWithConfig.setBargainFloorPrice(config.getBargainFloorPrice());
                        itemWithConfig.setBargainStepPrice(config.getBargainStepPrice());
                        itemWithConfig.setBargainMaxRounds(config.getBargainMaxRounds());
                    } else {
                        itemWithConfig.setXianyuAutoDeliveryOn(0);
                        itemWithConfig.setXianyuAutoReplyOn(0);
                        itemWithConfig.setXianyuAutoReplyContextOn(0);
                        itemWithConfig.setXianyuKeywordReplyOn(0);
                        itemWithConfig.setHumanInterventionOn(0);
                        itemWithConfig.setHumanInterventionMinutes(10);
                        itemWithConfig.setReplyManualConfirmOn(0);
                        itemWithConfig.setBargainOn(0);
                        itemWithConfig.setBargainMaxRounds(3);
                    }

                    // 获取自动发货配置
                    com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig deliveryConfig =
                            autoDeliveryService.getAutoDeliveryConfig(item.getXianyuAccountId(), item.getXyGoodId());

                    if (deliveryConfig != null) {
                        itemWithConfig.setAutoDeliveryType(deliveryConfig.getDeliveryMode());
                        itemWithConfig.setAutoDeliveryContent(deliveryConfig.getAutoDeliveryContent());
                    }
                } else {
                    itemWithConfig.setXianyuAutoDeliveryOn(0);
                    itemWithConfig.setXianyuAutoReplyOn(0);
                    itemWithConfig.setXianyuAutoReplyContextOn(0);
                    itemWithConfig.setXianyuKeywordReplyOn(0);
                    itemWithConfig.setHumanInterventionOn(0);
                    itemWithConfig.setHumanInterventionMinutes(10);
                        itemWithConfig.setReplyManualConfirmOn(0);
                    itemWithConfig.setBargainOn(0);
                    itemWithConfig.setBargainMaxRounds(3);
                }
                
                itemsWithConfig.add(itemWithConfig);
            }
            
            ItemListFromDbRespDTO respDTO = new ItemListFromDbRespDTO();
            respDTO.setItemsWithConfig(itemsWithConfig);
            respDTO.setTotalCount(totalCount);
            respDTO.setPageNum(pageNum);
            respDTO.setPageSize(pageSize);
            respDTO.setTotalPage(totalPage);
            
            // 添加调试日志
            log.info("分页信息: totalCount={}, pageNum={}, pageSize={}, totalPage={}", 
                    totalCount, pageNum, pageSize, totalPage);
            
            log.info("从数据库获取商品列表成功: 总数={}, 当前页={}, 每页={}, 总页数={}", 
                    totalCount, pageNum, pageSize, totalPage);
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("从数据库获取商品列表失败", e);
            return ResultObject.failed("获取商品列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<ItemDetailRespDTO> getItemDetail(ItemDetailReqDTO reqDTO) {
        try {
            log.info("获取商品详情: xyGoodId={}, cookieRefLength={}", reqDTO.getXyGoodId(),
                    reqDTO.getCookieId() != null ? reqDTO.getCookieId().length() : 0);
            
            // 1. 从数据库获取商品基本信息
            XianyuGoodsInfo item = goodsInfoService.getByXyGoodId(reqDTO.getXyGoodId());
            
            if (item == null) {
                return ResultObject.failed("商品不存在");
            }

            // 商品按 xyGoodId 直达、自定义 cookieId 可借用任意账号身份调闲鱼 API，
            // 这里按当前用户可见范围兜底：受限用户不允许指定 cookieId，固定用商品关联账号。
            Long userId = com.feijimiao.xianyuassistant.context.UserContext.getUserId();
            rbacService.assertAccountVisible(userId, item.getXianyuAccountId());
            if (reqDTO.getCookieId() != null && !reqDTO.getCookieId().isBlank()) {
                rbacService.assertAccountVisible(userId, parseAccountIdOrNull(reqDTO.getCookieId()));
            }
            
            // 2. 判断是否需要获取详情
            boolean needFetchDetail = false;
            
            // 2.1 如果 detail_info 为空，必须获取
            if (item.getDetailInfo() == null || item.getDetailInfo().isEmpty()) {
                log.info("商品详情为空，需要获取: xyGoodId={}", reqDTO.getXyGoodId());
                needFetchDetail = true;
            }
            // 2.2 如果提供了 cookieId，也尝试获取/更新
            else if (reqDTO.getCookieId() != null && !reqDTO.getCookieId().isEmpty()) {
                log.info("提供了cookieId，尝试更新商品详情: xyGoodId={}", reqDTO.getXyGoodId());
                needFetchDetail = true;
            }
            
            // 3. 如果需要获取详情
            if (needFetchDetail) {
                // 3.1 确定使用哪个cookieId
                String cookieIdToUse = reqDTO.getCookieId();
                
                // 3.2 如果没有提供 cookieId，尝试从商品的 xianyu_account_id 获取
                if (cookieIdToUse == null || cookieIdToUse.isEmpty()) {
                    if (item.getXianyuAccountId() != null) {
                        // 使用商品关联的账号ID
                        cookieIdToUse = String.valueOf(item.getXianyuAccountId());
                        log.info("使用商品关联的账号ID获取详情: xyGoodId={}, accountId={}", 
                                reqDTO.getXyGoodId(), item.getXianyuAccountId());
                    } else {
                        log.warn("商品未关联账号且未提供cookieId，无法获取详情: xyGoodId={}", reqDTO.getXyGoodId());
                        ItemDetailRespDTO respDTO = new ItemDetailRespDTO();
                        respDTO.setItemWithConfig(buildItemWithConfig(item));
                        return ResultObject.failed("商品详情为空，且商品未关联账号，请提供cookieId参数以获取详情");
                    }
                }
                
                // 3.2 调用API获取详情
                try {
                    String detailInfo = fetchItemDetailFromApi(reqDTO.getXyGoodId(), cookieIdToUse);
                    
                    if (detailInfo != null && !detailInfo.isEmpty()) {
                        // 更新数据库中的详情信息
                        goodsInfoService.updateDetailInfo(reqDTO.getXyGoodId(), detailInfo);
                        item.setDetailInfo(detailInfo);
                        log.info("商品详情已更新: xyGoodId={}", reqDTO.getXyGoodId());
                    } else {
                        log.warn("未能获取到商品详情: xyGoodId={}", reqDTO.getXyGoodId());
                    }
                } catch (Exception e) {
                    log.error("获取商品详情失败，返回数据库中的信息: xyGoodId={}", reqDTO.getXyGoodId(), e);
                    // 即使获取详情失败，也返回数据库中的基本信息
                }
            }
            
            ItemDetailRespDTO respDTO = new ItemDetailRespDTO();
            respDTO.setItemWithConfig(buildItemWithConfig(item));
            
            log.info("获取商品详情成功: xyGoodId={}", reqDTO.getXyGoodId());
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取商品详情失败: xyGoodId={}", reqDTO.getXyGoodId(), e);
            return ResultObject.failed("获取商品详情失败: " + e.getMessage());
        }
    }
    
    /**
     * 构建包含配置的商品信息
     */
    private ItemWithConfigDTO buildItemWithConfig(XianyuGoodsInfo item) {
        ItemWithConfigDTO itemWithConfig = new ItemWithConfigDTO();
        itemWithConfig.setItem(item);
        
        // 获取商品配置
        if (item.getXianyuAccountId() != null) {
            com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig config = 
                    autoDeliveryService.getGoodsConfig(item.getXianyuAccountId(), item.getXyGoodId());
            
            if (config != null) {
                itemWithConfig.setXianyuAutoDeliveryOn(config.getXianyuAutoDeliveryOn());
                itemWithConfig.setXianyuAutoReplyOn(config.getXianyuAutoReplyOn());
                itemWithConfig.setXianyuAutoReplyContextOn(config.getXianyuAutoReplyContextOn() != null ? config.getXianyuAutoReplyContextOn() : 1);
                itemWithConfig.setXianyuKeywordReplyOn(config.getXianyuKeywordReplyOn());
                itemWithConfig.setHumanInterventionOn(config.getHumanInterventionOn());
                itemWithConfig.setHumanInterventionMinutes(config.getHumanInterventionMinutes());
                itemWithConfig.setReplyManualConfirmOn(config.getReplyManualConfirmOn());
                itemWithConfig.setBargainOn(config.getBargainOn());
                itemWithConfig.setBargainFloorPrice(config.getBargainFloorPrice());
                itemWithConfig.setBargainStepPrice(config.getBargainStepPrice());
                itemWithConfig.setBargainMaxRounds(config.getBargainMaxRounds());
            } else {
                itemWithConfig.setXianyuAutoDeliveryOn(0);
                itemWithConfig.setXianyuAutoReplyOn(0);
                itemWithConfig.setXianyuAutoReplyContextOn(1);
                itemWithConfig.setXianyuKeywordReplyOn(0);
                itemWithConfig.setHumanInterventionOn(0);
                itemWithConfig.setHumanInterventionMinutes(10);
                        itemWithConfig.setReplyManualConfirmOn(0);
                itemWithConfig.setBargainOn(0);
                itemWithConfig.setBargainMaxRounds(3);
            }
            
            // 获取自动发货配置
            com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig deliveryConfig = 
                    autoDeliveryService.getAutoDeliveryConfig(item.getXianyuAccountId(), item.getXyGoodId());
            
            if (deliveryConfig != null) {
                itemWithConfig.setAutoDeliveryType(deliveryConfig.getDeliveryMode());
                itemWithConfig.setAutoDeliveryContent(deliveryConfig.getAutoDeliveryContent());
            }
        } else {
            itemWithConfig.setXianyuAutoDeliveryOn(0);
            itemWithConfig.setXianyuAutoReplyOn(0);
            itemWithConfig.setXianyuAutoReplyContextOn(1);
            itemWithConfig.setXianyuKeywordReplyOn(0);
            itemWithConfig.setHumanInterventionOn(0);
            itemWithConfig.setHumanInterventionMinutes(10);
                        itemWithConfig.setReplyManualConfirmOn(0);
        }
        
        return itemWithConfig;
    }
    
    /**
     * 从闲鱼API获取商品详情
     * 实现流程：
     * 1. 检查缓存（24小时内的详情不重复获取）
     * 2. 首选：通过闲鱼API mtop.taobao.idle.pc.detail 获取
     * 3. 备选：如果API失败，可以考虑使用浏览器访问（需要额外实现）
     *
     * @param itemId 商品ID
     * @param cookieId Cookie ID
     * @return 商品详情JSON字符串
     */
    private String fetchItemDetailFromApi(String itemId, String cookieId) {
        try {
            log.info("开始获取商品详情: itemId={}, cookieRefLength={}", itemId,
                    cookieId != null ? cookieId.length() : 0);
            
            // 1. 检查缓存：如果数据库中已有详情且在24小时内，直接返回
            XianyuGoodsInfo cachedItem = goodsInfoService.getByXyGoodId(itemId);
            if (cachedItem != null && cachedItem.getDetailInfo() != null && !cachedItem.getDetailInfo().isEmpty()) {
                // 检查更新时间是否在24小时内
                if (isDetailInfoFresh(cachedItem.getUpdatedTime())) {
                    log.info("使用缓存的商品详情: itemId={}, 缓存时间={}", itemId, cachedItem.getUpdatedTime());
                    return cachedItem.getDetailInfo();
                } else {
                    log.info("缓存的商品详情已过期，重新获取: itemId={}", itemId);
                }
            } else {
                log.info("数据库中没有商品详情缓存，需要调用API获取: itemId={}", itemId);
            }
            
            // 2. 从数据库获取Cookie
            String cookiesStr = getCookieFromDb(cookieId);
            if (cookiesStr == null || cookiesStr.isEmpty()) {
                log.error("未找到账号Cookie: cookieRefLength={}",
                        cookieId != null ? cookieId.length() : 0);
                return null;
            }
            
            log.info("Cookie获取成功，准备调用API: itemId={}", itemId);
            
            // 3. 首选方式：通过闲鱼API获取商品详情
            Long accountId = getAccountIdFromCookieId(cookieId);
            if (accountId == null) {
                log.error("无法解析商品详情所属账号: itemId={}", itemId);
                return null;
            }
            String detailJson = fetchDetailFromApi(itemId, cookiesStr, accountId);
            
            if (detailJson != null && !detailJson.isEmpty()) {
                log.info("通过API获取商品详情成功: itemId={}, 详情长度={}", itemId, detailJson.length());
                return detailJson;
            }
            
            // 4. 备选方式：通过浏览器访问获取（暂未实现）
            log.warn("API获取商品详情失败，备选方式（浏览器访问）暂未实现: itemId={}", itemId);
            
            // 如果有缓存的详情（即使过期），也返回它
            if (cachedItem != null && cachedItem.getDetailInfo() != null && !cachedItem.getDetailInfo().isEmpty()) {
                log.info("返回过期的缓存详情: itemId={}", itemId);
                return cachedItem.getDetailInfo();
            }
            
            log.error("无法获取商品详情，且没有可用的缓存: itemId={}", itemId);
            return null;
            
        } catch (Exception e) {
            log.error("获取商品详情异常: itemId={}", itemId, e);
            return null;
        }
    }
    
    /**
     * 通过闲鱼API获取商品详情
     *
     * @param itemId 商品ID
     * @param cookiesStr Cookie字符串
     * @param accountId 账号ID，用于吸收 Set-Cookie 并在令牌过期时刷新重试
     * @return 商品详情JSON字符串
     */
    private String fetchDetailFromApi(String itemId, String cookiesStr, Long accountId) {
        try {
            log.info("调用闲鱼API获取商品详情: itemId={}", itemId);
            
            // 构建请求数据
            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("itemId", itemId);
            
            // 商品详情同样通过统一调用器处理 Set-Cookie 与令牌刷新，保持列表和详情凭据一致。
            com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils.ApiCallResult apiResult =
                    xianyuAdapter.callApi(
                            accountId,
                            "mtop.taobao.idle.pc.detail",
                            dataMap,
                            cookiesStr);
            String response = apiResult.getResponse();

            if (!apiResult.isSuccess() || response == null) {
                String detailFailureCategory = describeApiFailure(apiResult.getErrorMessage());
                log.error("商品详情API调用失败: itemId={}, accountId={}, failureCategory={}",
                        itemId, accountId, detailFailureCategory);
                return null;
            }
            
            log.info("API响应成功，响应长度: {}, itemId={}", response.length(), itemId);
            log.info("mtop.taobao.idle.pc.detail 响应已接收: itemId={}, responseLength={}",
                    itemId, response.length());
            
            // 检查响应是否成功
            if (!XianyuApiUtils.isSuccess(response)) {
                String error = XianyuApiUtils.extractError(response);
                String safeError = error == null || error.isBlank()
                        ? "未知错误"
                        : error.replaceAll("[\\r\\n\\t]+", " ").trim();
                if (safeError.length() > 160) {
                    safeError = safeError.substring(0, 160);
                }
                log.warn("闲鱼商品详情接口返回业务失败: itemId={}, error={}", itemId, safeError);
                return null;
            }
            
            log.info("API响应状态检查通过，开始提取data字段: itemId={}", itemId);
            
            // 提取data字段
            Map<String, Object> data = XianyuApiUtils.extractData(response);
            if (data == null) {
                log.error("无法提取data字段, itemId={}", itemId);
                log.error("响应内容无法解析: responseLength={}", response.length());
                return null;
            }
            
            log.info("data字段提取成功，包含 {} 个字段, itemId={}", data.size(), itemId);
            
            // 将data转换为JSON字符串
            String detailJson = objectMapper.writeValueAsString(data);
            log.info("API获取商品详情成功: itemId={}, 详情长度={}", itemId, detailJson.length());
            
            // 提取desc字段
            String extractedDesc = ItemDetailUtils.extractDescFromDetailJson(detailJson);
            log.info("提取desc字段成功: itemId={}, 原始长度={}, 提取后长度={}", 
                    itemId, detailJson.length(), extractedDesc.length());
            
            List<XianyuGoodsSku> skuList = ItemDetailUtils.extractSkuList(detailJson);
            if (!skuList.isEmpty()) {
                XianyuGoodsInfo goodsInfo = goodsInfoService.getByXyGoodId(itemId);
                Long goodsAccountId = goodsInfo != null ? goodsInfo.getXianyuAccountId() : accountId;
                goodsSkuService.saveSkus(itemId, goodsAccountId, skuList);
                goodsInfoService.updateSkuCount(itemId, skuList.size());
                List<XianyuGoodsSkuProperty> propertyList = ItemDetailUtils.extractSkuPropertyList(detailJson);
                if (!propertyList.isEmpty()) {
                    goodsSkuPropertyService.saveProperties(itemId, goodsAccountId, propertyList);
                }
            }
            
            return extractedDesc;
            
        } catch (Exception e) {
            log.error("API获取商品详情异常: itemId={}", itemId, e);
            return null;
        }
    }
    
    /**
     * 检查详情信息是否新鲜（24小时内）
     *
     * @param updatedTime 更新时间字符串（格式：yyyy-MM-dd HH:mm:ss）
     * @return 是否新鲜
     */
    private boolean isDetailInfoFresh(LocalDateTime updatedTime) {
        if (updatedTime == null) {
            return false;
        }
        try {
            // 计算时间差
            java.time.Duration duration = java.time.Duration.between(updatedTime, java.time.LocalDateTime.now());
            long hours = duration.toHours();
            // 24小时内认为是新鲜的
            boolean isFresh = hours < 24;
            log.debug("详情缓存检查: 更新时间={}, 距今{}小时, 是否新鲜={}", updatedTime, hours, isFresh);
            return isFresh;
        } catch (Exception e) {
            log.error("判断更新时间失败: {}", updatedTime, e);
            return false;
        }
    }
    /**
     * 按官方个人主页流程解析商品列表所需的 kcUserId。
     *
     * @param accountId 账号ID
     * @param cookiesStr 当前明文Cookie
     * @param fallbackUserId 解析失败时使用的历史UNB值
     * @return 商品列表接口要求的用户ID
     */
    @SuppressWarnings("unchecked")
    private String resolveGoodsUserId(Long accountId, String cookiesStr, String fallbackUserId) {
        try {
            Map<String, Object> headData = new HashMap<>();
            // 官方页面在访问本人主页时只提交 self=true，undefined 的 userId 不会进入序列化结果。
            headData.put("self", true);
            com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils.ApiCallResult headResult =
                    callPersonalPageApi(accountId, "mtop.idle.web.user.page.head", headData, cookiesStr);
            if (!headResult.isSuccess()) {
                String userIdFailureCategory = describeApiFailure(headResult.getErrorMessage());
                log.warn("解析官方商品用户ID失败，使用历史账号标识: accountId={}, failureCategory={}",
                        accountId, userIdFailureCategory);
                return fallbackUserId;
            }
            Map<String, Object> data = headResult.extractData();
            Map<String, Object> baseInfo = data != null && data.get("baseInfo") instanceof Map
                    ? (Map<String, Object>) data.get("baseInfo")
                    : null;
            Object kcUserId = baseInfo != null ? baseInfo.get("kcUserId") : null;
            if (kcUserId != null && !kcUserId.toString().isBlank()) {
                log.info("官方商品用户ID解析成功: accountId={}", accountId);
                return kcUserId.toString();
            }
            log.warn("个人主页响应缺少kcUserId，使用历史账号标识: accountId={}", accountId);
            return fallbackUserId;
        } catch (Exception e) {
            log.warn("解析官方商品用户ID异常，使用历史账号标识: accountId={}, errorType={}",
                    accountId, e.getClass().getSimpleName());
            return fallbackUserId;
        }
    }

    /**
     * 以官方个人主页的请求上下文调用页面级 MTop 接口。
     *
     * <p>商品列表与个人头信息不是通用 H5 接口；它们会校验来源页和 SPM。
     * 此处将该上下文限制在个人页 API，避免影响订单、详情等既有调用。</p>
     */
    private com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils.ApiCallResult callPersonalPageApi(
            Long accountId, String apiName, Map<String, Object> dataMap, String cookiesStr) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile.PERSONAL_PAGE_REFERER);

        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("spm_cnt",
                com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile.PERSONAL_PAGE_SPM_CNT);

        return xianyuAdapter.callApi(
                accountId, apiName, dataMap, cookiesStr, headers, queryParams);
    }

    /**
     * 将远端错误归类为固定标签，日志不记录可能包含会话、链接或服务端回显的原始文本。
     */
    private String describeApiFailure(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "EMPTY_RESPONSE";
        }
        String normalized = errorMessage.toUpperCase(Locale.ROOT);
        if (normalized.contains("RGV587") || normalized.contains("RISK") || normalized.contains("风控")) {
            return "RISK_CONTROL";
        }
        if (normalized.contains("TOKEN") || normalized.contains("SESSION") || normalized.contains("登录")) {
            return "AUTHENTICATION";
        }
        return "REMOTE_REQUEST_FAILED";
    }

    /**
     * 从商品卡片提取稳定主键，缺少主键的卡片不参与远端快照。
     */
    private String extractItemId(ItemDTO item) {
        if (item == null || item.getDetailParams() == null) {
            return null;
        }
        String itemId = item.getDetailParams().getItemId();
        return itemId != null && !itemId.isBlank() ? itemId : null;
    }

    /**
     * 从首屏服务端分组中选择默认分组；无默认标志时使用首个分组，不引入历史固定分组值。
     */
    private Map<String, Object> selectDynamicGroupContext(List<Map<String, Object>> itemGroupList) {
        if (itemGroupList == null || itemGroupList.isEmpty()) {
            return null;
        }
        for (Map<String, Object> group : itemGroupList) {
            if (Boolean.TRUE.equals(toNullableBoolean(group.get("defaultGroup")))) {
                return new LinkedHashMap<>(group);
            }
        }
        return new LinkedHashMap<>(itemGroupList.get(0));
    }

    /**
     * 兼容接口将布尔值序列化为 Boolean、数字或字符串的情况；未知形式保留为 null。
     */
    private Boolean toNullableBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number numberValue) {
            return numberValue.intValue() != 0;
        }
        if (value instanceof String stringValue) {
            if ("true".equalsIgnoreCase(stringValue) || "1".equals(stringValue)) {
                return true;
            }
            if ("false".equalsIgnoreCase(stringValue) || "0".equals(stringValue)) {
                return false;
            }
        }
        return null;
    }

    /**
     * 解析商品列表响应
     */
    @SuppressWarnings("unchecked")
    private ItemListRespDTO parseItemListResponse(Map<String, Object> responseMap, int pageNumber, int pageSize) {
        ItemListRespDTO respDTO = new ItemListRespDTO();
        respDTO.setPageNumber(pageNumber);
        respDTO.setPageSize(pageSize);
        respDTO.setItems(new ArrayList<>());

        try {
            log.info("开始解析响应，字段数: {}", responseMap.size());
            
            List<String> ret = (List<String>) responseMap.get("ret");
            log.info("ret字段数量: {}", ret != null ? ret.size() : 0);
            
            // 检查令牌是否过期
            if (ret != null && !ret.isEmpty()) {
                String retValue = ret.get(0);
                if (retValue.contains("FAIL_SYS_TOKEN_EXOIRED") || retValue.contains("令牌过期")) {
                    log.warn("API调用失败，检测到令牌过期: retCount={}", ret.size());
                    throw new com.feijimiao.xianyuassistant.exception.BusinessException("令牌已过期，请重新登录");
                }
            }
            
            if (ret != null && !ret.isEmpty() && ret.get(0).contains("SUCCESS")) {
                log.info("API调用成功，开始解析数据");
                respDTO.setSuccess(true);

                Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
                log.info("data字段存在: {}, fieldCount={}", data != null, data != null ? data.size() : 0);
                
                if (data != null) {
                    // 服务端分页字段按原始形态保留，供后续页请求透传。
                    respDTO.setNextPage(toNullableBoolean(data.get("nextPage")));
                    respDTO.setNextPageModel(data.get("nextPageModel"));
                    respDTO.setNextPageNum(data.get("nextPageNum"));
                    Object rawItemGroupList = data.get("itemGroupList");
                    if (rawItemGroupList instanceof List<?>) {
                        respDTO.setItemGroupList(objectMapper.convertValue(rawItemGroupList,
                                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { }));
                    }

                    List<Map<String, Object>> cardList = (List<Map<String, Object>>) data.get("cardList");
                    log.info("cardList存在: {}, size: {}", cardList != null, cardList != null ? cardList.size() : 0);
                    
                    if (cardList != null) {
                        for (Map<String, Object> card : cardList) {
                            Map<String, Object> cardData = (Map<String, Object>) card.get("cardData");
                            if (cardData != null) {
                                log.info("商品cardData已解析: fieldCount={}", cardData.size());
                                ItemDTO itemDTO = objectMapper.convertValue(cardData, ItemDTO.class);
                                respDTO.getItems().add(itemDTO);
                            }
                        }
                    }
                }

                respDTO.setCurrentCount(respDTO.getItems().size());
                respDTO.setSavedCount(respDTO.getItems().size());
                log.info("解析完成，商品数量: {}", respDTO.getItems().size());
            } else {
                log.warn("API调用失败: retCount={}", ret != null ? ret.size() : 0);
                respDTO.setSuccess(false);
            }
        } catch (com.feijimiao.xianyuassistant.exception.BusinessException e) {
            // 重新抛出业务异常
            throw e;
        } catch (Exception e) {
            log.error("解析商品列表响应失败: errorType={}", e.getClass().getSimpleName());
            respDTO.setSuccess(false);
        }

        return respDTO;
    }

    /**
     * 从cookieId获取账号ID
     * cookieId可以是：账号ID、账号备注(account_note)或UNB
     *
     * @param cookieId Cookie ID
     * @return 账号ID
     */
    private Long getAccountIdFromCookieId(String cookieId) {
        try {
            // 1. 先尝试作为账号ID解析（数字）
            try {
                return Long.parseLong(cookieId);
            } catch (NumberFormatException e) {
                // 不是数字，继续其他方式查询
                log.debug("cookieId不是数字，尝试其他查询方式: inputLength={}",
                        cookieId != null ? cookieId.length() : 0);
            }
            
            // 2. 尝试按账号备注查询
            Long accountId = accountService.getAccountIdByAccountNote(cookieId);
            if (accountId != null) {
                log.info("通过账号备注获取账号ID成功: accountId={}", accountId);
                return accountId;
            }
            
            // 3. 尝试按UNB查询
            accountId = accountService.getAccountIdByUnb(cookieId);
            if (accountId != null) {
                log.info("通过UNB获取账号ID成功: accountId={}", accountId);
                return accountId;
            }
            
            log.warn("未找到账号ID: inputLength={}", cookieId != null ? cookieId.length() : 0);
            return null;
            
        } catch (Exception e) {
            log.error("获取账号ID失败: inputLength={}, errorType={}",
                    cookieId != null ? cookieId.length() : 0, e.getClass().getSimpleName());
            return null;
        }
    }
    
    /**
     * 从数据库获取Cookie（包含 m_h5_tk 补充逻辑）
     * cookieId可以是：账号ID、账号备注(account_note)或UNB
     */
    /** cookieId 可为账号ID/备注/UNB，仅当它是数字账号ID时返回该 ID，用于账号范围校验。 */
    private Long parseAccountIdOrNull(String cookieId) {
        try {
            return Long.parseLong(cookieId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String getCookieFromDb(String cookieId) {
        try {
            log.info("从数据库查询Cookie: inputLength={}", cookieId != null ? cookieId.length() : 0);
            
            String cookie = null;
            
            // 1. 先尝试作为账号ID查询（数字）
            try {
                Long accountId = Long.parseLong(cookieId);
                cookie = accountService.getCookieByAccountId(accountId);
                if (cookie != null) {
                    log.info("通过账号ID获取Cookie成功: accountId={}", accountId);
                    // 检查并补充 _m_h5_tk
                    cookie = ensureMh5tkInCookie(cookie, accountId);
                    return cookie;
                }
            } catch (NumberFormatException e) {
                // 不是数字，继续其他方式查询
                log.debug("cookieId不是数字，尝试其他查询方式: inputLength={}",
                        cookieId != null ? cookieId.length() : 0);
            }
            
            // 2. 尝试按账号备注查询
            cookie = accountService.getCookieByAccountNote(cookieId);
            if (cookie != null) {
                log.info("通过账号备注获取Cookie成功");
                return cookie;
            }
            
            // 3. 尝试按UNB查询
            cookie = accountService.getCookieByUnb(cookieId);
            if (cookie != null) {
                log.info("通过UNB获取Cookie成功");
                return cookie;
            }
            
            log.warn("未找到Cookie: inputLength={}", cookieId != null ? cookieId.length() : 0);
            return null;
            
        } catch (Exception e) {
            log.error("从数据库获取Cookie失败: inputLength={}, errorType={}",
                    cookieId != null ? cookieId.length() : 0, e.getClass().getSimpleName());
            return null;
        }
    }
    
    @Override
    public ResultObject<UpdateAutoDeliveryRespDTO> updateAutoDeliveryStatus(UpdateAutoDeliveryReqDTO reqDTO) {
        try {
            log.info("更新商品自动发货状态: xianyuAccountId={}, xyGoodsId={}, status={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getXianyuAutoDeliveryOn());
            
            // 1. 获取商品配置
            com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig goodsConfig = 
                    autoDeliveryService.getGoodsConfig(reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
            
            // 2. 如果配置不存在，创建新的配置
            if (goodsConfig == null) {
                goodsConfig = new com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig();
                goodsConfig.setXianyuAccountId(reqDTO.getXianyuAccountId());
                goodsConfig.setXyGoodsId(reqDTO.getXyGoodsId());
                goodsConfig.setXianyuAutoDeliveryOn(reqDTO.getXianyuAutoDeliveryOn());
                goodsConfig.setXianyuAutoReplyOn(0); // 默认关闭自动回复
                goodsConfig.setCreateTime(LocalDateTime.now());
                goodsConfig.setUpdateTime(goodsConfig.getCreateTime());
            } else {
                // 3. 更新配置
                goodsConfig.setXianyuAutoDeliveryOn(reqDTO.getXianyuAutoDeliveryOn());
                goodsConfig.setUpdateTime(LocalDateTime.now());
            }
            
            // 4. 保存配置
            autoDeliveryService.saveOrUpdateGoodsConfig(goodsConfig);
            
            // 5. 返回结果
            UpdateAutoDeliveryRespDTO respDTO = new UpdateAutoDeliveryRespDTO();
            respDTO.setSuccess(true);
            respDTO.setMessage("自动发货状态更新成功");
            
            log.info("自动发货状态更新成功: xianyuAccountId={}, xyGoodsId={}, status={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getXianyuAutoDeliveryOn());
            
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("更新自动发货状态失败: xianyuAccountId={}, xyGoodsId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), e);
            return ResultObject.failed("更新自动发货状态失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<UpdateAutoReplyRespDTO> updateAutoReplyStatus(UpdateAutoReplyReqDTO reqDTO) {
        try {
            log.info("更新商品自动回复状态: xianyuAccountId={}, xyGoodsId={}, status={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getXianyuAutoReplyOn());
            
            // 1. 获取商品配置
            com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig goodsConfig = 
                    autoDeliveryService.getGoodsConfig(reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
            
            // 2. 如果配置不存在，创建新的配置
            if (goodsConfig == null) {
                goodsConfig = new com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig();
                goodsConfig.setXianyuAccountId(reqDTO.getXianyuAccountId());
                goodsConfig.setXyGoodsId(reqDTO.getXyGoodsId());
                goodsConfig.setXianyuAutoDeliveryOn(0); // 默认关闭自动发货
                goodsConfig.setXianyuAutoReplyOn(reqDTO.getXianyuAutoReplyOn());
                // 携带上下文开关：第一次跟随自动回复开关默认开启
                if (reqDTO.getXianyuAutoReplyContextOn() != null) {
                    goodsConfig.setXianyuAutoReplyContextOn(reqDTO.getXianyuAutoReplyContextOn());
                } else {
                    goodsConfig.setXianyuAutoReplyContextOn(1); // 默认开启
                }
                goodsConfig.setCreateTime(LocalDateTime.now());
                goodsConfig.setUpdateTime(goodsConfig.getCreateTime());
                if (reqDTO.getXianyuKeywordReplyOn() != null) {
                    goodsConfig.setXianyuKeywordReplyOn(reqDTO.getXianyuKeywordReplyOn());
                } else {
                    goodsConfig.setXianyuKeywordReplyOn(0);
                }
                if (reqDTO.getHumanInterventionOn() != null) {
                    goodsConfig.setHumanInterventionOn(reqDTO.getHumanInterventionOn());
                } else {
                    goodsConfig.setHumanInterventionOn(0);
                }
                if (reqDTO.getHumanInterventionMinutes() != null && reqDTO.getHumanInterventionMinutes() > 0) {
                    goodsConfig.setHumanInterventionMinutes(reqDTO.getHumanInterventionMinutes());
                } else {
                    goodsConfig.setHumanInterventionMinutes(10);
                }
                if (reqDTO.getReplyManualConfirmOn() != null) {
                    goodsConfig.setReplyManualConfirmOn(reqDTO.getReplyManualConfirmOn());
                } else {
                    goodsConfig.setReplyManualConfirmOn(0);
                }
                if (reqDTO.getBargainOn() != null) {
                    goodsConfig.setBargainOn(reqDTO.getBargainOn());
                } else {
                    goodsConfig.setBargainOn(0);
                }
                goodsConfig.setBargainFloorPrice(normalizeBargainPrice(reqDTO.getBargainFloorPrice()));
                goodsConfig.setBargainStepPrice(normalizeBargainPrice(reqDTO.getBargainStepPrice()));
                if (reqDTO.getBargainMaxRounds() != null && reqDTO.getBargainMaxRounds() > 0) {
                    goodsConfig.setBargainMaxRounds(reqDTO.getBargainMaxRounds());
                } else {
                    goodsConfig.setBargainMaxRounds(3);
                }
            } else {
                // 3. 更新配置
                goodsConfig.setXianyuAutoReplyOn(reqDTO.getXianyuAutoReplyOn());
                // 更新携带上下文开关
                if (reqDTO.getXianyuAutoReplyContextOn() != null) {
                    goodsConfig.setXianyuAutoReplyContextOn(reqDTO.getXianyuAutoReplyContextOn());
                }
                if (reqDTO.getXianyuKeywordReplyOn() != null) {
                    goodsConfig.setXianyuKeywordReplyOn(reqDTO.getXianyuKeywordReplyOn());
                }
                if (reqDTO.getHumanInterventionOn() != null) {
                    goodsConfig.setHumanInterventionOn(reqDTO.getHumanInterventionOn());
                }
                if (reqDTO.getHumanInterventionMinutes() != null && reqDTO.getHumanInterventionMinutes() > 0) {
                    goodsConfig.setHumanInterventionMinutes(reqDTO.getHumanInterventionMinutes());
                }
                if (reqDTO.getReplyManualConfirmOn() != null) {
                    goodsConfig.setReplyManualConfirmOn(reqDTO.getReplyManualConfirmOn());
                }
                if (reqDTO.getBargainOn() != null) {
                    goodsConfig.setBargainOn(reqDTO.getBargainOn());
                }
                if (reqDTO.getBargainFloorPrice() != null) {
                    goodsConfig.setBargainFloorPrice(normalizeBargainPrice(reqDTO.getBargainFloorPrice()));
                }
                if (reqDTO.getBargainStepPrice() != null) {
                    goodsConfig.setBargainStepPrice(normalizeBargainPrice(reqDTO.getBargainStepPrice()));
                }
                if (reqDTO.getBargainMaxRounds() != null && reqDTO.getBargainMaxRounds() > 0) {
                    goodsConfig.setBargainMaxRounds(reqDTO.getBargainMaxRounds());
                }
                goodsConfig.setUpdateTime(LocalDateTime.now());
            }
            
            // 4. 保存配置
            autoDeliveryService.saveOrUpdateGoodsConfig(goodsConfig);
            
            // 5. 返回结果
            UpdateAutoReplyRespDTO respDTO = new UpdateAutoReplyRespDTO();
            respDTO.setSuccess(true);
            respDTO.setMessage("自动回复状态更新成功");
            
            log.info("自动回复状态更新成功: xianyuAccountId={}, xyGoodsId={}, status={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getXianyuAutoReplyOn());
            
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("更新自动回复状态失败: xianyuAccountId={}, xyGoodsId={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), e);
            return ResultObject.failed("更新自动回复状态失败: " + e.getMessage());
        }
    }

    /**
     * 校验并规范化议价价格输入：空白返回 null，非法格式或非正数抛出业务异常。
     */
    private String normalizeBargainPrice(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            if (new java.math.BigDecimal(trimmed).signum() <= 0) {
                throw new IllegalArgumentException("议价价格必须为正数: " + trimmed);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("议价价格格式错误: " + trimmed);
        }
        return trimmed;
    }

    @Override
    public ResultObject<BatchUpdateGoodsConfigRespDTO> batchUpdateGoodsConfig(BatchUpdateGoodsConfigReqDTO reqDTO) {
        try {
            int maxItems = 200;
            if (reqDTO.getXyGoodsIds().size() > maxItems) {
                return ResultObject.failed("单次最多操作" + maxItems + "个商品");
            }
            boolean updateDelivery = reqDTO.getXianyuAutoDeliveryOn() != null;
            boolean updateReply = reqDTO.getXianyuAutoReplyOn() != null;
            if (!updateDelivery && !updateReply) {
                return ResultObject.failed("请至少指定自动发货或自动回复开关");
            }

            log.info("批量更新商品自动化配置: accountId={}, 商品数={}, 自动发货={}, 自动回复={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsIds().size(),
                    reqDTO.getXianyuAutoDeliveryOn(), reqDTO.getXianyuAutoReplyOn());

            BatchUpdateGoodsConfigRespDTO respDTO = new BatchUpdateGoodsConfigRespDTO();
            respDTO.setTotal(reqDTO.getXyGoodsIds().size());

            for (String xyGoodsId : reqDTO.getXyGoodsIds()) {
                if (xyGoodsId == null || xyGoodsId.isEmpty()) {
                    addBatchFail(respDTO, xyGoodsId, "商品ID为空");
                    continue;
                }
                try {
                    com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig goodsConfig =
                            autoDeliveryService.getGoodsConfig(reqDTO.getXianyuAccountId(), xyGoodsId);
                    if (goodsConfig == null) {
                        goodsConfig = new com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig();
                        goodsConfig.setXianyuAccountId(reqDTO.getXianyuAccountId());
                        goodsConfig.setXyGoodsId(xyGoodsId);
                        goodsConfig.setXianyuAutoDeliveryOn(0);
                        goodsConfig.setXianyuAutoReplyOn(0);
                        goodsConfig.setXianyuAutoReplyContextOn(1);
                        goodsConfig.setXianyuKeywordReplyOn(0);
                        goodsConfig.setHumanInterventionOn(0);
                        goodsConfig.setHumanInterventionMinutes(10);
                        goodsConfig.setCreateTime(LocalDateTime.now());
                    }
                    if (updateDelivery) {
                        goodsConfig.setXianyuAutoDeliveryOn(reqDTO.getXianyuAutoDeliveryOn());
                    }
                    if (updateReply) {
                        goodsConfig.setXianyuAutoReplyOn(reqDTO.getXianyuAutoReplyOn());
                        if (reqDTO.getXianyuAutoReplyContextOn() != null) {
                            goodsConfig.setXianyuAutoReplyContextOn(reqDTO.getXianyuAutoReplyContextOn());
                        }
                    }
                    goodsConfig.setUpdateTime(LocalDateTime.now());
                    autoDeliveryService.saveOrUpdateGoodsConfig(goodsConfig);
                    respDTO.setSuccessCount(respDTO.getSuccessCount() + 1);
                } catch (Exception e) {
                    log.error("批量更新商品配置失败: accountId={}, xyGoodsId={}", 
                            reqDTO.getXianyuAccountId(), xyGoodsId, e);
                    addBatchFail(respDTO, xyGoodsId, e.getMessage());
                }
            }

            respDTO.setFailCount(respDTO.getFailures().size());
            log.info("批量更新商品配置完成: 成功{}条, 失败{}条", respDTO.getSuccessCount(), respDTO.getFailCount());
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("批量更新商品自动化配置失败", e);
            return ResultObject.failed("批量更新商品自动化配置失败: " + e.getMessage());
        }
    }

    private void addBatchFail(BatchUpdateGoodsConfigRespDTO respDTO, String xyGoodsId, String reason) {
        BatchUpdateGoodsConfigRespDTO.FailItem fail = new BatchUpdateGoodsConfigRespDTO.FailItem();
        fail.setXyGoodsId(xyGoodsId);
        fail.setReason(reason != null ? reason : "未知错误");
        respDTO.getFailures().add(fail);
    }
    
    @Override
    public ResultObject<DeleteItemRespDTO> deleteItem(DeleteItemReqDTO reqDTO) {
        try {
            log.info("开始删除商品: xianyuAccountId={}, xyGoodsId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
            
            // 验证参数
            if (reqDTO.getXianyuAccountId() == null) {
                log.error("账号ID不能为空");
                return ResultObject.failed("账号ID不能为空");
            }
            
            if (reqDTO.getXyGoodsId() == null || reqDTO.getXyGoodsId().isEmpty()) {
                log.error("商品ID不能为空");
                return ResultObject.failed("商品ID不能为空");
            }
            
            // 删除商品信息
            boolean deleted = goodsInfoService.deleteGoodsInfo(
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
            
            DeleteItemRespDTO respDTO = new DeleteItemRespDTO();
            if (deleted) {
                respDTO.setMessage("商品删除成功");
                log.info("商品删除成功: xianyuAccountId={}, xyGoodsId={}", 
                        reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
                return ResultObject.success(respDTO);
            } else {
                respDTO.setMessage("商品删除失败，商品可能不存在");
                log.warn("商品删除失败: xianyuAccountId={}, xyGoodsId={}", 
                        reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
                return ResultObject.failed("商品删除失败");
            }
        } catch (Exception e) {
            log.error("删除商品异常: xianyuAccountId={}, xyGoodsId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), e);
            return ResultObject.failed("删除商品异常: " + e.getMessage());
        }
    }
    
    /**
     * 确保Cookie中包含 _m_h5_tk
     * 如果cookie_text中没有，则从数据库的m_h5_tk字段补充
     */
    private String ensureMh5tkInCookie(String cookieText, Long accountId) {
        try {
            // 解析Cookie
            Map<String, String> cookies = XianyuSignUtils.parseCookies(cookieText);
            
            // 如果已经包含 _m_h5_tk，直接返回
            if (cookies.containsKey("_m_h5_tk") && !cookies.get("_m_h5_tk").isEmpty()) {
                return cookieText;
            }
            
            // 从数据库获取 m_h5_tk
            String mH5Tk = accountService.getMh5tkByAccountId(accountId);
            if (mH5Tk != null && !mH5Tk.isEmpty()) {
                log.info("从数据库m_h5_tk字段补充token: accountId={}", accountId);
                cookies.put("_m_h5_tk", mH5Tk);
                return XianyuSignUtils.formatCookies(cookies);
            }
            
            log.warn("数据库中也没有m_h5_tk: accountId={}", accountId);
            return cookieText;
            
        } catch (Exception e) {
            log.error("补充m_h5_tk失败: accountId={}", accountId, e);
            return cookieText;
        }
    }
    

    
    /**
     * 获取自动回复配置
     */
    @Override
    public ResultObject<RagAutoReplyConfigRespDTO> getRagAutoReplyConfig(RagAutoReplyConfigReqDTO reqDTO) {
        try {
            Long accountId = reqDTO.getXianyuAccountId();
            String xyGoodsId = reqDTO.getXyGoodsId();

            com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig config = 
                    autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);

            RagAutoReplyConfigRespDTO respDTO = new RagAutoReplyConfigRespDTO();
            if (config != null && config.getRagDelaySeconds() != null) {
                respDTO.setRagDelaySeconds(config.getRagDelaySeconds());
            } else {
                respDTO.setRagDelaySeconds(15);
            }
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取自动回复配置失败", e);
            return ResultObject.failed("获取自动回复配置失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新自动回复配置
     */
    @Override
    public ResultObject<?> updateRagAutoReplyConfig(UpdateRagAutoReplyConfigReqDTO reqDTO) {
        try {
            Long accountId = reqDTO.getXianyuAccountId();
            String xyGoodsId = reqDTO.getXyGoodsId();
            Integer ragDelaySeconds = reqDTO.getRagDelaySeconds();

            com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig config = 
                    autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);

            if (config == null) {
                config = new com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig();
                config.setXianyuAccountId(accountId);
                config.setXyGoodsId(xyGoodsId);
                config.setRagDelaySeconds(ragDelaySeconds != null ? ragDelaySeconds : 15);
                autoDeliveryConfigMapper.insert(config);
            } else {
                config.setRagDelaySeconds(ragDelaySeconds != null ? ragDelaySeconds : 15);
                autoDeliveryConfigMapper.updateById(config);
            }

            log.info("更新自动回复延时配置成功: accountId={}, xyGoodsId={}, ragDelaySeconds={}", 
                    accountId, xyGoodsId, ragDelaySeconds);
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("更新自动回复配置失败", e);
            return ResultObject.failed("更新自动回复配置失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<XianyuGoodsInfo> updateItemInfo(UpdateItemInfoReqDTO reqDTO) {
        try {
            XianyuGoodsInfo goods = goodsInfoService.updateItemInfo(reqDTO);
            return ResultObject.success(goods, "本地商品资料已更新；闲鱼远程商品请在官方页面确认");
        } catch (IllegalArgumentException e) {
            return ResultObject.failed(400, e.getMessage());
        } catch (Exception e) {
            log.error("更新本地商品资料失败", e);
            return ResultObject.failed("更新商品资料失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<BatchUpdatePriceRespDTO> batchUpdatePrice(BatchUpdatePriceReqDTO reqDTO) {
        if (reqDTO == null || reqDTO.getXianyuAccountId() == null) {
            return ResultObject.failed(400, "账号ID不能为空");
        }
        if (reqDTO.getXyGoodsIds() == null || reqDTO.getXyGoodsIds().isEmpty()) {
            return ResultObject.failed(400, "商品ID列表不能为空");
        }
        if (reqDTO.getXyGoodsIds().size() > 50) {
            return ResultObject.failed(400, "单次最多操作50个商品");
        }
        if (reqDTO.getSoldPrice() == null) {
            return ResultObject.failed(400, "商品价格不能为空");
        }

        BatchUpdatePriceRespDTO response = new BatchUpdatePriceRespDTO();
        response.setTotal(reqDTO.getXyGoodsIds().size());
        String requestedPrice = reqDTO.getSoldPrice().toPlainString();
        for (String xyGoodsId : reqDTO.getXyGoodsIds()) {
            BatchUpdatePriceRespDTO.ItemPriceResult detail = new BatchUpdatePriceRespDTO.ItemPriceResult();
            detail.setXyGoodsId(xyGoodsId);
            try {
                XianyuGoodsInfo goods = goodsInfoService.updateItemPrice(
                        reqDTO.getXianyuAccountId(), xyGoodsId, reqDTO.getSoldPrice());
                detail.setSuccess(true);
                detail.setSoldPrice(goods.getSoldPrice());
                detail.setMessage("本地售价已更新；闲鱼远程商品请在官方页面确认");
                response.setSuccess(response.getSuccess() + 1);
            } catch (Exception e) {
                detail.setSuccess(false);
                detail.setSoldPrice(requestedPrice);
                detail.setMessage(e.getMessage() != null ? e.getMessage() : "更新失败");
                response.setFailed(response.getFailed() + 1);
                log.warn("批量更新本地商品售价失败: accountId={}, xyGoodsId={}, errorType={}",
                        reqDTO.getXianyuAccountId(), xyGoodsId, e.getClass().getSimpleName());
            }
            response.getDetails().add(detail);
        }
        return ResultObject.success(response, "批量更新本地售价完成；闲鱼远程商品请在官方页面确认");
    }

    @Override
    public ResultObject<XianyuGoodsInfo> createItemDraft(CreateItemDraftReqDTO reqDTO) {
        try {
            XianyuGoodsInfo goods = goodsInfoService.createItemDraft(reqDTO);
            return ResultObject.success(goods, "本地商品草稿已创建；发布到闲鱼需在官方发布页完成");
        } catch (IllegalArgumentException e) {
            return ResultObject.failed(400, e.getMessage());
        } catch (Exception e) {
            log.error("创建本地商品草稿失败", e);
            return ResultObject.failed("创建商品草稿失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<PolishItemRespDTO> polishItem(PolishItemReqDTO reqDTO) {
        if (reqDTO == null || reqDTO.getXianyuAccountId() == null || reqDTO.getXyGoodsId() == null) {
            return ResultObject.failed(400, "参数不能为空");
        }
        BatchPolishItemsReqDTO batchReq = new BatchPolishItemsReqDTO();
        batchReq.setXianyuAccountId(reqDTO.getXianyuAccountId());
        batchReq.setXyGoodsIds(List.of(reqDTO.getXyGoodsId()));
        return batchPolishItems(batchReq);
    }

    @Override
    public ResultObject<PolishItemRespDTO> batchPolishItems(BatchPolishItemsReqDTO reqDTO) {
        if (reqDTO == null || reqDTO.getXianyuAccountId() == null) {
            return ResultObject.failed(400, "账号ID不能为空");
        }
        Long accountId = reqDTO.getXianyuAccountId();
        String cookie = accountService.getCookieByAccountId(accountId);
        if (cookie == null || cookie.isBlank()) {
            return ResultObject.failed(400, "账号Cookie不存在或未登录");
        }

        List<String> targetGoodsIds = reqDTO.getXyGoodsIds();
        if (targetGoodsIds == null || targetGoodsIds.isEmpty()) {
            // 为空时自动获取该账号下所有在售商品
            targetGoodsIds = goodsInfoMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<XianyuGoodsInfo>()
                            .eq(XianyuGoodsInfo::getXianyuAccountId, accountId)
                            .eq(XianyuGoodsInfo::getStatus, 0)
            ).stream().map(XianyuGoodsInfo::getXyGoodId).filter(Objects::nonNull).toList();
        }

        if (targetGoodsIds.isEmpty()) {
            return ResultObject.success(PolishItemRespDTO.builder()
                    .total(0)
                    .success(0)
                    .failed(0)
                    .details(Collections.emptyList())
                    .build(), "没有找到待擦亮的在售商品");
        }

        int successCount = 0;
        int failedCount = 0;
        List<PolishItemRespDTO.ItemPolishResult> details = new ArrayList<>();

        for (int i = 0; i < targetGoodsIds.size(); i++) {
            String itemId = targetGoodsIds.get(i);
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("itemId", itemId);
                com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils.ApiCallResult callResult = xianyuAdapter.callApi(
                        accountId,
                        com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile.ITEM_POLISH_API,
                        data,
                        cookie,
                        com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile.ITEM_POLISH_API_VERSION
                );

                boolean ok = callResult.isSuccess();
                String msg = ok ? "擦亮成功" : (callResult.getErrorMessage() != null ? callResult.getErrorMessage() : "擦亮未成功");
                if (ok) {
                    successCount++;
                } else {
                    failedCount++;
                }
                details.add(new PolishItemRespDTO.ItemPolishResult(itemId, ok, msg));

                // 批处理间歇 1 秒，防平台触发频控
                if (i < targetGoodsIds.size() - 1) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                failedCount++;
                details.add(new PolishItemRespDTO.ItemPolishResult(itemId, false, "执行异常: " + e.getMessage()));
            }
        }

        PolishItemRespDTO resp = PolishItemRespDTO.builder()
                .total(targetGoodsIds.size())
                .success(successCount)
                .failed(failedCount)
                .details(details)
                .build();
        return ResultObject.success(resp, String.format("擦亮完成: 共%d个, 成功%d个, 失败%d个",
                targetGoodsIds.size(), successCount, failedCount));
    }
}

