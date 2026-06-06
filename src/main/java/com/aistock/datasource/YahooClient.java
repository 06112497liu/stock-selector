package com.aistock.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal client for the public Yahoo Finance chart API.
 *
 * <p>Endpoint: {@code https://query1.finance.yahoo.com/v8/finance/chart/{symbol}}
 * with query params {@code period1}/{@code period2} (epoch seconds) and
 * {@code interval=1d}. A browser-like {@code User-Agent} header is required,
 * otherwise Yahoo answers with HTTP 429.</p>
 *
 * <p>The JSON shape is:
 * <pre>
 * chart.result[0].timestamp           -> array of epoch seconds (one per bar)
 * chart.result[0].indicators.quote[0] -> { open[], high[], low[], close[], volume[] }
 * chart.result[0].meta.gmtoffset      -> exchange offset in seconds (used to derive the trading date)
 * </pre>
 */
public class YahooClient implements KlineSource {

    private static final String BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String QUOTE_URL = "https://query1.finance.yahoo.com/v7/finance/quote";
    /** 触发 Set-Cookie 的引导地址(返回 404 属预期,目的仅为拿 cookie)。 */
    private static final String COOKIE_BOOTSTRAP_URL = "https://fc.yahoo.com";
    /** 用 cookie 换取 crumb 的地址(响应体即 crumb 明文)。 */
    private static final String CRUMB_URL = "https://query1.finance.yahoo.com/v1/test/getcrumb";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final OkHttpClient http;
    private final ObjectMapper mapper;

    /** crumb 缓存:整批 code 复用,首次拿到后供后续 quote 请求(连同 cookie jar)使用。 */
    private volatile String cachedCrumb;

    public YahooClient() {
        // 默认客户端带共享内存 CookieJar:crumb 流程需要 fc.yahoo.com 的 Set-Cookie
        // 在后续 getcrumb / quote 请求中带回,否则 v7/quote 返回 Unauthorized。
        this(new OkHttpClient.Builder()
                        .cookieJar(new InMemoryCookieJar())
                        .build(),
                new ObjectMapper());
    }

    public YahooClient(OkHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /**
     * Fetches daily bars for {@code symbol} between two dates (inclusive of the range
     * spanned by the epoch seconds Yahoo returns).
     *
     * @param symbol e.g. {@code "AAPL"}
     * @param from   start date (inclusive)
     * @param to     end date (inclusive)
     * @return parsed bars, ordered oldest-first
     * @throws IOException on network failure or a non-2xx response
     */
    public List<Bar> fetchDaily(String symbol, LocalDate from, LocalDate to) throws IOException {
        long period1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        // +1 day so the "to" day is fully covered.
        long period2 = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();

        HttpUrl url = HttpUrl.parse(BASE_URL + symbol).newBuilder()
                .addQueryParameter("period1", Long.toString(period1))
                .addQueryParameter("period2", Long.toString(period2))
                .addQueryParameter("interval", "1d")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Yahoo request failed: HTTP " + response.code()
                        + " for " + symbol);
            }
            return parse(response.body().string());
        }
    }

    /**
     * Fetches the official instrument name for {@code symbol} from Yahoo's quote
     * API.
     *
     * <p>Endpoint: {@code https://query1.finance.yahoo.com/v7/finance/quote?symbols={symbol}}.
     * The name is read from {@code quoteResponse.result[0].longName}, falling back
     * to {@code shortName}, and finally to {@code symbol} itself.</p>
     *
     * <p>This method <b>never throws</b>: on any network failure, non-2xx
     * response, or unparseable body it degrades to returning {@code symbol}
     * verbatim. Names are therefore never fabricated — a failure simply yields
     * the code the caller already knows.</p>
     *
     * @param symbol e.g. {@code "AAPL"}
     * @return the official long/short name, or {@code symbol} on any failure
     */
    public String fetchName(String symbol) {
        HttpUrl url = HttpUrl.parse(QUOTE_URL).newBuilder()
                .addQueryParameter("symbols", symbol)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return symbol;
            }
            return parseName(response.body().string(), symbol);
        } catch (Exception e) {
            return symbol;
        }
    }

    /**
     * Parses a Yahoo quote API JSON payload to extract the instrument name.
     *
     * <p>Testable, I/O-free core of {@link #fetchName(String)}. Reads
     * {@code quoteResponse.result[0].longName}, then {@code shortName}; if both
     * are absent/blank or the JSON is malformed, returns {@code fallback}.</p>
     *
     * @param json     raw quote API response body
     * @param fallback value to return when no usable name is present
     * @return the parsed name, or {@code fallback}
     */
    public String parseName(String json, String fallback) {
        try {
            JsonNode result = mapper.readTree(json)
                    .path("quoteResponse").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return fallback;
            }
            JsonNode first = result.get(0);
            String longName = first.path("longName").asText("");
            if (!longName.isBlank()) {
                return longName;
            }
            String shortName = first.path("shortName").asText("");
            if (!shortName.isBlank()) {
                return shortName;
            }
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Fetches the company market capitalisation (in USD) for {@code symbol} from
     * Yahoo's authenticated v7 quote API.
     *
     * <p>Yahoo's {@code v7/finance/quote} requires a crumb + matching cookies
     * (plain access returns {@code Unauthorized}). The flow (replicated here):
     * <ol>
     *   <li>{@code GET https://fc.yahoo.com} — discard the 404, keep the Set-Cookie;</li>
     *   <li>{@code GET .../v1/test/getcrumb} with that cookie — body is the crumb;</li>
     *   <li>{@code GET .../v7/finance/quote?symbols=...&crumb=...} with the cookie —
     *       JSON {@code quoteResponse.result[0].marketCap}.</li>
     * </ol>
     * The crumb (and cookie jar) are <b>cached and reused</b> across the whole
     * batch — fetched lazily on the first call, then reused for every code.</p>
     *
     * <p>This method <b>never throws</b>: on any failure (no crumb, non-2xx,
     * unparseable body, missing field) it degrades to {@link OptionalDouble#empty()}.
     * Unit: USD.</p>
     *
     * @param symbol e.g. {@code "AAPL"}
     * @return market cap in USD, or empty on any failure
     */
    public OptionalDouble fetchMarketCap(String symbol) {
        try {
            String crumb = crumb();
            if (crumb == null || crumb.isBlank()) {
                return OptionalDouble.empty();
            }

            HttpUrl url = HttpUrl.parse(QUOTE_URL).newBuilder()
                    .addQueryParameter("symbols", symbol)
                    .addQueryParameter("crumb", crumb)
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return OptionalDouble.empty();
                }
                return parseMarketCap(response.body().string());
            }
        } catch (Exception e) {
            return OptionalDouble.empty();
        }
    }

    /**
     * Lazily obtains (and caches) a Yahoo crumb, priming the cookie jar first.
     *
     * <p>Step 1 hits {@link #COOKIE_BOOTSTRAP_URL} purely to collect Set-Cookie
     * headers into the shared jar — its 404 is expected and ignored. Step 2 then
     * exchanges those cookies for a crumb at {@link #CRUMB_URL}. The crumb is
     * cached so the batch shares one crumb instead of re-fetching per code.</p>
     *
     * @return the crumb string, or {@code null} on failure
     */
    private String crumb() {
        String c = cachedCrumb;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (cachedCrumb != null) {
                return cachedCrumb;
            }
            try {
                // 1) 引导 cookie:忽略 404,只为拿 Set-Cookie 进 jar。
                Request bootstrap = new Request.Builder()
                        .url(COOKIE_BOOTSTRAP_URL)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build();
                try (Response ignored = http.newCall(bootstrap).execute()) {
                    // 404 属预期,无需检查 —— cookie 已由 CookieJar 收下。
                    if (ignored.body() != null) {
                        ignored.body().close();
                    }
                }

                // 2) 用 cookie 换 crumb:响应体即 crumb 明文。
                Request crumbReq = new Request.Builder()
                        .url(CRUMB_URL)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build();
                try (Response response = http.newCall(crumbReq).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        return null;
                    }
                    String crumb = response.body().string().trim();
                    if (crumb.isEmpty()) {
                        return null;
                    }
                    cachedCrumb = crumb;
                    return crumb;
                }
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Parses a Yahoo v7 quote API JSON payload to extract {@code marketCap}.
     *
     * <p>Testable, I/O-free core of {@link #fetchMarketCap(String)}. Reads
     * {@code quoteResponse.result[0].marketCap}; if absent / non-positive or the
     * JSON is malformed, returns {@link OptionalDouble#empty()}.</p>
     *
     * @param json raw v7 quote API response body
     * @return market cap in USD, or empty when no usable value is present
     */
    public OptionalDouble parseMarketCap(String json) {
        try {
            JsonNode result = mapper.readTree(json)
                    .path("quoteResponse").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return OptionalDouble.empty();
            }
            JsonNode cap = result.get(0).path("marketCap");
            if (cap.isMissingNode() || cap.isNull() || !cap.isNumber()) {
                return OptionalDouble.empty();
            }
            double v = cap.asDouble();
            return v > 0 ? OptionalDouble.of(v) : OptionalDouble.empty();
        } catch (Exception e) {
            return OptionalDouble.empty();
        }
    }

    /**
     * Parses a Yahoo chart API JSON payload into a list of {@link Bar}s.
     *
     * <p>This is the testable core of the client and performs no I/O. Bars whose
     * OHLCV entries are all {@code null} (Yahoo emits these for non-trading slots)
     * are skipped.</p>
     *
     * @param json raw chart API response body
     * @return parsed bars, oldest-first; empty if the result set is empty
     * @throws IOException if the JSON is malformed or reports an API error
     */
    public List<Bar> parse(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        JsonNode chart = root.path("chart");

        JsonNode error = chart.path("error");
        if (!error.isNull() && !error.isMissingNode()) {
            throw new IOException("Yahoo chart API error: " + error);
        }

        JsonNode result = chart.path("result");
        if (!result.isArray() || result.isEmpty()) {
            return new ArrayList<>();
        }

        JsonNode first = result.get(0);
        JsonNode timestamps = first.path("timestamp");
        JsonNode quote = first.path("indicators").path("quote");
        if (!timestamps.isArray() || !quote.isArray() || quote.isEmpty()) {
            return new ArrayList<>();
        }

        JsonNode q = quote.get(0);
        JsonNode opens = q.path("open");
        JsonNode highs = q.path("high");
        JsonNode lows = q.path("low");
        JsonNode closes = q.path("close");
        JsonNode volumes = q.path("volume");

        int gmtOffsetSeconds = first.path("meta").path("gmtoffset").asInt(0);
        ZoneId zone = ZoneOffset.ofTotalSeconds(gmtOffsetSeconds);

        List<Bar> bars = new ArrayList<>(timestamps.size());
        for (int i = 0; i < timestamps.size(); i++) {
            JsonNode openNode = opens.path(i);
            JsonNode highNode = highs.path(i);
            JsonNode lowNode = lows.path(i);
            JsonNode closeNode = closes.path(i);
            JsonNode volNode = volumes.path(i);

            // Skip bars with no OHLC data (Yahoo returns nulls for gaps/halts).
            if (openNode.isNull() || highNode.isNull()
                    || lowNode.isNull() || closeNode.isNull()) {
                continue;
            }

            long epochSeconds = timestamps.path(i).asLong();
            LocalDate date = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate();

            bars.add(new Bar(
                    date,
                    openNode.asDouble(),
                    highNode.asDouble(),
                    lowNode.asDouble(),
                    closeNode.asDouble(),
                    volNode.isNull() ? 0L : volNode.asLong()
            ));
        }
        return bars;
    }

    // ---- K 线(行情展示用,与选股 fetchDaily 解耦)--------------------------

    private static final DateTimeFormatter KLINE_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter KLINE_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 取 {@code symbol} 在周期 {@code period} 下的 K 线(行情图用)。
     *
     * <p>接口:{@code GET .../v8/finance/chart/{symbol}?interval=&range=}(chart
     * 接口公开,无需 crumb)。<b>绝不抛</b>:网络 / 429 / 结构异常一律返回空 List。
     *
     * @param symbol 美股代码,如 {@code "AAPL"}
     * @param period 周期枚举
     * @return K 线列表(oldest-first),失败降级为空 List
     */
    @Override
    public List<KlinePoint> fetchKline(String symbol, KlinePeriod period) {
        if (symbol == null || symbol.isBlank() || period == null) {
            return new ArrayList<>();
        }
        try {
            HttpUrl url = HttpUrl.parse(BASE_URL + symbol).newBuilder()
                    .addQueryParameter("interval", period.yahooInterval())
                    .addQueryParameter("range", period.yahooRange())
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return new ArrayList<>();
                }
                return parseKline(response.body().string(), period);
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 解析 Yahoo chart JSON 为 {@link KlinePoint} 列表(无 I/O,可测)。
     *
     * <p>读 {@code chart.result[0].timestamp[]}(epoch 秒)+
     * {@code indicators.quote[0].{open,high,low,close,volume}[]};某根 OHLC 任一为
     * null 则跳过该根。time 用 {@code meta.gmtoffset} 转交易所当地时间:分钟档
     * {@code "yyyy-MM-dd HH:mm"},日及以上 {@code "yyyy-MM-dd"}。任何异常返回空 List。
     *
     * @param json   chart 接口响应体
     * @param period 周期枚举(决定 time 格式)
     * @return 解析出的 K 线(oldest-first);异常 / 空结果返回空 List
     */
    public List<KlinePoint> parseKline(String json, KlinePeriod period) {
        try {
            JsonNode chart = mapper.readTree(json).path("chart");
            JsonNode result = chart.path("result");
            if (!result.isArray() || result.isEmpty()) {
                return new ArrayList<>();
            }
            JsonNode first = result.get(0);
            JsonNode timestamps = first.path("timestamp");
            JsonNode quote = first.path("indicators").path("quote");
            if (!timestamps.isArray() || !quote.isArray() || quote.isEmpty()) {
                return new ArrayList<>();
            }
            JsonNode q = quote.get(0);
            JsonNode opens = q.path("open");
            JsonNode highs = q.path("high");
            JsonNode lows = q.path("low");
            JsonNode closes = q.path("close");
            JsonNode volumes = q.path("volume");

            int gmtOffsetSeconds = first.path("meta").path("gmtoffset").asInt(0);
            ZoneId zone = ZoneOffset.ofTotalSeconds(gmtOffsetSeconds);
            boolean intraday = period.isIntraday();

            List<KlinePoint> points = new ArrayList<>(timestamps.size());
            for (int i = 0; i < timestamps.size(); i++) {
                JsonNode openNode = opens.path(i);
                JsonNode highNode = highs.path(i);
                JsonNode lowNode = lows.path(i);
                JsonNode closeNode = closes.path(i);
                JsonNode volNode = volumes.path(i);

                if (openNode.isNull() || openNode.isMissingNode()
                        || highNode.isNull() || lowNode.isNull() || closeNode.isNull()) {
                    continue;
                }

                long epochSeconds = timestamps.path(i).asLong();
                LocalDateTime ldt = Instant.ofEpochSecond(epochSeconds)
                        .atZone(zone).toLocalDateTime();
                String time = intraday ? ldt.format(KLINE_DATETIME)
                        : ldt.toLocalDate().format(KLINE_DATE);

                points.add(new KlinePoint(
                        time,
                        openNode.asDouble(),
                        highNode.asDouble(),
                        lowNode.asDouble(),
                        closeNode.asDouble(),
                        volNode.isNull() || volNode.isMissingNode() ? 0L : volNode.asLong()
                ));
            }
            return points;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Minimal in-memory {@link CookieJar} so the crumb flow's Set-Cookie from
     * {@code fc.yahoo.com} is sent back on subsequent getcrumb / quote calls.
     *
     * <p>Keyed by cookie host + name; not expiry-aware (good enough for a single
     * short-lived crumb batch). Thread-safe.</p>
     */
    private static final class InMemoryCookieJar implements CookieJar {
        private final java.util.Map<String, Cookie> store = new ConcurrentHashMap<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            for (Cookie c : cookies) {
                store.put(c.domain() + "|" + c.name(), c);
            }
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> out = new ArrayList<>();
            for (Cookie c : store.values()) {
                if (c.matches(url)) {
                    out.add(c);
                }
            }
            return out;
        }
    }
}
