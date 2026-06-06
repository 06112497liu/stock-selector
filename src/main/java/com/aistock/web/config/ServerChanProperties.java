package com.aistock.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server酱(微信推送)配置:从 application.yml / 环境变量读取 sendKey,默认空。
 *
 * <pre>
 * serverchan:
 *   sendkey: ${SERVERCHAN_SENDKEY:}
 * </pre>
 */
@ConfigurationProperties(prefix = "serverchan")
public class ServerChanProperties {

    /** Server酱 turbo SendKey;为空则不推送(按钮旁提示未配置)。 */
    private String sendkey = "";

    public String getSendkey() {
        return sendkey;
    }

    public void setSendkey(String sendkey) {
        this.sendkey = sendkey;
    }
}
