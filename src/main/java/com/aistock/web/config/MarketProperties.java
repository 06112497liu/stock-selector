package com.aistock.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 市场配置:code 小篮子与缓存目录从 application.yml 读取,<b>不写死在 Java 业务里</b>。
 *
 * <pre>
 * market:
 *   cache-dir: ./.cache
 *   us: [AAPL, MSFT, NVDA, ...]
 *   cn: ["600519", "000001", ...]
 * </pre>
 */
@ConfigurationProperties(prefix = "market")
public class MarketProperties {

    /** 行情缓存(SQLite)目录。 */
    private String cacheDir = "./.cache";

    /** 美股(Yahoo)code 篮子。 */
    private List<String> us = new ArrayList<>();

    /** A 股(东财)code 篮子。 */
    private List<String> cn = new ArrayList<>();

    public String getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    public List<String> getUs() {
        return us;
    }

    public void setUs(List<String> us) {
        this.us = us;
    }

    public List<String> getCn() {
        return cn;
    }

    public void setCn(List<String> cn) {
        this.cn = cn;
    }
}
