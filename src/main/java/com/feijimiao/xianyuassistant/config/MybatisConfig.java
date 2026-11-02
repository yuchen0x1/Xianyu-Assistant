package com.feijimiao.xianyuassistant.config;

import jakarta.annotation.PostConstruct;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * MyBatis配置类
 * 强制注册自定义类型处理器
 */
@Configuration
public class MybatisConfig {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @PostConstruct
    public void registerTypeHandlers() {
        TypeHandlerRegistry registry = sqlSessionFactory.getConfiguration().getTypeHandlerRegistry();
        // 必须同时占住 null jdbcType 槽位：MyBatis-Plus 的 insert/updateById/selectList
        // 绑定参数时不携带 jdbcType，若只注册 TIMESTAMP 槽位，这些语句会落到
        // MyBatis 默认的 LocalDateTimeTypeHandler（ps.setObject），
        // sqlite-jdbc 驱动按 UTC 写入导致时间整体偏移 8 小时。
        registry.register(LocalDateTime.class, CustomLocalDateTimeTypeHandler.class);
        registry.register(LocalDateTime.class, JdbcType.TIMESTAMP, CustomLocalDateTimeTypeHandler.class);
    }
}