package com.aistock.datasource;

/**
 * 单根 K 线(OHLCV)。
 *
 * <p>{@code time} 为交易所当地时间字符串:分钟档含时分(如
 * {@code "2024-01-02 14:30"}),日 / 周 / 月只到日期({@code "2024-01-02"})。
 * 该 record 直接序列化为 JSON 供前端 ECharts 使用。
 */
public record KlinePoint(
        String time,
        double open,
        double high,
        double low,
        double close,
        long volume) {
}
