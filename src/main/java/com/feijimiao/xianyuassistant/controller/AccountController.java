package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import jakarta.validation.Valid;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.controller.dto.AccountReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.AddAccountRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.DeleteAccountReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.DeleteAccountRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.GetAccountDetailReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.GetAccountDetailRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.GetAccountListRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.ManualAddAccountReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.UpdateAccountReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.UpdateAccountRespDTO;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.WebSocketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 账号管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/account")
@RequirePermission("account:read")
public class AccountController {

        
    @Autowired
    private AccountService accountService;

    @Autowired
    private WebSocketService webSocketService;
    @Autowired
    private com.feijimiao.xianyuassistant.rbac.service.RbacService rbacService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.XianyuUserProfileService xianyuUserProfileService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.WebSocketTokenService webSocketTokenService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.CookieRefreshService cookieRefreshService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.TokenRefreshService tokenRefreshService;

    /**
     * 获取账号列表
     */
    @PostMapping("/list")
    public ResultObject<GetAccountListRespDTO> getAccountList() {
        try {
            List<XianyuAccount> accounts = accountService.listAll();
            // P0-1 账号数据隔离：非管理员且配置了可见范围时，仅返回范围内账号
            java.util.List<Long> visibleAccountIds = rbacService.getVisibleAccountIds(
                    com.feijimiao.xianyuassistant.context.UserContext.getUserId());
            if (visibleAccountIds != null) {
                java.util.Set<Long> allowed = new java.util.HashSet<>(visibleAccountIds);
                accounts = accounts.stream()
                        .filter(account -> allowed.contains(account.getId()))
                        .toList();
            }
            GetAccountListRespDTO respDTO = new GetAccountListRespDTO();
            respDTO.setAccounts(accounts);
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取账号列表失败", e);
            return ResultObject.failed("获取账号列表失败: " + e.getMessage());
        }
    }

    /**
     * 添加账号
     */
    @PostMapping("/add")
    @RequirePermission("account:write")
    public ResultObject<AddAccountRespDTO> addAccount(@RequestBody AccountReqDTO reqDTO) {
        try {
            log.info("添加账号请求: accountNotePresent={}",
                    reqDTO.getAccountNote() != null && !reqDTO.getAccountNote().isBlank());
            
            if (reqDTO.getCookie() == null || reqDTO.getCookie().isEmpty()) {
                return ResultObject.failed("Cookie不能为空");
            }
            
            Long accountId = accountService.saveAccountAndCookie(
                    reqDTO.getAccountNote(),
                    reqDTO.getUnb(),
                    reqDTO.getCookie()
            );
            
            AddAccountRespDTO respDTO = new AddAccountRespDTO();
            respDTO.setAccountId(accountId);
            respDTO.setMessage("添加成功");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("添加账号失败", e);
            return ResultObject.failed("添加账号失败: " + e.getMessage());
        }
    }

    /**
     * 手动添加账号
     */
    @PostMapping("/manualAdd")
    @RequirePermission("account:write")
    public ResultObject<AddAccountRespDTO> manualAddAccount(@RequestBody ManualAddAccountReqDTO reqDTO) {
        try {
            log.info("手动添加账号请求: accountNotePresent={}",
                    reqDTO.getAccountNote() != null && !reqDTO.getAccountNote().isBlank());
            
            if (reqDTO.getCookie() == null || reqDTO.getCookie().isEmpty()) {
                return ResultObject.failed("Cookie不能为空");
            }
            
            // 从Cookie中提取unb信息
            String unb = extractUnbFromCookie(reqDTO.getCookie());
            if (unb == null || unb.isEmpty()) {
                return ResultObject.failed("无法从Cookie中提取UNB信息");
            }
            
            // 检查账号是否已存在
            Long existingAccountId = accountService.getAccountIdByUnb(unb);
            if (existingAccountId != null) {
                return ResultObject.failed("账号已存在");
            }
            
            // 保存账号和Cookie信息
            Long accountId = accountService.saveAccountAndCookie(
                    reqDTO.getAccountNote(),
                    unb,
                    reqDTO.getCookie()
            );
            
            AddAccountRespDTO respDTO = new AddAccountRespDTO();
            respDTO.setAccountId(accountId);
            respDTO.setMessage("添加成功");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("手动添加账号失败", e);
            return ResultObject.failed("添加账号失败: " + e.getMessage());
        }
    }
    
    /**
     * 从Cookie字符串中提取UNB值
     *
     * @param cookie Cookie字符串
     * @return UNB值，如果未找到则返回null
     */
    private String extractUnbFromCookie(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return null;
        }
        
        // 查找unb=后面的值
        String[] cookieParts = cookie.split(";\\s*");
        for (String part : cookieParts) {
            if (part.startsWith("unb=")) {
                return part.substring(4); // "unb=".length() = 4
            }
        }
        
        return null;
    }

    /**
     * 更新账号
     */
    @PostMapping("/update")
    @RequirePermission("account:write")
    public ResultObject<UpdateAccountRespDTO> updateAccount(@Valid @RequestBody UpdateAccountReqDTO reqDTO) {
        try {
            log.info("更新账号请求: accountId={}", reqDTO.getAccountId());
            
            if (reqDTO.getAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            XianyuAccount account = accountService.getById(reqDTO.getAccountId());
            if (account == null) {
                return ResultObject.failed("账号不存在");
            }
            
            // 只更新账号备注
            if (reqDTO.getAccountNote() != null) {
                account.setAccountNote(reqDTO.getAccountNote());
            }
            
            accountService.updateAccount(account);
            
            // 不再更新Cookie和UNB
            
            UpdateAccountRespDTO respDTO = new UpdateAccountRespDTO();
            respDTO.setMessage("更新成功");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("更新账号失败", e);
            return ResultObject.failed("更新账号失败: " + e.getMessage());
        }
    }

    /**
     * 删除账号
     */
    @PostMapping("/delete")
    @RequirePermission("account:write")
    public ResultObject<DeleteAccountRespDTO> deleteAccount(@Valid @RequestBody DeleteAccountReqDTO reqDTO) {
        try {
            Long id = reqDTO.getAccountId();
            log.info("删除账号请求: accountId={}", id);
            
            XianyuAccount account = accountService.getById(id);
            if (account == null) {
                return ResultObject.failed("账号不存在");
            }
            
            // 先永久取消连接、心跳、令牌刷新和重连任务，再删除持久化数据。
            if (!webSocketService.stopWebSocketForAccountDeletion(id)) {
                return ResultObject.failed("账号连接未能完全停止，请重试或重启服务后再删除");
            }

            // 删除账号关联的所有数据
            accountService.deleteAccountAndRelatedData(id);

            // 账号 ID 会被自增计数器复用，必须清掉各服务残留的内存状态
            // （验证码等待、锁表、通知节流时间戳），否则毒化复用同 ID 的新账号
            webSocketTokenService.removeAccountState(id);
            cookieRefreshService.removeAccountState(id);
            tokenRefreshService.removeAccountState(id);

            DeleteAccountRespDTO respDTO = new DeleteAccountRespDTO();
            respDTO.setMessage("删除成功");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("删除账号失败", e);
            return ResultObject.failed("删除账号失败: " + e.getMessage());
        }
    }

    /**
     * 刷新账号的闲鱼资料（昵称/头像/信用分）
     */
    @PostMapping("/refreshProfile")
    @RequirePermission("account:write")
    public ResultObject<UpdateAccountRespDTO> refreshProfile(@Valid @RequestBody GetAccountDetailReqDTO reqDTO) {
        try {
            Long id = reqDTO.getAccountId();
            log.info("刷新闲鱼资料请求: accountId={}", id);
            if (accountService.getById(id) == null) {
                return ResultObject.failed("账号不存在");
            }
            if (!xianyuUserProfileService.refreshAccountProfile(id)) {
                return ResultObject.failed("刷新闲鱼资料失败，请稍后重试");
            }
            UpdateAccountRespDTO respDTO = new UpdateAccountRespDTO();
            respDTO.setMessage("刷新成功");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("刷新闲鱼资料失败", e);
            return ResultObject.failed("刷新闲鱼资料失败: " + e.getMessage());
        }
    }

    /**
     * 获取账号详情
     */
    @PostMapping("/detail")
    public ResultObject<GetAccountDetailRespDTO> getAccountDetail(@Valid @RequestBody GetAccountDetailReqDTO reqDTO) {
        try {
            Long id = reqDTO.getAccountId();
            XianyuAccount account = accountService.getById(id);
            if (account == null) {
                return ResultObject.failed("账号不存在");
            }
            GetAccountDetailRespDTO respDTO = new GetAccountDetailRespDTO();
            respDTO.setAccount(account);
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取账号详情失败", e);
            return ResultObject.failed("获取账号详情失败: " + e.getMessage());
        }
    }



}
