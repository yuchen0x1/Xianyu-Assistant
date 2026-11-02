package com.feijimiao.xianyuassistant.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 在 Windows 上，Java 21 的所有 NIO Selector 实现（WEPoll/WindowsSelector）底层均改用
 * AF_UNIX 管道，而部分受限环境（沙盒 bash）不支持这一系统调用，导致 Tomcat 启动失败。
 *
 * <p>NIO2（Http11Nio2Protocol）使用 Windows IOCP 异步 I/O，完全绕开 Selector/PipeImpl，
 * 可在上述受限环境中正常启动。
 */
@Configuration
public class TomcatNio2Config {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatNio2Customizer() {
        return factory -> factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");
    }
}
