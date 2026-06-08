package com.aistock.web;

import com.aistock.datasource.BarCache;
import com.aistock.datasource.Bar;
import com.aistock.datasource.DataSource;
import com.aistock.datasource.EastMoneyClient;
import com.aistock.datasource.EastMoneySource;
import com.aistock.datasource.UsNameResolver;
import com.aistock.datasource.YahooClient;
import com.aistock.datasource.YahooSource;
import com.aistock.service.MarketDataService;
import com.aistock.storage.ParamsStore;
import com.aistock.storage.Store;
import com.aistock.storage.WatchlistStore;
import com.aistock.storage.WatchlistStore.WatchlistGroup;
import com.aistock.storage.WatchlistStore.WatchlistStock;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 自选股服务:
 * <ol>
 *   <li>分组 + 股票的增删改查(持久化到 {@link WatchlistStore})</li>
 *   <li>添加股票时校验代码:通过真实数据源 fetchName + updateBars
 *       能拿到有效名称 + 至少一根 bar 才算合法,否则返回友好错误</li>
 *   <li>为每个自选股分组动态构建对应的 {@link MarketDataService} / {@link Store} /
 *       {@link ParamsStore},使自选股分组能以 {@code wl_xxx} 为 market key
 *       无缝接入现有 {@link SignalService} / {@link PanelCache} 流程,
 *       与内置 us / cn 完全同级。</li>
 * </ol>
 *
 * <p>自选股分组 market_type 决定数据源:
 * <ul>
 *   <li>{@code "us"} → Yahoo(YahooSource + YahooClient::fetchName 经 UsNameResolver)</li>
 *   <li>{@code "cn"} → 东财(EastMoneySource + EastMoneyClient::fetchName)</li>
 * </ul>
 * 每个分组有独立的 bar 缓存 SQLite / 账本 / 参数库,互不干扰。
 */
@Service
public class WatchlistService {

    public record ValidationResult(boolean valid, String name, String errorMessage) {
        public static ValidationResult ok(String name) {
            return new ValidationResult(true, name, null);
        }
        public static ValidationResult fail(String msg) {
            return new ValidationResult(false, null, msg);
        }
    }

    private final WatchlistStore store;
    private final String cacheDir;

    /** 动态构建并缓存自选股分组对应的服务对象:marketKey -> MarketDataService。 */
    private final Map<String, MarketDataService> marketDataServiceCache = new ConcurrentHashMap<>();
    /** 动态构建并缓存自选股分组对应的账本:marketKey -> Store。 */
    private final Map<String, Store> storeCache = new ConcurrentHashMap<>();
    /** 动态构建并缓存自选股分组对应的参数库:marketKey -> ParamsStore。 */
    private final Map<String, ParamsStore> paramsCache = new ConcurrentHashMap<>();

    /** 客户端单例复用(带 cookie jar / crumb 缓存)。 */
    private final YahooClient yahooClient = new YahooClient();
    private final EastMoneyClient eastMoneyClient = new EastMoneyClient();

    public WatchlistService(WatchlistStore watchlistStore,
                            com.aistock.web.config.MarketProperties props) {
        this.store = watchlistStore;
        this.cacheDir = props.getCacheDir();
    }

    /**
     * 启动时自动迁移旧的包含非 ASCII 字符的 groupId(例如中文 "wl_科技股")
     * 到全 ASCII slug 形式(如 "wl_u79d1_u6280_u80a1")。
     * 旧分组被删除,所有 stocks 迁移到新分组。
     */
    @PostConstruct
    public void migrateLegacyGroups() {
        for (WatchlistGroup g : store.listGroups()) {
            String id = g.groupId();
            String rawId = id.startsWith(WatchlistStore.PREFIX)
                    ? id.substring(WatchlistStore.PREFIX.length())
                    : id;
            if (isAscii(rawId)) {
                continue;
            }
            String slug = slugify(g.groupName());
            String newId = WatchlistStore.toGroupKey(slug);
            if (newId.equals(g.groupId())) {
                continue;
            }
            // 防重
            int i = 2;
            String candidate = newId;
            while (store.getGroup(candidate) != null && !candidate.equals(g.groupId())) {
                candidate = newId + "_" + (i++);
            }
            // 复制到新分组 + 迁移 stocks
            store.createGroup(candidate, g.groupName(), g.marketType());
            for (WatchlistStock s : store.listStocks(g.groupId())) {
                store.addStock(candidate, s.code(), s.name());
            }
            store.deleteGroup(g.groupId());
        }
    }

    // ---- groups ----------------------------------------------------------

    public List<WatchlistGroup> listGroups() {
        return store.listGroups();
    }

    public WatchlistGroup getGroup(String groupKey) {
        return store.getGroup(groupKey);
    }

    /** 创建分组。groupId 自动补 wl_ 前缀;marketType 必须是 us 或 cn。
     *  groupName 中的非 ASCII 字符会被转成全 ASCII 的 unicode hex slug,
     *  保证 groupId 可安全用于 URL / 文件名。 */
    public String createGroup(String groupName, String marketType) {
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("分组名不能为空");
        }
        String mt = normalizeMarketType(marketType);
        String rawId = slugify(groupName.trim());
        if (rawId.isEmpty()) {
            rawId = "group";
        }
        String groupId = WatchlistStore.toGroupKey(rawId);
        // 防重:若同 id 已存在,追加序号
        int i = 2;
        String candidate = groupId;
        while (store.getGroup(candidate) != null) {
            candidate = groupId + "_" + (i++);
        }
        store.createGroup(candidate, groupName.trim(), mt);
        return candidate;
    }

    public void renameGroup(String groupKey, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("分组名不能为空");
        }
        store.renameGroup(groupKey, newName.trim());
        invalidateCaches(groupKey);
    }

    public void deleteGroup(String groupKey) {
        store.deleteGroup(groupKey);
        invalidateCaches(groupKey);
    }

    // ---- stocks ----------------------------------------------------------

    public List<WatchlistStore.WatchlistStock> listStocks(String groupKey) {
        return store.listStocks(groupKey);
    }

    /**
     * 校验一只股票代码对某 market_type 是否有效,并返回其官方名称。
     *
     * <p>校验规则:
     * <ol>
     *   <li>fetchName 不能降级回 code 本身(说明数据源能识别这只票)</li>
     *   <li>updateBars 至少返回一根 bar(有历史数据,不是凭空代码)</li>
     * </ol>
     * 任一不满足返回 {@link ValidationResult#fail(String)} 带友好中文提示。
     */
    public ValidationResult validateStock(String marketType, String rawCode) {
        String mt = normalizeMarketType(marketType);
        String code = normalizeCode(mt, rawCode);
        if (code == null || code.isBlank()) {
            return ValidationResult.fail("股票代码不能为空");
        }

        DataSource ds = buildTemporarySource(mt, code + "_validate");
        Function<String, String> nameFn = nameResolverFor(mt);

        String name;
        try {
            name = nameFn.apply(code);
        } catch (RuntimeException e) {
            return ValidationResult.fail("网络错误,无法查询股票名称,请稍后再试");
        }
        // 名称等于 code → 数据源没有识别出这只票
        if (name == null || name.equalsIgnoreCase(code)) {
            return ValidationResult.fail(
                    "未找到代码为「" + code + "」的" + ("cn".equals(mt) ? "A" : "美") + "股,请检查代码是否正确");
        }

        List<Bar> bars;
        try {
            bars = ds.updateBars(code);
        } catch (RuntimeException e) {
            return ValidationResult.fail("网络错误,无法拉取历史数据,请稍后再试");
        }
        if (bars == null || bars.isEmpty()) {
            return ValidationResult.fail(
                    "代码「" + code + "」无历史行情数据,无法纳入选股计算");
        }

        return ValidationResult.ok(name);
    }

    /**
     * 添加股票到分组。已校验过才允许加入;加入后立即使该分组缓存失效,
     * 下次访问 /signals 会重新拉取并纳入选股。
     */
    public String addStock(String groupKey, String rawCode) {
        WatchlistGroup g = store.getGroup(groupKey);
        if (g == null) {
            throw new IllegalArgumentException("分组不存在:" + groupKey);
        }
        String code = normalizeCode(g.marketType(), rawCode);
        if (store.hasStock(groupKey, code)) {
            return code;
        }
        ValidationResult v = validateStock(g.marketType(), code);
        if (!v.valid()) {
            throw new IllegalArgumentException(v.errorMessage());
        }
        store.addStock(groupKey, code, v.name());
        invalidateCaches(groupKey);
        // 触发一次预拉取:顺便把这只票的 bar 缓存到分组专属 SQLite
        try {
            marketDataServiceFor(groupKey).buildPanel();
        } catch (RuntimeException ignore) {
            // 预拉取失败不影响加入,下次访问再试
        }
        return code;
    }

    public void removeStock(String groupKey, String rawCode) {
        WatchlistGroup g = store.getGroup(groupKey);
        if (g == null) {
            return;
        }
        String code = normalizeCode(g.marketType(), rawCode);
        store.removeStock(groupKey, code);
        invalidateCaches(groupKey);
    }

    // ---- 动态服务对象构建(给 SignalService / PanelCache 用) --------------

    /** 返回某 market key 对应的 MarketDataService。
     *  对自选股分组(wl_xxx)动态构建,对内置 us/cn 抛异常(调用方应自行持有那两个 bean)。 */
    public MarketDataService marketDataServiceFor(String marketKey) {
        if (!WatchlistStore.isWatchlist(marketKey)) {
            throw new IllegalArgumentException("仅支持自选股分组:" + marketKey);
        }
        return marketDataServiceCache.computeIfAbsent(marketKey, this::buildMarketDataService);
    }

    /** 返回某 market key 对应的账本 Store。自选股分组有独立账本。 */
    public Store storeFor(String marketKey) {
        if (!WatchlistStore.isWatchlist(marketKey)) {
            throw new IllegalArgumentException("仅支持自选股分组:" + marketKey);
        }
        return storeCache.computeIfAbsent(marketKey, this::buildStore);
    }

    /** 返回某 market key 对应的参数 ParamsStore。自选股分组有独立参数库。 */
    public ParamsStore paramsFor(String marketKey) {
        if (!WatchlistStore.isWatchlist(marketKey)) {
            throw new IllegalArgumentException("仅支持自选股分组:" + marketKey);
        }
        return paramsCache.computeIfAbsent(marketKey, this::buildParams);
    }

    /** 自选股分组的展示名(内置 us/cn 原样返回)。给顶栏市场切换用。 */
    public String displayName(String marketKey) {
        if (marketKey == null) return "";
        if ("us".equals(marketKey)) return "美股(Yahoo)";
        if ("cn".equals(marketKey)) return "A股(东财)";
        if (WatchlistStore.isWatchlist(marketKey)) {
            WatchlistGroup g = store.getGroup(marketKey);
            if (g != null) {
                return g.groupName() + "(" + ("cn".equals(g.marketType()) ? "A" : "美") + "股自选)";
            }
            return marketKey;
        }
        return marketKey;
    }

    /** 返回该 market key 对应分组的基础 market_type(us/cn)。自选股按分组配置返回;内置原样返回。 */
    public String underlyingMarket(String marketKey) {
        if (marketKey == null) return "us";
        if ("us".equals(marketKey) || "cn".equals(marketKey)) return marketKey;
        if (WatchlistStore.isWatchlist(marketKey)) {
            WatchlistGroup g = store.getGroup(marketKey);
            return g == null ? "us" : g.marketType();
        }
        return "us";
    }

    // ---- internals -------------------------------------------------------

    private void invalidateCaches(String groupKey) {
        marketDataServiceCache.remove(groupKey);
        storeCache.remove(groupKey);
        paramsCache.remove(groupKey);
    }

    private String normalizeMarketType(String mt) {
        if ("cn".equalsIgnoreCase(mt)) return "cn";
        if ("us".equalsIgnoreCase(mt)) return "us";
        throw new IllegalArgumentException("marketType 必须是 us 或 cn,收到:" + mt);
    }

    private String normalizeCode(String marketType, String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        return "us".equals(marketType) ? s.toUpperCase(Locale.ROOT) : s;
    }

    private Function<String, String> nameResolverFor(String marketType) {
        if ("cn".equals(marketType)) {
            return eastMoneyClient::fetchName;
        }
        return new UsNameResolver(yahooClient::fetchName);
    }

    private Function<String, OptionalDouble> marketCapResolverFor(String marketType) {
        return "cn".equals(marketType) ? eastMoneyClient::fetchMarketCap : yahooClient::fetchMarketCap;
    }

    private String cacheFile(String name) {
        Path dir = Path.of(cacheDir);
        dir.toFile().mkdirs();
        return dir.resolve(name).toString();
    }

    /** 临时 DataSource(校验用,用单独的临时 SQLite,不污染分组缓存)。 */
    private DataSource buildTemporarySource(String marketType, String tag) {
        String db = cacheFile("wl_tmp_" + tag + "_" + System.currentTimeMillis() + ".sqlite");
        BarCache cache = new BarCache(db);
        return "cn".equals(marketType)
                ? new EastMoneySource(eastMoneyClient, cache)
                : new YahooSource(yahooClient, cache);
    }

    private MarketDataService buildMarketDataService(String marketKey) {
        WatchlistGroup g = store.getGroup(marketKey);
        if (g == null) {
            // 分组被删除的罕见情况:返回空篮子服务,buildPanel 会返回空面板
            return new MarketDataService(
                    buildTemporarySource("us", marketKey),
                    Function.identity(),
                    s -> OptionalDouble.empty(),
                    new ArrayList<>());
        }
        String mt = g.marketType();
        BarCache cache = new BarCache(cacheFile(marketKey + ".sqlite"));
        DataSource source = "cn".equals(mt)
                ? new EastMoneySource(eastMoneyClient, cache)
                : new YahooSource(yahooClient, cache);
        List<String> codes = store.stockCodes(marketKey);
        return new MarketDataService(source, nameResolverFor(mt), marketCapResolverFor(mt), codes);
    }

    private Store buildStore(String marketKey) {
        return new Store(cacheFile(marketKey + "_ledger.sqlite"));
    }

    private ParamsStore buildParams(String marketKey) {
        return new ParamsStore(cacheFile(marketKey + "_params.sqlite"));
    }

    /**
     * 把任意字符串转成全 ASCII 的 slug。
     * ASCII 小写字母/数字保留,其它字符(含中文)转成 u{4位hex} 形式。
     * 连接符非字母数字统一转下划线,连续下划线合并。
     * 结果稳定(相同输入得到相同输出)、可逆(必要时可还原)、URL/文件名安全。
     */
    static String slugify(String name) {
        if (name == null || name.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append(Character.toLowerCase(c));
            } else if (c >= '0' && c <= '9') {
                sb.append(c);
            } else {
                sb.append("u").append(String.format("%04x", (int) c));
            }
        }
        String s = sb.toString().replaceAll("_+", "_");
        if (s.startsWith("_")) s = s.substring(1);
        if (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static boolean isAscii(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }
}
