package com.aistock.service;

import com.aistock.datasource.Bar;
import com.aistock.datasource.DataSource;
import com.aistock.feature.MarketPanel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * 装配服务层:把「一组标的代码(小篮子)」装配成可供选股 / 回测 / 推荐使用的数据,
 * 并提供标的的官方名称。
 *
 * <p>设计为<b>市场无关</b>:美股(Yahoo)与 A 股(东财)复用同一个 service 类,
 * 只是构造时注入不同的 {@link DataSource} 与名称解析函数:
 * <ul>
 *   <li>美股:{@code new MarketDataService(yahooSource, yahooClient::fetchName, yahooClient::fetchMarketCap, usCodes)}</li>
 *   <li>A 股:{@code new MarketDataService(eastMoneySource, eastMoneyClient::fetchName, eastMoneyClient::fetchMarketCap, aCodes)}</li>
 * </ul>
 *
 * <p><b>名称铁律</b>:名称一律通过注入的解析函数从真实数据源接口获取,绝不硬编码;
 * 解析函数本身在网络 / 代理失败时已降级返回 code 本身,本服务不会编造名称。
 *
 * <p><b>市值口径</b>:市值同样由注入的解析函数从真实 quote 接口获取(对称名称),
 * 任一步失败返回 {@link OptionalDouble#empty()} 降级(页面显示 N/A),绝不编造、绝不抛。
 *
 * <p>code 篮子作为配置由外部传入,不写死在业务里。名称 / 市值结果均缓存,避免重复请求。
 */
public final class MarketDataService {

    private final DataSource source;
    private final Function<String, String> nameResolver;
    private final Function<String, OptionalDouble> marketCapResolver;
    private final List<String> codes;

    /** 名称缓存:code -> name(首次 {@link #names()} 后填充)。 */
    private final Map<String, String> nameCache = new LinkedHashMap<>();

    /** 市值缓存:code -> 市值(首次查询后填充;降级项缓存为 {@link OptionalDouble#empty()})。 */
    private final Map<String, OptionalDouble> marketCapCache = new LinkedHashMap<>();

    /** buildPanel 并发拉取的最大线程数(篮子更小则取篮子大小)。 */
    private static final int MAX_FETCH_THREADS = 8;

    /**
     * @param source            提供日线的 datasource(自带缓存 + 降级)
     * @param nameResolver      名称解析函数(通常是某 client 的 {@code fetchName};失败需返回 code 本身)
     * @param marketCapResolver 市值解析函数(通常是某 client 的 {@code fetchMarketCap};失败需返回
     *                          {@link OptionalDouble#empty()};单位由市场决定:美股美元 / A 股人民币元)
     * @param codes             标的代码篮子(配置传入,顺序保留)
     */
    public MarketDataService(DataSource source,
                             Function<String, String> nameResolver,
                             Function<String, OptionalDouble> marketCapResolver,
                             List<String> codes) {
        this.source = source;
        this.nameResolver = nameResolver;
        this.marketCapResolver = marketCapResolver;
        this.codes = List.copyOf(codes);
    }

    /**
     * 标的代码篮子(不可变,顺序即配置顺序)。
     */
    public List<String> codes() {
        return codes;
    }

    /**
     * 对篮子里每个 code 调 {@link DataSource#updateBars(String)}(自带缓存 + 降级),
     * 组装成 {@link MarketPanel}。
     *
     * <p>拉取阶段<b>并发</b>执行(每只票一次网络 IO,串行是冷启动瓶颈):用有界线程池
     * 并行调用 {@code updateBars},线程数取 {@code min(篮子大小, 8)}。{@link DataSource}
     * 实现(自带 {@code BarCache},每操作独立连接 + busy_timeout)线程安全,可并发调用。
     *
     * <p><b>异常隔离</b>:单只票拉取抛异常不拖垮整批——该 code 视作无数据跳过,其余正常
     * 进面板,与原串行行为一致(原串行里 updateBars 内部已对网络失败降级用缓存;此处再
     * 兜底捕获意外异常)。
     *
     * <p><b>确定性</b>:并发结果按 {@code codes} 的原始顺序回填到 {@link LinkedHashMap},
     * 产出的面板与串行版等价(相同的票、相同的 bars、相同顺序)。
     *
     * <p>无任何 bar 的 code(数据源彻底取不到且无缓存)不会进入面板,避免污染横截面。
     *
     * @return 按交易日对齐的因子面板
     */
    public MarketPanel buildPanel() {
        // 空篮子:无需起线程池。
        if (codes.isEmpty()) {
            return new MarketPanel(new LinkedHashMap<>());
        }

        // 并发收集:code -> bars(只放非空结果)。ConcurrentHashMap 防并发写竞态。
        Map<String, List<Bar>> fetched = new ConcurrentHashMap<>();

        int threads = Math.min(codes.size(), MAX_FETCH_THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(codes.size());
            for (String code : codes) {
                Callable<Void> task = () -> {
                    try {
                        List<Bar> bars = source.updateBars(code);
                        if (bars != null && !bars.isEmpty()) {
                            fetched.put(code, bars);
                        }
                    } catch (RuntimeException e) {
                        // 异常隔离:单只票失败不拖垮整批,视作无数据跳过。
                        // updateBars 内部对网络/限流失败已降级用缓存,此处兜底意外异常。
                    }
                    return null;
                };
                futures.add(pool.submit(task));
            }
            // 等待全部完成(任务已吞掉自身异常,这里不会因业务异常中断)。
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException | RuntimeException e) {
                    // 任务体已隔离异常,理论到不了这里;到了也只跳过该结果。
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            // Java 17 的 ExecutorService 非 AutoCloseable,显式回收线程。
            pool.shutdown();
        }

        // 确定性组装:按 codes 原始顺序回填,等价于串行 LinkedHashMap 插入顺序。
        Map<String, List<Bar>> barsByCode = new LinkedHashMap<>();
        for (String code : codes) {
            List<Bar> bars = fetched.get(code);
            if (bars != null) {
                barsByCode.put(code, bars);
            }
        }
        return new MarketPanel(barsByCode);
    }

    /**
     * 取篮子里每个 code 的官方名称。失败项由解析函数降级为 code 本身(铁律:不编造)。
     * 结果缓存,重复调用不再触网。
     *
     * @return code -> name,覆盖篮子里所有 code(顺序保留)
     */
    public Map<String, String> names() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String code : codes) {
            String name = nameCache.get(code);
            if (name == null) {
                name = nameResolver.apply(code);
                // 解析函数约定失败返回 code;再兜底一次防止注入了返回 null 的函数。
                if (name == null) {
                    name = code;
                }
                nameCache.put(code, name);
            }
            out.put(code, name);
        }
        return out;
    }

    /**
     * 取单个 code 的公司市值(market cap)。解析函数失败 / 拿不到 → {@link OptionalDouble#empty()}
     * (降级,页面显示 N/A;铁律:不编造、不抛)。结果缓存,重复调用不再触网。
     *
     * @param code 标的代码
     * @return 市值(单位随市场:美股美元 / A 股人民币元);拿不到时 empty
     */
    public OptionalDouble marketCapOf(String code) {
        OptionalDouble cached = marketCapCache.get(code);
        if (cached != null) {
            return cached;
        }
        OptionalDouble cap;
        try {
            cap = marketCapResolver.apply(code);
            // 兜底:防止注入了返回 null 的函数。
            if (cap == null) {
                cap = OptionalDouble.empty();
            }
        } catch (RuntimeException e) {
            // 解析函数本应自降级,这里再兜底意外异常,绝不让市值拖垮页面。
            cap = OptionalDouble.empty();
        }
        marketCapCache.put(code, cap);
        return cap;
    }

    /**
     * 取篮子里每个 code 的市值。降级项为 {@link OptionalDouble#empty()}。结果缓存,
     * 重复调用不再触网。
     *
     * @return code -> 市值(覆盖篮子里所有 code,顺序保留)
     */
    public Map<String, OptionalDouble> marketCaps() {
        Map<String, OptionalDouble> out = new LinkedHashMap<>();
        for (String code : codes) {
            out.put(code, marketCapOf(code));
        }
        return out;
    }

    /**
     * 面板里最新的交易日(给前端展示用)。
     *
     * @return 最新交易日;面板为空时返回 {@code null}
     */
    public LocalDate latestDay(MarketPanel panel) {
        List<LocalDate> days = panel.tradingDays();
        return days.isEmpty() ? null : days.get(days.size() - 1);
    }

    /**
     * 篮子里每个 code 在最新交易日的现价(收盘价),给前端展示用。
     *
     * <p>仅包含在最新交易日确有收盘价的 code;当天停牌 / 无 bar 的 code 被跳过。
     *
     * @return code -> 最新收盘价;面板为空时返回空 map
     */
    public Map<String, Double> latestCloseByCode(MarketPanel panel) {
        Map<String, Double> out = new LinkedHashMap<>();
        LocalDate day = latestDay(panel);
        if (day == null) {
            return out;
        }
        for (String code : codes) {
            double close = panel.closeOn(code, day);
            if (!Double.isNaN(close)) {
                out.put(code, close);
            }
        }
        return out;
    }
}
