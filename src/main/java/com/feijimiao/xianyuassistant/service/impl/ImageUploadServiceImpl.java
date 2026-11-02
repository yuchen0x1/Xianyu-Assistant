package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.ImageUploadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class ImageUploadServiceImpl implements ImageUploadService {
    
    private static final String UPLOAD_URL = "https://stream-upload.goofish.com/api/upload.api?floderId=0&appkey=xy_chat&_input_charset=utf-8";
    private static final int MAX_WIDTH = 1920;
    private static final int MAX_HEIGHT = 1920;
    private static final int MAX_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MAX_REMOTE_IMAGE_SIZE = 10 * 1024 * 1024;
    private static final int MAX_SOURCE_DIMENSION = 8192;
    private static final long MAX_SOURCE_PIXELS = 20_000_000L;
    private static final int QUALITY = 85;

    @Value("${app.image-download.allowed-hosts:alicdn.com,goofish.com}")
    private String allowedImageHosts;
    
    @Autowired
    private XianyuAccountMapper accountMapper;
    
    @Autowired
    private AccountService accountService;
    
    private final OkHttpClient remoteImageHttpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(30))
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    private PinnedClientFactory pinnedClientFactory = this::createPinnedClient;

    private HostResolver hostResolver = InetAddress::getAllByName;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public ResultObject<String> uploadImage(Long accountId, byte[] imageData, String filename) {
        try {
            XianyuAccount account = accountMapper.selectById(accountId);
            if (account == null) {
                return ResultObject.failed("账号不存在");
            }
            
            String cookie = accountService.getCookieByAccountId(accountId);
            if (cookie == null || cookie.isEmpty()) {
                return ResultObject.failed("账号Cookie为空");
            }
            
            // 压缩图片
            byte[] compressedData = compressImage(imageData);
            if (compressedData == null) {
                return ResultObject.failed("图片压缩失败");
            }
            
            // 上传到闲鱼CDN
            String cdnUrl = uploadToGoofishCDN(cookie, compressedData, filename);
            if (cdnUrl == null) {
                return ResultObject.failed("上传到闲鱼CDN失败");
            }
            if ("COOKIE_EXPIRED".equals(cdnUrl)) {
                accountService.updateCookieStatus(accountId, 2);
                return ResultObject.failed("Cookie已过期，请刷新Cookie");
            }
            
            log.info("【账号{}】图片上传成功", accountId);
            return ResultObject.success(cdnUrl);
            
        } catch (Exception e) {
            log.error("上传图片失败: accountId={}, errorType={}",
                    accountId, e.getClass().getSimpleName());
            return ResultObject.failed("上传图片失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<String> uploadImageFromUrl(Long accountId, String imageUrl) {
        try {
            // 下载图片
            byte[] imageData = downloadImage(imageUrl);

            String filename = UUID.randomUUID().toString() + ".jpg";
            return uploadImage(accountId, imageData, filename);
            
        } catch (Exception e) {
            log.error("从URL上传图片失败: accountId={}, urlLength={}, errorType={}",
                    accountId, imageUrl != null ? imageUrl.length() : 0, e.getClass().getSimpleName());
            return ResultObject.failed("从URL上传图片失败: " + e.getMessage());
        }
    }
    
    byte[] compressImage(byte[] imageData) {
        try {
            BufferedImage image = readValidatedImage(imageData);
            
            if (image == null) {
                log.error("无法读取图片数据");
                return null;
            }
            
            int width = image.getWidth();
            int height = image.getHeight();
            
            // 调整尺寸
            if (width > MAX_WIDTH || height > MAX_HEIGHT) {
                double scale = Math.min((double) MAX_WIDTH / width, (double) MAX_HEIGHT / height);
                int newWidth = (int) (width * scale);
                int newHeight = (int) (height * scale);
                
                BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = resized.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.drawImage(image, 0, 0, newWidth, newHeight, null);
                g2d.dispose();
                image = resized;
                
                log.info("图片尺寸调整: {}x{} -> {}x{}", width, height, newWidth, newHeight);
            }
            
            // 转换为RGB模式
            if (image.getType() != BufferedImage.TYPE_INT_RGB) {
                BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = rgb.createGraphics();
                g2d.drawImage(image, 0, 0, null);
                g2d.dispose();
                image = rgb;
            }
            
            // 压缩为JPEG
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", bos);
            byte[] compressed = bos.toByteArray();
            
            // 如果还是太大，降低质量
            if (compressed.length > MAX_SIZE) {
                float quality = 0.5f;
                bos = new ByteArrayOutputStream();
                javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
                javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
                writer.setOutput(ImageIO.createImageOutputStream(bos));
                writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
                writer.dispose();
                compressed = bos.toByteArray();
                log.info("图片质量调整，文件大小: {}KB", compressed.length / 1024);
            }
            
            log.info("图片压缩完成: {}KB", compressed.length / 1024);
            return compressed;
            
        } catch (Exception e) {
            log.error("压缩图片失败", e);
            return null;
        }
    }

    private BufferedImage readValidatedImage(byte[] imageData) throws IOException {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(
                new ByteArrayInputStream(imageData))) {
            if (imageInput == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                return null;
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (!isSourceImageSizeAllowed(width, height)) {
                    log.warn("拒绝解码尺寸异常的图片: width={}, height={}, pixels={}",
                            width, height, pixels);
                    return null;
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    boolean isSourceImageSizeAllowed(int width, int height) {
        return width > 0 && height > 0
                && width <= MAX_SOURCE_DIMENSION && height <= MAX_SOURCE_DIMENSION
                && (long) width * height <= MAX_SOURCE_PIXELS;
    }
    
    private String uploadToGoofishCDN(String cookie, byte[] imageData, String filename) {
        try {
            String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            
            StringBuilder bodyBuilder = new StringBuilder();
            bodyBuilder.append("--").append(boundary).append("\r\n");
            bodyBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"\r\n");
            bodyBuilder.append("Content-Type: image/jpeg\r\n\r\n");
            
            byte[] header = bodyBuilder.toString().getBytes("UTF-8");
            byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(header);
            bos.write(imageData);
            bos.write(footer);
            byte[] body = bos.toByteArray();

            okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
                    body,
                    okhttp3.MediaType.parse("multipart/form-data; boundary=" + boundary)
            );

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(UPLOAD_URL)
                    .header("Cookie", cookie)
                    .header("Referer", XianyuProtocolProfile.WEB_REFERER)
                    .header("User-Agent", XianyuProtocolProfile.WEB_USER_AGENT)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("x-requested-with", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .post(requestBody)
                    .build();

            try (okhttp3.Response response = remoteImageHttpClient.newCall(request).execute()) {
                int statusCode = response.code();

                if (statusCode == 200) {
                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        log.error("上传响应体为空");
                        return null;
                    }
                    String responseStr = responseBody.string();
                    log.debug("上传响应已接收: responseLength={}", responseStr.length());

                    return parseUploadResponse(responseStr);
                } else if (statusCode == 302 || statusCode == 301) {
                    log.error("上传失败: HTTP {}，Cookie已过期（被重定向到登录页）", statusCode);
                    return "COOKIE_EXPIRED";
                } else {
                    log.error("上传失败: HTTP {}", statusCode);
                    return null;
                }
            }
            
        } catch (Exception e) {
            log.error("上传到闲鱼CDN失败: errorType={}", e.getClass().getSimpleName());
            return null;
        }
    }
    
    private String parseUploadResponse(String responseBody) {
        try {
            // 检查是否返回HTML（Cookie失效）
            if (responseBody.contains("<!DOCTYPE html>") || responseBody.contains("<html>")) {
                log.error("Cookie已失效，返回了登录页面");
                return "COOKIE_EXPIRED";
            }
            
            JsonNode root = objectMapper.readTree(responseBody);
            
            // 方式1: data.url
            if (root.has("data") && root.get("data").has("url")) {
                return root.get("data").get("url").asText();
            }
            
            // 方式2: object.url
            if (root.has("object") && root.get("object").has("url")) {
                return root.get("object").get("url").asText();
            }
            
            // 方式3: url
            if (root.has("url")) {
                return root.get("url").asText();
            }
            
            // 方式4: result.url
            if (root.has("result") && root.get("result").has("url")) {
                return root.get("result").get("url").asText();
            }
            
            // 方式5: data.fileUrl
            if (root.has("data") && root.get("data").has("fileUrl")) {
                return root.get("data").get("fileUrl").asText();
            }
            
            log.error("无法从响应中提取图片URL: responseLength={}", responseBody.length());
            return null;
            
        } catch (Exception e) {
            log.error("解析上传响应失败: responseLength={}, errorType={}",
                    responseBody != null ? responseBody.length() : 0, e.getClass().getSimpleName());
            return null;
        }
    }
    
    byte[] downloadImage(String imageUrl) throws IOException {
        ValidatedRemoteImage target = validateRemoteImage(imageUrl);
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(target.uri().toString())
                .header("User-Agent", XianyuProtocolProfile.WEB_USER_AGENT)
                .header("Accept", "image/*")
                .get()
                .build();

        Call.Factory client = pinnedClientFactory.create(target.host(), target.addresses());
        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (response.code() != 200) {
                throw new IOException("下载图片失败: HTTP " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("远程图片响应为空");
            }
            String contentType = response.header("Content-Type", "");
            if (contentType.isBlank() && body.contentType() != null) {
                contentType = body.contentType().toString();
            }
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                throw new IOException("远程地址未返回图片内容");
            }
            if (body.contentLength() > MAX_REMOTE_IMAGE_SIZE) {
                throw new IOException("远程图片大小不能超过10MB");
            }

            byte[] imageData = body.byteStream().readNBytes(MAX_REMOTE_IMAGE_SIZE + 1);
            if (imageData.length > MAX_REMOTE_IMAGE_SIZE) {
                throw new IOException("远程图片大小不能超过10MB");
            }
            return imageData;
        }
    }

    URI validateRemoteImageUri(String imageUrl) throws UnknownHostException {
        return validateRemoteImage(imageUrl).uri();
    }

    private ValidatedRemoteImage validateRemoteImage(String imageUrl) throws UnknownHostException {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("图片URL不能为空");
        }

        URI uri;
        try {
            uri = URI.create(imageUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图片URL格式无效");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("仅支持可信HTTPS图片地址");
        }

        String host;
        try {
            host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图片URL域名无效");
        }
        if (!isAllowedImageHost(host)) {
            throw new IllegalArgumentException("图片域名不在允许列表中");
        }

        InetAddress[] addresses = hostResolver.resolve(host);
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(this::isPrivateOrSpecialAddress)) {
            throw new IllegalArgumentException("图片地址不能指向本机或私有网络");
        }
        return new ValidatedRemoteImage(uri, host, List.copyOf(Arrays.asList(addresses)));
    }

    private Call.Factory createPinnedClient(String expectedHost, List<InetAddress> addresses) {
        return remoteImageHttpClient.newBuilder()
                .dns(hostname -> {
                    String normalizedHost;
                    try {
                        normalizedHost = IDN.toASCII(hostname).toLowerCase(Locale.ROOT);
                    } catch (IllegalArgumentException e) {
                        throw new UnknownHostException("无效的图片域名");
                    }
                    if (!expectedHost.equals(normalizedHost)) {
                        throw new UnknownHostException("拒绝解析非目标图片域名");
                    }
                    return addresses;
                })
                .build();
    }

    private boolean isAllowedImageHost(String host) {
        Set<String> allowedHosts = new LinkedHashSet<>();
        if (allowedImageHosts != null) {
            for (String configuredHost : allowedImageHosts.split(",")) {
                String normalized = configuredHost.trim().toLowerCase(Locale.ROOT);
                while (normalized.startsWith(".")) {
                    normalized = normalized.substring(1);
                }
                if (!normalized.isBlank()) {
                    allowedHosts.add(normalized);
                }
            }
        }
        return allowedHosts.stream().anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
    }

    private boolean isPrivateOrSpecialAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0
                    || first == 100 && second >= 64 && second <= 127
                    || first == 198 && (second == 18 || second == 19);
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            boolean mappedIpv4 = true;
            for (int i = 0; i < 10; i++) {
                mappedIpv4 &= bytes[i] == 0;
            }
            mappedIpv4 &= bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
            if (mappedIpv4) {
                byte[] ipv4Bytes = Arrays.copyOfRange(bytes, 12, 16);
                try {
                    return isPrivateOrSpecialAddress(InetAddress.getByAddress(ipv4Bytes));
                } catch (UnknownHostException impossible) {
                    return true;
                }
            }
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    @FunctionalInterface
    interface PinnedClientFactory {
        Call.Factory create(String host, List<InetAddress> addresses);
    }

    private record ValidatedRemoteImage(URI uri, String host, List<InetAddress> addresses) {
    }
}
