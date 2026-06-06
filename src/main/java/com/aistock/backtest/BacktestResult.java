package com.aistock.backtest;

import java.time.LocalDate;
import java.util.List;

/**
 * 回测结果:净值曲线 + 与之对齐的日期 + 绩效指标。
 *
 * <p>{@code dates} 与 {@code nav} 一一对齐、等长:第 i 个元素表示 dates[i] 收盘后的组合净值。
 * nav[0] 为回测起点净值(1.0),对应第一个成交日(信号日之后的第一个交易日)。
 */
public final class BacktestResult {

    private final List<LocalDate> dates;
    private final double[] nav;
    private final Metrics metrics;

    public BacktestResult(List<LocalDate> dates, double[] nav, Metrics metrics) {
        if (dates.size() != nav.length) {
            throw new IllegalArgumentException(
                    "dates(" + dates.size() + ") and nav(" + nav.length + ") length mismatch");
        }
        this.dates = List.copyOf(dates);
        this.nav = nav.clone();
        this.metrics = metrics;
    }

    /** 净值曲线对应的交易日(升序,与 nav 对齐)。 */
    public List<LocalDate> dates() {
        return dates;
    }

    /** 净值曲线(从 1.0 起;防御性拷贝)。 */
    public double[] nav() {
        return nav.clone();
    }

    public Metrics metrics() {
        return metrics;
    }
}
