package com.feijimiao.xianyuassistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局 ObjectMapper 提供者。
 * 所有工具类通过此配置获取单例 ObjectMapper，避免重复创建实例。
 *
 * <p>注意：这里自定义的 @Primary ObjectMapper 会使 Spring Boot 自动装配的
 * ObjectMapper 退位，{@code Jackson2ObjectMapperBuilderCustomizer} 与
 * {@code spring.jackson.*} 配置都不会作用到它，因此 MVC 序列化所需的全部
 * 定制必须集中在本类完成。</p>
 */
@Configuration
public class ObjectMapperProvider {

    private static final String DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    private static ObjectMapper SHARED_MAPPER;

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATETIME_FORMAT);
        mapper.registerModule(new JavaTimeModule());
        SimpleModule localDateTimeModule = new SimpleModule();
        localDateTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(formatter));
        localDateTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(formatter));
        mapper.registerModule(localDateTimeModule);
        // 关闭时间戳输出，否则 LocalDateTime/Instant 会序列化成数组或数字而非字符串。
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.findAndRegisterModules();
        SHARED_MAPPER = mapper;
        return mapper;
    }

    /**
     * 供静态工具类获取共享的 ObjectMapper 实例。
     * 在 Spring 容器初始化完成前返回一个基础实例，初始化后返回 Spring 管理的实例。
     */
    public static ObjectMapper getSharedMapper() {
        if (SHARED_MAPPER != null) {
            return SHARED_MAPPER;
        }
        // 兜底：返回一个基础配置的实例
        ObjectMapper fallback = new ObjectMapper();
        fallback.findAndRegisterModules();
        return fallback;
    }
}
