package com.feijimiao.xianyuassistant.config;

import com.feijimiao.xianyuassistant.interceptor.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Web MVC 配置
 * 支持 Vue Router 的 History 模式
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**", "/ai/**")
                .excludePathPatterns("/api/login/**", "/api/system/version", "/api/system/health", "/actuator/health/**", "/actuator/info");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 尝试获取请求的资源
                        Resource requestedResource = location.createRelative(resourcePath);
                        
                        // 如果资源存在且可读，直接返回（静态文件、API等）
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }
                        
                        // API 与 actuator 端点必须交给 Spring 处理，不能回退成 index.html。
                        // 否则 /actuator/prometheus 之类的端点会被前端 SPA 兜底伪装成
                        // HTTP 200 + HTML，调用方以为指标抓取成功，实际拿到的是页面。
                        if (resourcePath.startsWith("api/") || isActuatorPath(resourcePath)) {
                            return null;
                        }
                        
                        // 其他情况返回 index.html，让 Vue Router 处理
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }

    private static boolean isActuatorPath(String resourcePath) {
        return resourcePath.startsWith("actuator/") || resourcePath.equals("actuator");
    }
}
