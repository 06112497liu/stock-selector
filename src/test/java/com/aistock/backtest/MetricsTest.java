package com.aistock.backtest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsTest {

    private static final double EPS = 1e-9;

    @Test
    void maxDrawdownPositiveConvention() {
        // 峰值 1.2,其后跌到 0.9 -> 回撤 (1.2-0.9)/1.2 = 0.25
        double[] nav = {1.0, 1.2, 0.9, 1.1};
        Metrics m = Metrics.of(nav);
        assertEquals(0.25, m.maxDrawdown(), EPS);
    }

    @Test
    void noDrawdownWhenMonotoneUp() {
        double[] nav = {1.0, 1.1, 1.2, 1.3};
        assertEquals(0.0, Metrics.of(nav).maxDrawdown(), EPS);
    }

    @Test
    void annReturnGeometric() {
        // 252 个日收益、每日恰好 total^(1/252)。取 total=2(翻倍),期数=252 -> 年化=100%
        int periods = Metrics.TRADING_DAYS_PER_YEAR;
        double daily = Math.pow(2.0, 1.0 / periods);
        double[] nav = new double[periods + 1];
        nav[0] = 1.0;
        for (int i = 1; i <= periods; i++) {
            nav[i] = nav[i - 1] * daily;
        }
        Metrics m = Metrics.of(nav);
        assertEquals(1.0, m.annReturn(), 1e-6); // 翻倍一年 -> 年化 100%
    }

    @Test
    void sharpeFormulaDailyTimesSqrt252() {
        // 构造日收益:+1%, -1%, +1%, -1% ... 均值=0 附近,手算对照。
        // 用一段简单 nav,日收益 r = [0.01, 0.02]
        // nav: 1.0 -> 1.01 -> 1.01*1.02
        double[] nav = {1.0, 1.01, 1.01 * 1.02};
        double r0 = 0.01;
        double r1 = nav[2] / nav[1] - 1.0; // ~0.02
        double mean = (r0 + r1) / 2.0;
        double var = ((r0 - mean) * (r0 - mean) + (r1 - mean) * (r1 - mean)) / 2.0; // 总体方差
        double sd = Math.sqrt(var);
        double expected = mean / sd * Math.sqrt(252);
        assertEquals(expected, Metrics.of(nav).sharpe(), 1e-9);
    }

    @Test
    void sharpeZeroWhenFlat() {
        double[] nav = {1.0, 1.0, 1.0};
        assertEquals(0.0, Metrics.of(nav).sharpe(), EPS);
    }

    @Test
    void winRateCountsPositiveDays() {
        // 日收益: +,+,- -> 2/3
        double[] nav = {1.0, 1.1, 1.2, 1.0};
        assertEquals(2.0 / 3.0, Metrics.of(nav).winRate(), EPS);
    }

    @Test
    void singlePointNavGivesZeroMetrics() {
        double[] nav = {1.0};
        Metrics m = Metrics.of(nav);
        assertEquals(0.0, m.annReturn(), EPS);
        assertEquals(0.0, m.sharpe(), EPS);
        assertEquals(0.0, m.maxDrawdown(), EPS);
        assertEquals(0.0, m.winRate(), EPS);
    }

    @Test
    void noNaNInMetrics() {
        double[] nav = {1.0, 1.05, 0.95, 1.02, 1.10};
        Metrics m = Metrics.of(nav);
        assertTrue(Double.isFinite(m.annReturn()));
        assertTrue(Double.isFinite(m.sharpe()));
        assertTrue(Double.isFinite(m.maxDrawdown()));
        assertTrue(Double.isFinite(m.winRate()));
    }
}
