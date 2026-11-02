package com.feijimiao.xianyuassistant.notify;

/**
 * 通知消息体
 */
public class NotifyMessage {

    private String title;
    private String content;
    private NotifyLevel level;

    public NotifyMessage() {}

    public NotifyMessage(String title, String content, NotifyLevel level) {
        this.title = title;
        this.content = content;
        this.level = level;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public NotifyLevel getLevel() {
        return level;
    }

    public void setLevel(NotifyLevel level) {
        this.level = level;
    }

    public enum NotifyLevel {
        INFO,
        WARNING,
        ERROR
    }
}
