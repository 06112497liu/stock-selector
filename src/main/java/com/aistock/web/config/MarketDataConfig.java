package com.aistock.web.config;

import com.aistock.datasource.BarCache;
import com.aistock.datasource.EastMoneyClient;
import com.aistock.datasource.EastMoneySource;
import com.aistock.datasource.KlineSource;
import com.aistock.datasource.UsChineseNames;
import com.aistock.datasource.UsNameResolver;
import com.aistock.datasource.YahooClient;
import com.aistock.datasource.YahooSource;
import com.aistock.notify.ServerChanNotifier;
import com.aistock.service.MarketDataService;
import com.aistock.storage.ParamsStore;
import com.aistock.storage.Store;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 为「美股(Yahoo)」与「A 股(东财)」各装配一个 {@link MarketDataService} Bean。
 *
 * <p>两市复用同一 service 类,只是注入不同 {@link com.aistock.datasource.DataSource}
 * 与名称解析函数。code 篮子、缓存目录均来自 {@link MarketProperties}(application.yml),
 * 不写死在业务里。
 *
 * <p>名称解析函数(client::fetchName)在网络 / 代理失败时已降级返回 code 本身,
 * 且 {@link MarketDataService#names()} 为懒加载——Bean 构造期间不触网,启动安全。
 */
@Configuration
@EnableConfigurationProperties({MarketProperties.class, ServerChanProperties.class})
public class MarketDataConfig {

    /** Server酱微信推送客户端;sendKey 来自配置 / 环境变量,为空则发送时降级返回 false。 */
    @Bean
    public ServerChanNotifier serverChanNotifier(ServerChanProperties props) {
        return new ServerChanNotifier(props.getSendkey());
    }

    /**
     * 美股行情服务(Yahoo)。
     *
     * <p>名称解析走降级链 <b>内置中文译名 → Yahoo 英文 longName → code</b>:用
     * {@link UsNameResolver} 把 {@link UsChineseNames}(固定中文译名表)包在
     * {@code client::fetchName}(英文名,失败自降级 code)外面。译名表未命中时行为与
     * 原先完全一致(英文名,失败 code)。{@link MarketDataService} 的 nameCache 缓存
     * 语义不变(resolver 是纯函数)。
     */
    @Bean
    public MarketDataService usMarketDataService(MarketProperties props) {
        BarCache cache = new BarCache(cacheFile(props, "us.sqlite"));
        YahooClient client = new YahooClient();
        YahooSource source = new YahooSource(client, cache);
        UsNameResolver nameFn = new UsNameResolver(client::fetchName);
        return new MarketDataService(source, nameFn, client::fetchMarketCap, props.getUs());
    }

    /** A 股行情服务(东财)。 */
    @Bean
    public MarketDataService cnMarketDataService(MarketProperties props) {
        BarCache cache = new BarCache(cacheFile(props, "cn.sqlite"));
        EastMoneyClient client = new EastMoneyClient();
        EastMoneySource source = new EastMoneySource(client, cache);
        return new MarketDataService(source, client::fetchName, client::fetchMarketCap, props.getCn());
    }

    /** 美股 K 线数据源(Yahoo chart 接口);{@code @Qualifier("usKlineSource")} 注入。 */
    @Bean
    public KlineSource usKlineSource() {
        return new YahooClient();
    }

    /** A 股 K 线数据源(东财 kline 接口);{@code @Qualifier("cnKlineSource")} 注入。 */
    @Bean
    public KlineSource cnKlineSource() {
        return new EastMoneyClient();
    }

    /**
     * 美股账本(独立 SQLite,放 cache-dir;Docker 卷 /data 持久化,重启不丢)。
     * 用 {@code @Qualifier("usStore")} 注入。
     */
    @Bean
    public Store usStore(MarketProperties props) {
        return new Store(cacheFile(props, "us_ledger.sqlite"));
    }

    /**
     * A 股账本(独立 SQLite,放 cache-dir)。用 {@code @Qualifier("cnStore")} 注入。
     */
    @Bean
    public Store cnStore(MarketProperties props) {
        return new Store(cacheFile(props, "cn_ledger.sqlite"));
    }

    /**
     * 美股策略参数存储(独立 SQLite,放 cache-dir;Docker 卷持久化)。
     * 用 {@code @Qualifier("usParams")} 注入。
     */
    @Bean
    public ParamsStore usParams(MarketProperties props) {
        return new ParamsStore(cacheFile(props, "us_params.sqlite"));
    }

    /**
     * A 股策略参数存储(独立 SQLite,放 cache-dir)。用 {@code @Qualifier("cnParams")} 注入。
     */
    @Bean
    public ParamsStore cnParams(MarketProperties props) {
        return new ParamsStore(cacheFile(props, "cn_params.sqlite"));
    }

    private static String cacheFile(MarketProperties props, String name) {
        Path dir = Path.of(props.getCacheDir());
        dir.toFile().mkdirs();
        return dir.resolve(name).toString();
    }
}
