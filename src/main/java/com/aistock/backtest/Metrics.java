package com.aistock.backtest;

/**
 * 由净值曲线(NAV)计算的绩效指标。
 *
 * @param annReturn    年化收益率。按几何方式年化:(navEnd/navStart)^(252/期数) - 1。
 *                     期数 = 日收益个数 = nav.length - 1。
 * @param sharpe       年化夏普比率。日收益均值/日收益标准差 × √252,无风险利率取 0。
 *                     日收益标准差为 0(或样本不足)时为 0。
 * @param maxDrawdown  最大回撤,<b>正值口径</b>:历史最高点到其后最低点的最大跌幅比例,
 *                     取值 [0,1],0 表示从未回撤。例如净值由 1.2 跌到 0.9 -> 0.25。
 * @param winRate      胜率:日收益 > 0 的天数占比,取值 [0,1];无日收益时为 0。
 */
public record Metrics(
        double annReturn,
        double sharpe,
        double maxDrawdown,
        double winRate
) {

    /** 一年交易日数(日频年化因子)。 */
    public static final int TRADING_DAYS_PER_YEAR = 252;

    /**
     * 从净值序列计算全部指标。
     *
     * @param nav 净值曲线,长度 >= 1,nav[0] 为起始净值(通常 1.0),需为正。
     */
    public static Metrics of(double[] nav) {
        if (nav == null || nav.length == 0) {
            throw new IllegalArgumentException("nav must be non-empty");
        }
        int n = nav.length;
        // 日收益序列 r[i] = nav[i+1]/nav[i] - 1
        int m = n - 1;
        double[] rets = new double[Math.max(m, 0)];
        int wins = 0;
        for (int i = 0; i < m; i++) {
            double prev = nav[i];
            double r = prev == 0.0 ? 0.0 : nav[i + 1] / prev - 1.0;
            rets[i] = r;
            if (r > 0) {
                wins++;
            }
        }

        // 年化收益(几何):仅当有期数且起点为正
        double annReturn = 0.0;
        if (m > 0 && nav[0] > 0) {
            double total = nav[n - 1] / nav[0];
            if (total > 0) {
                annReturn = Math.pow(total, (double) TRADING_DAYS_PER_YEAR / m) - 1.0;
            }
        }

        // 年化夏普
        double sharpe = 0.0;
        if (m > 0) {
            double mean = 0.0;
            for (double r : rets) {
                mean += r;
            }
            mean /= m;
            double var = 0.0;
            for (double r : rets) {
                double d = r - mean;
                var += d * d;
            }
            // 总体标准差(除以 m);m=1 时方差为 0 -> sharpe=0
            var /= m;
            double sd = Math.sqrt(var);
            if (sd > 0) {
                sharpe = mean / sd * Math.sqrt(TRADING_DAYS_PER_YEAR);
            }
        }

        // 最大回撤(正值口径)
        double maxDd = 0.0;
        double peak = nav[0];
        for (double v : nav) {
            if (v > peak) {
                peak = v;
            }
            if (peak > 0) {
                double dd = (peak - v) / peak;
                if (dd > maxDd) {
                    maxDd = dd;
                }
            }
        }

        double winRate = m > 0 ? (double) wins / m : 0.0;
        return new Metrics(annReturn, sharpe, maxDd, winRate);
    }
}
