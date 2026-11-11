package com.feijimiao.xianyuassistant;

import com.feijimiao.xianyuassistant.service.update.SelfUpdateService;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableAsync
@SpringBootApplication(exclude = {
    OpenAiChatAutoConfiguration.class,
    OpenAiEmbeddingAutoConfiguration.class,
    OpenAiAudioSpeechAutoConfiguration.class,
    OpenAiAudioTranscriptionAutoConfiguration.class,
    OpenAiImageAutoConfiguration.class,
    OpenAiModerationAutoConfiguration.class
})
public class XianYuAssistantApplication {

    public static void main(String[] args) {
        // 在线自更新 restart-mode=self 的握手：新进程在此等待旧进程退出（释放端口）后再启动。
        SelfUpdateService.awaitRestartHandshake();
        // Windows exit 模式的短暂置换助手只负责复制已校验的新包，
        // 不应继续启动第二个 Spring 服务实例。
        if (SelfUpdateService.isSwapOnlyProcess()) {
            return;
        }
        SpringApplication.run(XianYuAssistantApplication.class, args);
    }

}
