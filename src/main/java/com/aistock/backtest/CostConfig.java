package com.aistock.backtest;

/**
 * 交易成本费率配置(均为「单边」比例费率,如 0.0003 即 3 个基点)。
 *
 * <ul>
 *   <li>{@code commission} 佣金:买卖双向都收。</li>
 *   <li>{@code stamp}      印花税:A 股仅<b>卖出</b>收;美股可传 0。</li>
 *   <li>{@code slippage}   滑点:买卖双向都按该比例计(模拟成交价相对参考价的不利偏移)。</li>
 * </ul>
 *
 * 由此:
 * <pre>
 *   买入成本率 = commission + slippage
 *   卖出成本率 = commission + stamp + slippage
 * </pre>
 *
 * @param commission 单边佣金率(>= 0)
 * @param stamp      卖出印花税率(>= 0)
 * @param slippage   单边滑点率(>= 0)
 */
public record CostConfig(double commission, double stamp, double slippage) {

    public CostConfig {
        if (commission < 0 || stamp < 0 || slippage < 0) {
            throw new IllegalArgumentException(
                    "cost rates must be >= 0: commission=" + commission
                            + " stamp=" + stamp + " slippage=" + slippage);
        }
    }

    /** 零成本配置(用于对照:与含成本结果作差应严格不劣)。 */
    public static CostConfig zero() {
        return new CostConfig(0.0, 0.0, 0.0);
    }

    /** 仅含滑点、无佣金无印花税。 */
    public static CostConfig zeroExceptSlippage(double slippage) {
        return new CostConfig(0.0, 0.0, slippage);
    }

    /** 买入腿单边成本率 = commission + slippage。 */
    public double buyRate() {
        return commission + slippage;
    }

    /** 卖出腿单边成本率 = commission + stamp + slippage。 */
    public double sellRate() {
        return commission + stamp + slippage;
    }
}
