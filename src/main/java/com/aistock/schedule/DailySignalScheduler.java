package com.aistock.schedule;

import com.aistock.web.PanelCache;
import com.aistock.web.SignalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 每日定时任务:到点(默认收盘后)自动刷新各 market 行情、生成当日选股信号并经
 * Server酱推送到微信,对标 Python 原版独立 compose scheduler service。
 *
 * <p><b>开关</b>:仅当 {@code scheduler.enabled=true} 时才注册本 Bean
 * ({@link ConditionalOnProperty})——本地开发 / 测试默认 false,不会误触发,也不影响现有装配。
 * cron 由 {@code scheduler.cron} 配,使用<b>服务器本地时区</b>;要跑的 market 由
 * {@code scheduler.markets}(逗号分隔)配。
 *
 * <p><b>边界</b>:只发纯名单建议(复用 {@link SignalService#push} 内的
 * {@code SignalFormatter} 免责文案),不算股数金额、不记账、不下单。
 *
 * <p><b>容错</b>:遍历 market 时每个 market 独立 try/catch——单个 market 拉数据 / 推送失败
 * 只记 warn 日志,不影响其它 market,整体不抛异常,调度线程不会崩。Server酱未配置 sendKey 时
 * {@link SignalService#push} 返回 {@code NOT_CONFIGURED},按 info 日志处理,不报错。
 */
@Component
@ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true")
public class DailySignalScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailySignalScheduler.class);

    private final SignalService signalService;
    private final PanelCache panelCache;
    private final List<String> markets;

    public DailySignalScheduler(SignalService signalService,
                                PanelCache panelCache,
                                @Value("${scheduler.markets:us,cn}") String marketsCsv) {
        this.signalService = signalService;
        this.panelCache = panelCache;
        this.markets = parseMarkets(marketsCsv);
    }

    /** 解析逗号分隔的 market 列表,规整、去空、去重(保序)。 */
    private static List<String> parseMarkets(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String s : csv.split(",")) {
            String m = SignalService.normalizeMarket(s.trim());
            if (!s.trim().isEmpty() && !out.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * 定时入口:到 cron 点触发,委托给 {@link #runOnce()}。本方法只负责调度,不做断言逻辑,
     * 便于测试直接调 {@link #runOnce()} 而无需等 cron。
     */
    @Scheduled(cron = "${scheduler.cron}")
    public void runDailyPush() {
        log.info("DailySignalScheduler 触发:markets={}", markets);
        runOnce();
    }

    /**
     * 遍历配置的 market,逐个「强制刷新行情缓存(下次重新触网拉最新)+ 推送当日信号」,
     * 收集每个 market 的结果摘要。每个 market 独立容错:单个失败不影响其它、整体不抛。
     *
     * @return market -> 结果摘要(推送结果 message 或失败原因),保序
     */
    public Map<String, String> runOnce() {
        Map<String, String> summary = new LinkedHashMap<>();
        for (String market : markets) {
            try {
                // 强制清缓存:下次 push 内部 bundle() 会重新触网拉最新数据。
                panelCache.invalidate(market);
                SignalService.PushResult result = signalService.push(market);
                String msg = result.message();
                summary.put(market, msg);
                log.info("[{}] 当日信号推送结果:{}", market, msg);
            } catch (Exception e) {
                // 单个 market 拉取 / 推送失败:只记日志、继续下一个,不让调度线程崩。
                String msg = "失败:" + e.toString();
                summary.put(market, msg);
                log.warn("[{}] 当日信号推送异常,跳过该 market:{}",
                        market, e.toString(), e);
            }
        }
        return summary;
    }

    /** 暴露已解析的 market 列表(测试 / 诊断用)。 */
    public List<String> markets() {
        return List.copyOf(markets);
    }
}
