package com.aistock.feature;

import com.aistock.datasource.Bar;

import java.util.List;

/**
 * 行情时间序列因子计算(对标 Python 版 core/features/price_factors)。
 *
 * <p>约定:
 * <ul>
 *   <li>输入 {@code bars} 必须已按 {@code date} 升序排列。</li>
 *   <li>输出 {@code double[]} 与 {@code bars} 等长;窗口数据不足处填 {@link Double#NaN}。</li>
 * </ul>
 *
 * 全部为 static 方法,无状态。
 */
public final class Factors {

    private Factors() {
    }

    /**
     * 动量(momentum):{@code close[i] / close[i-window] - 1}。
     * 前 {@code window} 个元素因缺少 window 日前的基准价而为 NaN。
     *
     * @param bars   升序排列的日线数据
     * @param window 回看窗口(交易日数),必须为正
     * @return 与 bars 等长的动量序列
     */
    public static double[] momentum(List<Bar> bars, int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("window must be positive, got " + window);
        }
        int n = bars.size();
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < window) {
                out[i] = Double.NaN;
            } else {
                double base = bars.get(i - window).close();
                out[i] = bars.get(i).close() / base - 1.0;
            }
        }
        return out;
    }

    /**
     * 短期反转(reversal):动量取负,即 {@code -momentum(bars, window)}。
     * NaN 取负仍为 NaN。
     *
     * @param bars   升序排列的日线数据
     * @param window 回看窗口(交易日数),必须为正
     * @return 与 bars 等长的反转序列
     */
    public static double[] reversal(List<Bar> bars, int window) {
        double[] mom = momentum(bars, window);
        double[] out = new double[mom.length];
        for (int i = 0; i < mom.length; i++) {
            out[i] = -mom[i];
        }
        return out;
    }

    /**
     * 波动率(volatility):日收益率的 window 日滚动标准差。
     *
     * <p>日收益率为 close 的 pct_change:{@code r[i] = close[i] / close[i-1] - 1}
     * (r[0] 无定义)。对每个位置 i,取最近 window 个收益率 {@code r[i-window+1..i]}
     * 计算标准差。
     *
     * <p>口径:采用<b>样本标准差</b>(除以 N-1,Bessel 校正),与 Python 版
     * pandas {@code rolling(window).std()} 默认 {@code ddof=1} 一致。因此每个窗口
     * 至少需要 window 个收益率(即 window+1 个 close)才有定义;数据不足处为 NaN。
     *
     * @param bars   升序排列的日线数据
     * @param window 滚动窗口(收益率个数),必须 >= 2(否则样本方差分母为 0)
     * @return 与 bars 等长的波动率序列(非负或 NaN)
     */
    public static double[] volatility(List<Bar> bars, int window) {
        if (window < 2) {
            throw new IllegalArgumentException("window must be >= 2 for sample std, got " + window);
        }
        int n = bars.size();
        double[] out = new double[n];
        // 日收益率:returns[i] 对应 close[i],returns[0] = NaN
        double[] returns = new double[n];
        if (n > 0) {
            returns[0] = Double.NaN;
        }
        for (int i = 1; i < n; i++) {
            returns[i] = bars.get(i).close() / bars.get(i - 1).close() - 1.0;
        }
        for (int i = 0; i < n; i++) {
            // 窗口为 returns[i-window+1 .. i],共 window 个;最早一个有效收益率是 returns[1]
            int start = i - window + 1;
            if (start < 1) {
                out[i] = Double.NaN;
                continue;
            }
            double sum = 0.0;
            for (int j = start; j <= i; j++) {
                sum += returns[j];
            }
            double mean = sum / window;
            double sq = 0.0;
            for (int j = start; j <= i; j++) {
                double d = returns[j] - mean;
                sq += d * d;
            }
            // 样本标准差:除以 (window - 1)
            out[i] = Math.sqrt(sq / (window - 1));
        }
        return out;
    }
}
