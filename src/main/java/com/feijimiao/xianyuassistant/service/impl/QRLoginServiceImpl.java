package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.exception.BusinessException;

import com.feijimiao.xianyuassistant.controller.dto.QRLoginResponse;
import com.feijimiao.xianyuassistant.controller.dto.QRLoginSession;
import com.feijimiao.xianyuassistant.controller.dto.QRStatusResponse;
import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.service.QRLoginService;
import com.feijimiao.xianyuassistant.service.XianyuUserProfileService;
import com.feijimiao.xianyuassistant.utils.CookieUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 二维码登录服务实现
 */
@Service
@Slf4j
public class QRLoginServiceImpl implements QRLoginService {
    
    private final Map<String, QRLoginSession> sessions = new ConcurrentHashMap<>();
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.AccountService accountService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.WebSocketTokenService webSocketTokenService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.WebSocketService webSocketService;

    @Autowired
    private XianyuUserProfileService xianyuUserProfileService;

    @Autowired
    @Qualifier("qrLoginExecutor")
    private Executor qrLoginExecutor;
    
    private static final String HOST = "https://passport.goofish.com";
    private static final String API_MINI_LOGIN = HOST + "/mini_login.htm";
    private static final String API_GENERATE_QR = HOST + "/newlogin/qrcode/generate.do";
    private static final String API_SCAN_STATUS = HOST + "/newlogin/qrcode/query.do";
    private static final String API_LOGIN_TOKEN = HOST + "/login_token/login.do";
    private static final String API_MMSTAT_BOOTSTRAP = "https://log.mmstat.com/eg.js";
    private static final String API_POST_LOGIN_REFRESH = XianyuProtocolProfile.H5_BASE_URL
            + "mtop.idle.web.user.page.nav/1.0/";
    private static final String API_H5_TK = XianyuProtocolProfile.H5_BASE_URL
            + XianyuProtocolProfile.H5_TOKEN_REFRESH_API + "/" + XianyuProtocolProfile.H5_API_VERSION + "/";
    
    public QRLoginServiceImpl() {
        this(new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build());
    }

    QRLoginServiceImpl(OkHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient);
    }
    
    private Headers generateHeaders() {
        // 注意：不要手动设置Accept-Encoding，让OkHttp自动处理gzip
        return new Headers.Builder()
                .add("User-Agent", XianyuProtocolProfile.WEB_USER_AGENT)
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .add("Upgrade-Insecure-Requests", "1")
                .build();
    }
    
    private Headers generateApiHeaders() {
        // 注意：不要手动设置Accept-Encoding，让OkHttp自动处理gzip
        return new Headers.Builder()
                .add("User-Agent", XianyuProtocolProfile.WEB_USER_AGENT)
                .add("Accept", "application/json, text/plain, */*")
                .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .add("Referer", "https://passport.goofish.com/")
                .add("Origin", "https://passport.goofish.com")
                .build();
    }

    private Headers generateMtopHeaders() {
        return new Headers.Builder()
                .add("User-Agent", XianyuProtocolProfile.WEB_USER_AGENT)
                .add("Accept", "application/json")
                .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .add("Content-Type", "application/x-www-form-urlencoded")
                .add("Referer", "https://www.goofish.com/")
                .add("Origin", "https://www.goofish.com")
                .build();
    }

    private String sessionCookieHeader(QRLoginSession session) {
        return CookieUtils.formatCookies(session.getCookies());
    }

    private void updateSessionCookies(QRLoginSession session, Response response) {
        for (String cookie : response.headers("Set-Cookie")) {
            String[] parts = cookie.split(";", 2)[0].split("=", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                session.getCookies().put(parts[0], parts[1]);
                if ("unb".equals(parts[0])) {
                    session.setUnb(parts[1]);
                }
            }
        }
    }

    private void bootstrapInitialCookies(QRLoginSession session) {
        Request request = new Request.Builder()
                .url(API_MMSTAT_BOOTSTRAP)
                .header("User-Agent", XianyuProtocolProfile.WEB_USER_AGENT)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            updateSessionCookies(session, response);
            log.info("二维码登录基础Cookie初始化完成: sessionIdLength={}, Cookie字段数={}",
                    safeLength(session.getSessionId()), session.getCookies().size());
        } catch (Exception e) {
            log.warn("二维码登录基础Cookie初始化失败，将继续官方扫码流程: errorType={}",
                    e.getClass().getSimpleName());
        }
    }

    
    /**
     * 获取_m_h5_tk token
     * 这个token是闲鱼API调用必需的，用于签名验证
     */
    private void getMh5tk(QRLoginSession session) throws IOException {
        log.info("开始获取_m_h5_tk token...");
        
        Map<String, Object> data = new HashMap<>();
        data.put("bizScene", "home");
        String dataStr = gson.toJson(data);
        long t = System.currentTimeMillis();
        String appKey = XianyuProtocolProfile.H5_APP_KEY;
        
        // 第一次请求获取cookie
        Request request = new Request.Builder()
                .url(API_H5_TK)
                .headers(generateApiHeaders())
                .header("Cookie", sessionCookieHeader(session))
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                // 提取cookie（注意：Cookie名称是 _m_h5_tk，带下划线前缀）
                List<String> cookieHeaders = response.headers("Set-Cookie");
                updateSessionCookies(session, response);
                log.debug("从响应中提取Cookie，数量: {}", cookieHeaders.size());
                
                // 获取 _m_h5_tk（注意下划线前缀）
                String mh5tk = session.getCookies().get("_m_h5_tk");
                String token = "";
                if (mh5tk != null && mh5tk.contains("_")) {
                    token = mh5tk.split("_")[0];
                    log.info("提取到_m_h5_tk token，长度: {}", token.length());
                } else {
                    log.warn("未找到_m_h5_tk，当前Cookie字段数: {}", session.getCookies().size());
                }
                
                // 生成签名
                String signInput = token + "&" + t + "&" + appKey + "&" + dataStr;
                String sign = md5(signInput);
                
                // 构造请求参数
                HttpUrl url = HttpUrl.parse(API_H5_TK).newBuilder()
                        .addQueryParameter("jsv", "2.7.2")
                        .addQueryParameter("appKey", appKey)
                        .addQueryParameter("t", String.valueOf(t))
                        .addQueryParameter("sign", sign)
                        .addQueryParameter("v", "1.0")
                        .addQueryParameter("type", "originaljson")
                        .addQueryParameter("dataType", "json")
                        .addQueryParameter("timeout", "20000")
                        .addQueryParameter("api", XianyuProtocolProfile.H5_TOKEN_REFRESH_API)
                        .addQueryParameter("data", dataStr)
                        .build();
                
                // 第二次请求，刷新token
                Request request2 = new Request.Builder()
                        .url(url)
                        .headers(generateApiHeaders())
                        .header("Cookie", CookieUtils.formatCookies(session.getCookies()))
                        .post(RequestBody.create(new byte[0]))
                        .build();
                
                try (Response response2 = httpClient.newCall(request2).execute()) {
                    if (response2.isSuccessful()) {
                        // 第二次请求可能会更新_m_h5_tk
                        updateSessionCookies(session, response2);
                        log.info("_m_h5_tk获取成功: sessionIdLength={}, Cookie字段数: {}",
                                safeLength(session.getSessionId()), session.getCookies().size());
                    } else {
                        log.warn("第二次请求失败，状态码: {}", response2.code());
                    }
                }
            } else {
                log.error("获取_m_h5_tk失败，状态码: {}", response.code());
            }
        }
    }
    
    /**
     * 获取登录参数
     */
    private Map<String, String> getLoginParams(QRLoginSession session) throws IOException {
        HttpUrl url = HttpUrl.parse(API_MINI_LOGIN).newBuilder()
                .addQueryParameter("lang", "zh_cn")
                .addQueryParameter("appName", "xianyu")
                .addQueryParameter("appEntrance", "web")
                .addQueryParameter("styleType", "vertical")
                .addQueryParameter("bizParams", "")
                .addQueryParameter("notLoadSsoView", "false")
                .addQueryParameter("notKeepLogin", "false")
                .addQueryParameter("isMobile", "false")
                .addQueryParameter("qrCodeFirst", "false")
                .addQueryParameter("stie", "77")
                .addQueryParameter("rnd", String.valueOf(Math.random()))
                .build();
        
        Request request = new Request.Builder()
                .url(url)
                .headers(generateHeaders())
                .header("Cookie", CookieUtils.formatCookies(session.getCookies()))
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                updateSessionCookies(session, response);
                String html = response.body().string();
                log.debug("获取登录页面HTML长度: {}", html.length());
                
                // 正则匹配需要的json数据
                Pattern pattern = Pattern.compile("window\\.viewData\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);
                Matcher matcher = pattern.matcher(html);
                
                if (matcher.find()) {
                    String jsonString = matcher.group(1);
                    log.debug("提取到viewData，长度: {}", jsonString.length());
                    
                    JsonObject viewData = gson.fromJson(jsonString, JsonObject.class);
                    JsonObject loginFormData = viewData.getAsJsonObject("loginFormData");
                    
                    if (loginFormData != null) {
                        Map<String, String> params = new HashMap<>();
                        loginFormData.entrySet().forEach(entry -> {
                            if (entry.getValue().isJsonPrimitive()) {
                                params.put(entry.getKey(), entry.getValue().getAsString());
                            } else {
                                params.put(entry.getKey(), entry.getValue().toString());
                            }
                        });
                        params.put("umidTag", "SERVER");
                        session.getParams().putAll(params);
                        log.info("获取登录参数成功: sessionIdLength={}, 参数数量: {}",
                                safeLength(session.getSessionId()), params.size());
                        return params;
                    } else {
                        log.error("viewData中没有loginFormData字段，viewData keys: {}", viewData.keySet());
                    }
                } else {
                    log.error("未匹配到window.viewData，尝试查找其他模式");
                    // 尝试其他可能的模式
                    Pattern pattern2 = Pattern.compile("var\\s+viewData\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);
                    Matcher matcher2 = pattern2.matcher(html);
                    if (matcher2.find()) {
                        String jsonString = matcher2.group(1);
                        log.debug("使用备用模式提取到viewData");
                        JsonObject viewData = gson.fromJson(jsonString, JsonObject.class);
                        JsonObject loginFormData = viewData.getAsJsonObject("loginFormData");
                        if (loginFormData != null) {
                            Map<String, String> params = new HashMap<>();
                            loginFormData.entrySet().forEach(entry -> {
                                if (entry.getValue().isJsonPrimitive()) {
                                    params.put(entry.getKey(), entry.getValue().getAsString());
                                } else {
                                    params.put(entry.getKey(), entry.getValue().toString());
                                }
                            });
                            params.put("umidTag", "SERVER");
                            session.getParams().putAll(params);
                            log.info("获取登录参数成功(备用模式): sessionIdLength={}",
                                    safeLength(session.getSessionId()));
                            return params;
                        }
                    }
                }
                
                // 如果都失败了，保存HTML用于调试
                log.error("无法提取登录参数，HTML长度: {}", html.length());
                
                // 尝试直接查找所有可能的参数
                Map<String, String> params = extractParamsFromHtml(html);
                if (!params.isEmpty()) {
                    params.put("umidTag", "SERVER");
                    session.getParams().putAll(params);
                    log.info("使用备用方法提取到参数: {}", params.keySet());
                    return params;
                }
                
                throw new BusinessException("未找到loginFormData");
            }
            throw new BusinessException("获取登录参数失败，HTTP状态码: " + response.code());
        }
    }

    
    @Override
    public QRLoginResponse generateQRCode(Long targetAccountId) {
        try {
            // 创建新会话
            String sessionId = UUID.randomUUID().toString();
            QRLoginSession session = new QRLoginSession(sessionId, targetAccountId);
            
            // 1. 初始化同一官方登录会话需要的基础Cookie
            bootstrapInitialCookies(session);

            // 2. 获取m_h5_tk
            getMh5tk(session);
            
            // 3. 获取登录参数
            Map<String, String> loginParams = getLoginParams(session);
            
            // 4. 生成二维码
            HttpUrl.Builder urlBuilder = HttpUrl.parse(API_GENERATE_QR).newBuilder();
            loginParams.forEach(urlBuilder::addQueryParameter);
            
            Request request = new Request.Builder()
                    .url(urlBuilder.build())
                    .headers(generateApiHeaders())
                    .header("Cookie", sessionCookieHeader(session))
                    .get()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    updateSessionCookies(session, response);
                    String responseBody = response.body().string();
                    log.debug("二维码接口响应已接收，HTTP状态: {}，响应长度: {}",
                            response.code(), responseBody.length());
                    
                    JsonObject results = gson.fromJson(responseBody, JsonObject.class);
                    JsonObject content = results.getAsJsonObject("content");
                    
                    if (content != null && content.get("success").getAsBoolean()) {
                        JsonObject data = content.getAsJsonObject("data");
                        
                        // 更新会话参数
                        session.getParams().put("t", data.get("t").getAsString());
                        session.getParams().put("ck", data.get("ck").getAsString());
                        
                        // 获取二维码内容
                        String qrContent = data.get("codeContent").getAsString();
                        session.setQrContent(qrContent);
                        
                        // 生成二维码图片（base64格式）
                        String qrDataUrl = generateQRCodeImage(qrContent);
                        session.setQrCodeUrl(qrDataUrl);
                        session.setStatus("waiting");
                        
                        // 保存会话
                        sessions.put(sessionId, session);
                        
                        if (!submitMonitorTask(session)) {
                            return new QRLoginResponse(false, "生成二维码失败");
                        }
                        
                        log.info("二维码生成成功: sessionIdLength={}, 更新现有账号={}",
                                safeLength(sessionId), targetAccountId != null);
                        return new QRLoginResponse(true, sessionId, qrDataUrl, null);
                    } else {
                        return new QRLoginResponse(false, "获取登录二维码失败");
                    }
                }
            }
            
            return new QRLoginResponse(false, "生成二维码失败");
            
        } catch (Exception e) {
            log.error("二维码生成过程中发生异常: errorType={}", e.getClass().getSimpleName());
            return new QRLoginResponse(false, "生成二维码失败");
        }
    }
    
    /**
     * 从HTML中提取参数（备用方法）
     */
    private Map<String, String> extractParamsFromHtml(String html) {
        Map<String, String> params = new HashMap<>();
        
        // 尝试提取常见的参数
        String[] paramNames = {"appName", "appEntrance", "hsiz", "rnd", "bizParams", 
                               "isMobile", "lang", "returnUrl", "fromSite", "umidToken"};
        
        for (String paramName : paramNames) {
            Pattern pattern = Pattern.compile("\"" + paramName + "\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                params.put(paramName, matcher.group(1));
                log.debug("提取到参数 {}: valueLength={}", paramName, matcher.group(1).length());
            }
        }
        
        return params;
    }
    
    /**
     * 生成二维码图片（Base64格式）
     */
    private String generateQRCodeImage(String content) throws WriterException, IOException {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 2);
        
        BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, 300, 300, hints);
        BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(bitMatrix);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(qrImage, "PNG", baos);
        byte[] imageBytes = baos.toByteArray();
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        
        return "data:image/png;base64," + base64;
    }

    private boolean submitMonitorTask(QRLoginSession session) {
        try {
            qrLoginExecutor.execute(() -> runMonitorTask(session));
            return true;
        } catch (RuntimeException e) {
            sessions.remove(session.getSessionId(), session);
            clearSessionCredentials(session);
            log.error("二维码监控任务提交失败: sessionIdLength={}, errorType={}",
                    safeLength(session.getSessionId()), e.getClass().getSimpleName());
            return false;
        }
    }

    private void runMonitorTask(QRLoginSession session) {
        try {
            monitorQRStatus(session.getSessionId());
        } catch (RuntimeException e) {
            session.setStatus("error");
            clearSessionCredentials(session);
            log.error("二维码监控任务执行失败: sessionIdLength={}, errorType={}",
                    safeLength(session.getSessionId()), e.getClass().getSimpleName());
        }
    }

    
    /**
     * 监控二维码状态
     */
    void monitorQRStatus(String sessionId) {
        try {
            QRLoginSession session = sessions.get(sessionId);
            if (session == null) {
                return;
            }
            
            log.info("开始监控二维码状态: sessionIdLength={}", safeLength(sessionId));
            
            long maxWaitTime = 300000; // 5分钟
            long startTime = System.currentTimeMillis();
            
            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                if (Thread.currentThread().isInterrupted()) {
                    handleMonitorInterruption(session);
                    return;
                }
                try {
                    // 检查会话是否还存在
                    if (!sessions.containsKey(sessionId)) {
                        break;
                    }
                    
                    // 轮询二维码状态
                    String qrCodeStatus = pollQRCodeStatus(session);
                    
                    if ("CONFIRMED".equals(qrCodeStatus)) {
                        // 登录确认
                        log.info("扫码流程完成: sessionIdLength={}, status={}",
                                safeLength(sessionId), session.getStatus());
                        break;
                    } else if ("NEW".equals(qrCodeStatus)) {
                        // 二维码未被扫描，继续轮询
                    } else if ("EXPIRED".equals(qrCodeStatus)) {
                        // 二维码已过期
                        session.setStatus("expired");
                        log.info("二维码已过期: sessionIdLength={}", safeLength(sessionId));
                        break;
                    } else if ("SCANED".equals(qrCodeStatus)) {
                        // 二维码已被扫描，等待确认
                        if ("waiting".equals(session.getStatus())) {
                            session.setStatus("scanned");
                            log.info("二维码已扫描，等待确认: sessionIdLength={}", safeLength(sessionId));
                        }
                    } else {
                        // 用户取消确认
                        session.setStatus("cancelled");
                        log.info("用户取消登录: sessionIdLength={}", safeLength(sessionId));
                        break;
                    }
                    
                    Thread.sleep(800); // 每0.8秒检查一次
                    
                } catch (InterruptedException e) {
                    handleMonitorInterruption(session);
                    return;
                } catch (Exception e) {
                    log.error("监控二维码状态异常: errorType={}", e.getClass().getSimpleName());
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException interruptedException) {
                        handleMonitorInterruption(session);
                        return;
                    }
                }
            }
            
            // 超时处理
            if (session != null && !Arrays.asList(
                    "success", "expired", "cancelled", "verification_required", "account_mismatch", "error")
                    .contains(session.getStatus())) {
                session.setStatus("expired");
                log.info("二维码监控超时，标记为过期: sessionIdLength={}", safeLength(sessionId));
            }
            
        } catch (Exception e) {
            log.error("监控二维码状态失败: errorType={}", e.getClass().getSimpleName());
            QRLoginSession session = sessions.get(sessionId);
            if (session != null) {
                session.setStatus("expired");
            }
        }
    }

    private void handleMonitorInterruption(QRLoginSession session) {
        Thread.currentThread().interrupt();
        if (session != null) {
            session.setStatus("expired");
            clearSessionCredentials(session);
        }
        log.info("二维码监控任务已中断: sessionIdLength={}",
                session != null ? safeLength(session.getSessionId()) : 0);
    }
    
    /**
     * 轮询二维码状态
     */
    private boolean completeLoginToken(QRLoginSession session, String loginToken) throws IOException {
        HttpUrl url = HttpUrl.parse(API_LOGIN_TOKEN).newBuilder()
                .addQueryParameter("token", loginToken)
                .addQueryParameter("subFlow", "DIALOG_CHECK_LOGIN_RPC")
                .addQueryParameter("nextCode", "0018")
                .addQueryParameter("bizScene", "qrcode")
                .addQueryParameter("confirm", "true")
                .build();
        String deviceId = session.getCookies().getOrDefault("cna", "");
        Request request = new Request.Builder()
                .url(url)
                .headers(generateApiHeaders())
                .header("Cookie", sessionCookieHeader(session))
                .post(new FormBody.Builder().add("deviceId", deviceId).build())
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            updateSessionCookies(session, response);
            ResponseBody body = response.body();
            String responseBody = body != null ? body.string() : "";
            if (markVerificationRequired(session, responseBody)) {
                return false;
            }
            if (!response.isSuccessful()) {
                log.warn("登录票据交换失败: status={}", response.code());
                return false;
            }
            log.info("登录票据交换完成: sessionIdLength={}, Cookie字段数={}",
                    safeLength(session.getSessionId()), session.getCookies().size());
            return true;
        }
    }

    private boolean markVerificationRequired(QRLoginSession session, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }
        try {
            JsonObject root = gson.fromJson(responseBody, JsonObject.class);
            List<JsonObject> candidates = new ArrayList<>();
            candidates.add(root);
            if (root.has("content") && root.get("content").isJsonObject()) {
                JsonObject content = root.getAsJsonObject("content");
                candidates.add(content);
                if (content.has("data") && content.get("data").isJsonObject()) {
                    candidates.add(content.getAsJsonObject("data"));
                }
            }
            for (JsonObject candidate : candidates) {
                if (candidate.has("iframeRedirect")
                        && !candidate.get("iframeRedirect").isJsonNull()
                        && candidate.get("iframeRedirect").getAsBoolean()) {
                    String officialCheckLink = candidate.has("iframeRedirectUrl")
                            && !candidate.get("iframeRedirectUrl").isJsonNull()
                            ? candidate.get("iframeRedirectUrl").getAsString()
                            : null;
                    session.setStatus("verification_required");
                    session.setVerificationUrl(officialCheckLink);
                    log.warn("账号仍需闲鱼官方验证: sessionIdLength={}, verificationUrlLength={}",
                            safeLength(session.getSessionId()), safeLength(officialCheckLink));
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("登录票据响应不是可解析JSON，按HTTP状态继续: errorType={}",
                    e.getClass().getSimpleName());
        }
        return false;
    }

    private void refreshPostLoginCookies(QRLoginSession session) throws IOException {
        HttpUrl url = HttpUrl.parse(API_POST_LOGIN_REFRESH).newBuilder()
                .addQueryParameter("jsv", "2.7.2")
                .addQueryParameter("appKey", XianyuProtocolProfile.H5_APP_KEY)
                .addQueryParameter("t", String.valueOf(System.currentTimeMillis()))
                .addQueryParameter("sign", "")
                .addQueryParameter("v", "1.0")
                .addQueryParameter("type", "originaljson")
                .addQueryParameter("dataType", "json")
                .addQueryParameter("timeout", "20000")
                .addQueryParameter("api", "mtop.idle.web.user.page.nav")
                .addQueryParameter("sessionOption", "AutoLoginOnly")
                .addQueryParameter("spm_cnt", "a21ybx.home.0.0")
                .build();
        Request request = new Request.Builder()
                .url(url)
                .headers(generateMtopHeaders())
                .header("Cookie", sessionCookieHeader(session))
                .post(new FormBody.Builder().add("data", "{}").build())
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            updateSessionCookies(session, response);
            if (!response.isSuccessful()) {
                log.warn("登录后H5 Cookie刷新失败: status={}", response.code());
                return;
            }
            log.info("登录后H5 Cookie刷新完成: sessionIdLength={}, Cookie字段数={}",
                    safeLength(session.getSessionId()), session.getCookies().size());
        }
    }

    String pollQRCodeStatus(QRLoginSession session) throws IOException {
        FormBody.Builder formBuilder = new FormBody.Builder();
        session.getParams().forEach(formBuilder::add);
        
        Request request = new Request.Builder()
                .url(API_SCAN_STATUS)
                .headers(generateApiHeaders())
                .header("Cookie", CookieUtils.formatCookies(session.getCookies()))
                .post(formBuilder.build())
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                updateSessionCookies(session, response);
                String responseBody = response.body().string();
                JsonObject results = gson.fromJson(responseBody, JsonObject.class);
                JsonObject content = results.getAsJsonObject("content");
                
                if (content != null) {
                    JsonObject data = content.getAsJsonObject("data");
                    if (data != null) {
                        String qrCodeStatus = data.get("qrCodeStatus").getAsString();
                        
                        if ("CONFIRMED".equals(qrCodeStatus)) {
                            // 检查是否需要风控验证
                            if (data.has("iframeRedirect") && data.get("iframeRedirect").getAsBoolean()) {
                                session.setStatus("verification_required");
                                String iframeUrl = null;
                                if (data.has("iframeRedirectUrl") && !data.get("iframeRedirectUrl").isJsonNull()) {
                                    iframeUrl = data.get("iframeRedirectUrl").getAsString();
                                }
                                session.setVerificationUrl(iframeUrl);
                                log.warn("⚠️ 账号被风控，需要手机验证");
                                log.warn("   - 会话ID长度: {}", safeLength(session.getSessionId()));
                                log.warn("   - 验证URL长度: {}", safeLength(iframeUrl));
                            } else {
                                // 扫码确认只是第一阶段。继续交换官方登录票据并刷新登录后的H5 Cookie，
                                // 确保保存的是完整登录态，而不是仅能通过二维码轮询的临时Cookie。
                                log.info("扫码确认成功，开始交换官方登录票据");
                                session.setStatus("processing");
                                String loginToken = data.has("token") && !data.get("token").isJsonNull()
                                        ? data.get("token").getAsString()
                                        : data.has("lgToken") && !data.get("lgToken").isJsonNull()
                                                ? data.get("lgToken").getAsString()
                                                : null;
                                if (loginToken != null && !loginToken.isBlank()) {
                                    if (!completeLoginToken(session, loginToken)) {
                                        if (!"verification_required".equals(session.getStatus())) {
                                            session.setStatus("error");
                                        }
                                        return qrCodeStatus;
                                    }
                                } else if (!session.getCookies().containsKey("unb")) {
                                    log.warn("扫码已确认，但官方响应未返回登录票据或登录Cookie");
                                    session.setStatus("error");
                                    return qrCodeStatus;
                                }
                                refreshPostLoginCookies(session);
                                if (session.getUnb() == null || session.getUnb().isBlank()) {
                                    session.setUnb(session.getCookies().get("unb"));
                                }
                                saveCookieToDatabase(session);
                            }
                        }
                        
                        return qrCodeStatus;
                    }
                }
            }
        }
        
        return "NEW";
    }

    
    @Override
    public QRStatusResponse getSessionStatus(String sessionId) {
        QRStatusResponse response = new QRStatusResponse();
        QRLoginSession session = sessions.get(sessionId);
        
        if (session == null) {
            response.setStatus("not_found");
            response.setMessage("会话不存在或已过期");
            return response;
        }
        
        if (session.isExpired() && !Arrays.asList("success", "account_mismatch", "error")
                .contains(session.getStatus())) {
            session.setStatus("expired");
        }
        
        // 转换后端状态为前端期望的状态
        String frontendStatus = convertToFrontendStatus(session.getStatus());
        response.setStatus(frontendStatus);
        // 根据状态设置详细的消息
        switch (session.getStatus()) {
            case "waiting":
                response.setMessage("等待扫码...");
                break;
            case "scanned":
                response.setMessage("已扫码，等待确认...");
                break;
            case "processing":
                response.setMessage("登录已确认，正在安全保存账号凭据...");
                break;
            case "success":
                response.setMessage(session.getTargetAccountId() == null
                        ? "登录成功！账号已添加"
                        : "登录成功！账号凭据已更新");
                response.setAccountId(session.getPersistedAccountId());
                response.setAccountNote(session.getPersistedAccountNote());
                response.setIsNewAccount(session.getNewAccount());
                break;
            case "account_mismatch":
                response.setMessage("扫码账号与当前账号不匹配，未覆盖当前账号凭据");
                break;
            case "error":
                response.setMessage("账号凭据保存失败，请重新扫码");
                break;
            case "expired":
                response.setMessage("二维码已过期，请重新生成");
                break;
            case "cancelled":
                response.setMessage("用户取消登录");
                break;
            case "verification_required":
                response.setMessage("账号被风控，需要手机验证");
                if (session.getVerificationUrl() != null) {
                    response.setVerificationUrl(session.getVerificationUrl());
                }
                break;
            default:
                response.setMessage("未知状态");
                break;
        }
        
        return response;
    }
    
    /**
     * 转换后端状态为前端期望的状态
     * 后端凭据仅用于持久化，转换后的响应只包含流程状态元数据。
     */
    private String convertToFrontendStatus(String backendStatus) {
        switch (backendStatus) {
            case "waiting":
                return "pending";
            case "success":
                return "confirmed";
            case "processing":
                return "pending";
            case "scanned":
            case "expired":
            case "cancelled":
            case "verification_required":
            case "account_mismatch":
            case "error":
                return backendStatus;
            default:
                return "pending";
        }
    }
    
    @Override
    public void cleanupExpiredSessions() {
        List<String> expiredSessions = new ArrayList<>();
        sessions.forEach((sessionId, session) -> {
            if (session.isExpired()) {
                expiredSessions.add(sessionId);
            }
        });
        
        expiredSessions.forEach(sessionId -> {
            sessions.remove(sessionId);
            log.info("清理过期会话: sessionIdLength={}", safeLength(sessionId));
        });
    }
    
    /**
     * 在后端完成新增或更新，避免可复用凭据经过浏览器。
     */
    private void saveCookieToDatabase(QRLoginSession session) {
        try {
            String unb = session.getUnb();
            if (unb == null || unb.isEmpty()) {
                log.error("UNB为空，无法保存扫码凭据: sessionIdLength={}",
                        safeLength(session.getSessionId()));
                session.setStatus("error");
                clearSessionCredentials(session);
                return;
            }
            
            // 检查关键Cookie字段
            Map<String, String> cookies = session.getCookies();
            // Some QR confirmation responses expose UNB separately from Set-Cookie.
            // Persist it with the rest of the session so later WebSocket handshakes
            // receive the same authenticated identity.
            cookies.putIfAbsent("unb", unb);
            log.info("准备保存Cookie到数据库，Cookie字段数: {}", cookies.size());
            
            // 提取 _m_h5_tk
            String mH5Tk = cookies.get("_m_h5_tk");
            if (mH5Tk == null || mH5Tk.isEmpty()) {
                log.warn("Cookie中缺少_m_h5_tk字段，后续API调用可能失败");
            } else {
                log.info("_m_h5_tk已包含，长度: {}", mH5Tk.length());
            }
            
            // 格式化Cookie字符串
            String cookieText = CookieUtils.formatCookies(cookies);
            log.info("格式化后的Cookie长度: {}", cookieText.length());

            Long targetAccountId = session.getTargetAccountId();
            Long persistedAccountId;
            if (targetAccountId == null) {
                String accountNote = "账号_" + unb.substring(0, Math.min(8, unb.length()));
                persistedAccountId = accountService.saveAccountAndCookie(
                        accountNote, unb, cookieText, mH5Tk);
            } else {
                String currentUnb = accountService.getXianyuUserId(targetAccountId);
                if (currentUnb != null && !Objects.equals(currentUnb, unb)) {
                    log.warn("扫码账号与目标账号不匹配: accountId={}", targetAccountId);
                    session.setStatus("account_mismatch");
                    clearSessionCredentials(session);
                    return;
                }
                if (!accountService.updateAccountCookie(targetAccountId, unb, cookieText)) {
                    log.error("扫码更新账号凭据失败: accountId={}", targetAccountId);
                    session.setStatus("error");
                    clearSessionCredentials(session);
                    return;
                }
                persistedAccountId = targetAccountId;
            }

            if (persistedAccountId == null || persistedAccountId <= 0) {
                log.error("保存扫码凭据失败：accountId为空");
                session.setStatus("error");
                clearSessionCredentials(session);
                return;
            }

            // 扫码成功后回填闲鱼资料（昵称/头像/信用分）；失败不阻断登录流程。
            boolean newAccount = targetAccountId == null;
            try {
                xianyuUserProfileService.refreshAccountProfile(persistedAccountId);
            } catch (Exception e) {
                log.warn("扫码后刷新闲鱼资料异常: accountId={}, errorType={}",
                        persistedAccountId, e.getClass().getSimpleName());
            }
            session.setNewAccount(newAccount);
            session.setPersistedAccountId(persistedAccountId);
            XianyuAccount persistedAccount = accountService.getById(persistedAccountId);
            session.setPersistedAccountNote(persistedAccount != null ? persistedAccount.getAccountNote() : null);

            try {
                if (webSocketTokenService != null) {
                    webSocketTokenService.clearCaptchaWait(persistedAccountId);
                }
                if (webSocketService != null) {
                    if (!webSocketService.resumeAutoConnectionAfterCredentialUpdate(persistedAccountId)) {
                        log.warn("扫码凭据已保存，但无法恢复WebSocket自动连接: accountId={}",
                                persistedAccountId);
                    }
                }
            } catch (Exception e) {
                log.warn("扫码凭据已保存，WebSocket将在后台巡检时恢复: accountId={}, errorType={}",
                        persistedAccountId, e.getClass().getSimpleName());
            }

            session.setStatus("success");
            clearSessionCredentials(session);
            log.info("扫码凭据已在后端保存: accountId={}, 更新现有账号={}",
                    persistedAccountId, targetAccountId != null);
        } catch (Exception e) {
            log.error("保存扫码凭据失败: sessionIdLength={}, errorType={}",
                    safeLength(session.getSessionId()), e.getClass().getSimpleName());
            session.setStatus("error");
            clearSessionCredentials(session);
        }
    }

    private void clearSessionCredentials(QRLoginSession session) {
        session.getCookies().clear();
        session.getParams().clear();
        session.setUnb(null);
        session.setQrContent(null);
        session.setQrCodeUrl(null);
        session.setVerificationUrl(null);
    }

    private static int safeLength(String value) {
        return value != null ? value.length() : 0;
    }
    
    /**
     * MD5加密
     */
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (Exception e) {
            log.error("MD5加密失败: errorType={}", e.getClass().getSimpleName());
            return "";
        }
    }
}
