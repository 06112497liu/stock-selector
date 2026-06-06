package com.aistock.datasource;

/**
 * K 线周期枚举:8 档(1分/5分/15分/30分/60分/日/周/月)。
 *
 * <p>每档携带三组映射:
 * <ul>
 *   <li>{@link #code} —— 前端 / REST 参数使用的周期码(如 {@code "1m"}、{@code "1d"});</li>
 *   <li>{@link #yahooInterval} / {@link #yahooRange} —— Yahoo chart 接口的
 *       {@code interval} 与 {@code range} 参数;</li>
 *   <li>{@link #eastMoneyKlt} —— 东财 kline 接口的 {@code klt} 参数。</li>
 * </ul>
 *
 * <p>{@link #fromCode(String)} 把前端码解析回枚举,非法 / 空码一律降级为日线
 * ({@link #DAY}),保证调用方永远拿到一个合法周期、绝不抛异常。
 */
public enum KlinePeriod {

    MIN_1("1m", "1m", "1d", "1", 1200),
    MIN_5("5m", "5m", "5d", "5", 1200),
    MIN_15("15m", "15m", "1mo", "15", 1200),
    MIN_30("30m", "30m", "1mo", "30", 1200),
    MIN_60("60m", "60m", "3mo", "60", 1200),
    DAY("1d", "1d", "1y", "101", 500),
    WEEK("1wk", "1wk", "2y", "102", 300),
    MONTH("1mo", "1mo", "5y", "103", 300);

    private final String code;
    private final String yahooInterval;
    private final String yahooRange;
    private final String eastMoneyKlt;
    private final int eastMoneyLmt;

    KlinePeriod(String code, String yahooInterval, String yahooRange,
                String eastMoneyKlt, int eastMoneyLmt) {
        this.code = code;
        this.yahooInterval = yahooInterval;
        this.yahooRange = yahooRange;
        this.eastMoneyKlt = eastMoneyKlt;
        this.eastMoneyLmt = eastMoneyLmt;
    }

    public String code() {
        return code;
    }

    public String yahooInterval() {
        return yahooInterval;
    }

    public String yahooRange() {
        return yahooRange;
    }

    public String eastMoneyKlt() {
        return eastMoneyKlt;
    }

    public int eastMoneyLmt() {
        return eastMoneyLmt;
    }

    /** 分钟档(time 含时分),日 / 周 / 月只到日期。 */
    public boolean isIntraday() {
        return this == MIN_1 || this == MIN_5 || this == MIN_15
                || this == MIN_30 || this == MIN_60;
    }

    /**
     * 把前端周期码解析为枚举;非法 / 空 / null 一律降级为日线 {@link #DAY}。
     *
     * @param code 前端码,如 {@code "5m"}、{@code "1wk"}
     * @return 匹配的周期,无匹配则 {@link #DAY}
     */
    public static KlinePeriod fromCode(String code) {
        if (code == null) {
            return DAY;
        }
        for (KlinePeriod p : values()) {
            if (p.code.equalsIgnoreCase(code)) {
                return p;
            }
        }
        return DAY;
    }
}
