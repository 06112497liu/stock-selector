package com.aistock.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Minimal client for East Money's public A-share K-line API.
 *
 * <p>Endpoint:
 * {@code https://push2his.eastmoney.com/api/qt/stock/kline/get} with query params:
 * <ul>
 *   <li>{@code secid} — market-prefixed code (see {@link #toSecid(String)})</li>
 *   <li>{@code klt=101} — daily candles</li>
 *   <li>{@code fqt=1} — forward-adjusted (前复权) prices</li>
 *   <li>{@code fields1=f1,f2,f3,f4,f5,f6}</li>
 *   <li>{@code fields2=f51,f52,f53,f54,f55,f56,f57}</li>
 *   <li>{@code beg}/{@code end} — {@code yyyyMMdd} inclusive range</li>
 * </ul>
 * A browser-like {@code User-Agent} header is sent because the endpoint may
 * reject default agents.</p>
 *
 * <p>The JSON shape is:
 * <pre>
 * data.code, data.name
 * data.klines -> array of comma-joined strings, one per trading day, where each
 *                string is: 日期,开盘,收盘,最高,最低,成交量,成交额
 *                (date, open, CLOSE, HIGH, LOW, volume, amount)
 * </pre>
 * Note East Money's field order is open/<b>close</b>/<b>high</b>/<b>low</b> — NOT
 * the standard OHLC order — so the parser maps explicitly to {@link Bar}. Volume
 * is reported in 手 (lots, 1 lot = 100 shares); it is stored as-is.</p>
 */
public class EastMoneyClient implements KlineSource {

    private static final String BASE_URL =
            "https://push2his.eastmoney.com/api/qt/stock/kline/get";
    private static final String QUOTE_URL =
            "https://push2.eastmoney.com/api/qt/stock/get";
    private static final String FIELDS1 = "f1,f2,f3,f4,f5,f6";
    private static final String FIELDS2 = "f51,f52,f53,f54,f55,f56,f57";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public EastMoneyClient() {
        this(new OkHttpClient(), new ObjectMapper());
    }

    public EastMoneyClient(OkHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /**
     * Converts a bare A-share code to East Money's market-prefixed {@code secid}.
     *
     * <p>Shanghai listings (codes starting with {@code 6}) are prefixed
     * {@code "1."}; Shenzhen listings (codes starting with {@code 0} or
     * {@code 3}, i.e. main board / ChiNext) are prefixed {@code "0."}.</p>
     *
     * @param code 6-digit A-share code, e.g. {@code "600519"}
     * @return East Money secid, e.g. {@code "1.600519"}
     * @throws IllegalArgumentException if the code's market cannot be determined
     */
    public static String toSecid(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code must not be empty");
        }
        char first = code.charAt(0);
        return switch (first) {
            case '6' -> "1." + code;        // Shanghai
            case '0', '3' -> "0." + code;   // Shenzhen (main board / ChiNext)
            default -> throw new IllegalArgumentException(
                    "Cannot determine market for A-share code: " + code);
        };
    }

    /**
     * Fetches forward-adjusted daily bars for {@code code} between two dates
     * (inclusive).
     *
     * @param code A-share code, e.g. {@code "600519"}
     * @param from start date (inclusive)
     * @param to   end date (inclusive)
     * @return parsed bars, oldest-first
     * @throws IOException on network failure or a non-2xx response
     */
    public List<Bar> fetchDaily(String code, LocalDate from, LocalDate to) throws IOException {
        String secid = toSecid(code);

        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("secid", secid)
                .addQueryParameter("klt", "101")
                .addQueryParameter("fqt", "1")
                .addQueryParameter("fields1", FIELDS1)
                .addQueryParameter("fields2", FIELDS2)
                .addQueryParameter("beg", from.format(YYYYMMDD))
                .addQueryParameter("end", to.format(YYYYMMDD))
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("East Money request failed: HTTP " + response.code()
                        + " for " + secid);
            }
            return parse(response.body().string());
        }
    }

    /**
     * Fetches the official A-share name for {@code code} from East Money's
     * K-line endpoint.
     *
     * <p>The real-time quote snapshot (push2 stock/get, {@code f58}) is blocked /
     * times out in the current environment, so the name is instead read from the
     * K-line API ({@code push2his} sub-domain, already verified reachable), whose
     * response carries {@code data.name} (e.g. {@code "贵州茅台"}). Only one daily
     * candle is requested ({@code klt=101}, {@code lmt=1}) since just the name is
     * needed. The {@code secid} is built via {@link #toSecid(String)}.</p>
     *
     * <p>This method <b>never throws</b>: on any network failure, non-2xx
     * response, an undeterminable market, or an unparseable body it degrades to
     * returning {@code code} verbatim. Names are therefore never fabricated.</p>
     *
     * @param code A-share code, e.g. {@code "600519"}
     * @return the official name, or {@code code} on any failure
     */
    public String fetchName(String code) {
        String secid;
        try {
            secid = toSecid(code);
        } catch (RuntimeException e) {
            // Unknown market / blank code: degrade to the code itself.
            return code;
        }

        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("secid", secid)
                .addQueryParameter("klt", "101")
                .addQueryParameter("fqt", "1")
                .addQueryParameter("fields1", FIELDS1)
                .addQueryParameter("fields2", FIELDS2)
                .addQueryParameter("beg", "0")
                .addQueryParameter("end", "20500101")
                .addQueryParameter("lmt", "1")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return code;
            }
            return parseName(response.body().string(), code);
        } catch (Exception e) {
            return code;
        }
    }

    /**
     * Parses an East Money K-line JSON payload to extract the name
     * ({@code data.name}).
     *
     * <p>Testable, I/O-free core of {@link #fetchName(String)}. Reads
     * {@code data.name}; if absent/blank or the JSON is malformed (East Money
     * returns {@code data:null} for an unknown secid), returns {@code fallback}.</p>
     *
     * @param json     raw K-line response body
     * @param fallback value to return when no usable name is present
     * @return the parsed name, or {@code fallback}
     */
    public String parseName(String json, String fallback) {
        try {
            JsonNode data = mapper.readTree(json).path("data");
            if (data.isNull() || data.isMissingNode()) {
                return fallback;
            }
            String name = data.path("name").asText("");
            return name.isBlank() ? fallback : name;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Fetches the total market capitalisation (总市值, in CNY) for {@code code} from
     * East Money's real-time quote snapshot.
     *
     * <p>Endpoint:
     * {@code https://push2.eastmoney.com/api/qt/stock/get?secid={secid}&fields=f57,f58,f116}
     * where {@code f116} is the total market cap (总市值). The {@code secid} is built
     * via {@link #toSecid(String)}.</p>
     *
     * <p>This method <b>never throws</b>: on any network failure (proxy block / 502),
     * non-2xx response, an undeterminable market, or a missing field it degrades to
     * {@link OptionalDouble#empty()} (page shows N/A). Unit: CNY.</p>
     *
     * @param code A-share code, e.g. {@code "600519"}
     * @return total market cap in CNY, or empty on any failure
     */
    public OptionalDouble fetchMarketCap(String code) {
        String secid;
        try {
            secid = toSecid(code);
        } catch (RuntimeException e) {
            return OptionalDouble.empty();
        }

        HttpUrl url = HttpUrl.parse(QUOTE_URL).newBuilder()
                .addQueryParameter("secid", secid)
                .addQueryParameter("fields", "f57,f58,f116")
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
        } catch (Exception e) {
            return OptionalDouble.empty();
        }
    }

    /**
     * Parses an East Money stock/get JSON payload to extract total market cap
     * ({@code f116}).
     *
     * <p>Testable, I/O-free core of {@link #fetchMarketCap(String)}. Reads
     * {@code data.f116}; if absent / non-positive or the JSON is malformed (East
     * Money returns {@code data:null} for an unknown secid), returns
     * {@link OptionalDouble#empty()}.</p>
     *
     * @param json raw stock/get response body
     * @return total market cap in CNY, or empty when no usable value is present
     */
    public OptionalDouble parseMarketCap(String json) {
        try {
            JsonNode data = mapper.readTree(json).path("data");
            if (data.isNull() || data.isMissingNode()) {
                return OptionalDouble.empty();
            }
            JsonNode cap = data.path("f116");
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
     * Parses an East Money K-line API JSON payload into a list of {@link Bar}s.
     *
     * <p>This is the testable core of the client and performs no I/O. Each entry
     * of {@code data.klines} is a comma-separated string in the order
     * {@code 日期,开盘,收盘,最高,最低,成交量,成交额}; the parser maps these to
     * {@link Bar}'s open/high/low/close correctly despite the non-standard order.
     * Malformed (too-short) rows are skipped.</p>
     *
     * @param json raw K-line API response body
     * @return parsed bars, oldest-first; empty if {@code data} or {@code klines}
     *         is null/empty
     * @throws IOException if the JSON is malformed
     */
    public List<Bar> parse(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        JsonNode data = root.path("data");
        if (data.isNull() || data.isMissingNode()) {
            return new ArrayList<>();
        }

        JsonNode klines = data.path("klines");
        if (!klines.isArray() || klines.isEmpty()) {
            return new ArrayList<>();
        }

        List<Bar> bars = new ArrayList<>(klines.size());
        for (JsonNode line : klines) {
            String[] parts = line.asText().split(",");
            // 日期,开盘,收盘,最高,最低,成交量,成交额
            if (parts.length < 6) {
                continue;
            }
            LocalDate date = LocalDate.parse(parts[0]);
            double open = Double.parseDouble(parts[1]);
            double close = Double.parseDouble(parts[2]);
            double high = Double.parseDouble(parts[3]);
            double low = Double.parseDouble(parts[4]);
            long volume = Long.parseLong(parts[5]); // 手 (lots)

            bars.add(new Bar(date, open, high, low, close, volume));
        }
        return bars;
    }

    // ---- K 线(行情展示用,与选股 fetchDaily 解耦)--------------------------

    /**
     * 取 {@code code} 在周期 {@code period} 下的 K 线(行情图用)。
     *
     * <p>接口:{@code GET .../api/qt/stock/kline/get?secid=&klt=&fqt=1&...&lmt=}。
     * <b>绝不抛</b>:本机被代理挡(502 / 超时)/ 结构异常一律返回空 List
     * (前端显示「暂无数据」,A 股本地联调属预期)。
     *
     * @param code   A 股代码,如 {@code "600519"}
     * @param period 周期枚举
     * @return K 线列表(oldest-first),失败降级为空 List
     */
    @Override
    public List<KlinePoint> fetchKline(String code, KlinePeriod period) {
        if (code == null || code.isBlank() || period == null) {
            return new ArrayList<>();
        }
        String secid;
        try {
            secid = toSecid(code);
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }

        try {
            HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                    .addQueryParameter("secid", secid)
                    .addQueryParameter("klt", period.eastMoneyKlt())
                    .addQueryParameter("fqt", "1")
                    .addQueryParameter("fields1", FIELDS1)
                    .addQueryParameter("fields2", FIELDS2)
                    .addQueryParameter("beg", "0")
                    .addQueryParameter("end", "20500101")
                    .addQueryParameter("lmt", Integer.toString(period.eastMoneyLmt()))
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
                return parseKline(response.body().string());
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 解析东财 kline JSON 为 {@link KlinePoint} 列表(无 I/O,可测)。
     *
     * <p>每条 {@code data.klines} 为逗号串
     * {@code 日期[时分],开,收,高,低,成交量,成交额,...}(注意东财顺序是
     * <b>开,收,高,低</b>,不可搞反)。time 直接用串里的日期 / 日期时间。
     * 太短的行跳过;任何异常返回空 List。
     *
     * @param json kline 接口响应体
     * @return 解析出的 K 线(oldest-first);异常 / 空结果返回空 List
     */
    public List<KlinePoint> parseKline(String json) {
        try {
            JsonNode data = mapper.readTree(json).path("data");
            if (data.isNull() || data.isMissingNode()) {
                return new ArrayList<>();
            }
            JsonNode klines = data.path("klines");
            if (!klines.isArray() || klines.isEmpty()) {
                return new ArrayList<>();
            }

            List<KlinePoint> points = new ArrayList<>(klines.size());
            for (JsonNode line : klines) {
                String[] parts = line.asText().split(",");
                // 日期[ 时分],开,收,高,低,成交量,成交额
                if (parts.length < 6) {
                    continue;
                }
                String time = parts[0];
                double open = Double.parseDouble(parts[1]);
                double close = Double.parseDouble(parts[2]);
                double high = Double.parseDouble(parts[3]);
                double low = Double.parseDouble(parts[4]);
                long volume = Long.parseLong(parts[5]);

                points.add(new KlinePoint(time, open, high, low, close, volume));
            }
            return points;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
