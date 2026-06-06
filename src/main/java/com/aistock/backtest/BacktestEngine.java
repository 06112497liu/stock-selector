package com.aistock.backtest;

import com.aistock.feature.MarketPanel;
import com.aistock.selector.Selector;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 等权 topN 组合的日频回测引擎(对标 Python 版 core/backtest)。
 *
 * <h2>防前视 + T+1 成交的口径(本里程碑严格遵守)</h2>
 * <ul>
 *   <li><b>信号日 d</b>:在交易日 d 收盘后,用 {@code selector.select(panel, d, topN)}
 *       得到目标持仓。MarketPanel 已保证选股只用到 &lt;= d 的因子,不含未来信息。</li>
 *   <li><b>成交日 d+1</b>:目标组合在<b>下一个交易日 d+1</b> 建/调仓。本里程碑成交价
 *       统一采用 {@link MarketPanel#closeOn} 的<b>收盘价</b>(MarketPanel 未暴露开盘价),
 *       但<b>成交日必须是 d+1 而非 d</b>——即「d 日信号、d+1 日成交价」。
 *       严禁用 d 日或更早的价格成交 d 日的信号。</li>
 *   <li><b>持仓收益</b>:某成交日 e 建仓后,组合按各持仓票从 e 收盘到下一交易日收盘的
 *       收益(close[next]/close[e]-1)等权累乘进 NAV,逐日推进,直到下一次成交日换仓。</li>
 *   <li><b>红线</b>:任何一天 t 的净值变化只用到 close[t] 与 close[t+1](已知的当日/次日价),
 *       绝不引用 t 之后才知道的价格去影响 t 或更早的净值。
 *       篡改某日之后的价格,只能影响其之后的 NAV。</li>
 * </ul>
 *
 * <h2>NAV 与日期对齐</h2>
 * NAV 从第一个成交日起步(值为 1.0)。第一个成交日 = 第一个 &gt;= start 的信号日的下一交易日。
 * {@code result.dates().get(i)} 是第 i 个净值点对应的交易日(成交日及其后逐日)。
 */
public final class BacktestEngine {

    /**
     * 跑一次回测。
     *
     * @param panel    行情面板(已防前视:某天因子不足的票不在横截面)
     * @param selector 选股器
     * @param start    回测起始日(>= start 的交易日才会产生信号)
     * @param topN     等权组合持仓数
     * @param cost     交易成本费率
     * @return 净值曲线 + 日期 + 绩效指标
     */
    public BacktestResult run(MarketPanel panel, Selector selector,
                             LocalDate start, int topN, CostConfig cost) {
        List<LocalDate> days = panel.tradingDays();

        // NAV 序列与对齐日期(NAV[0]=1.0 落在第一个成交日)。
        List<LocalDate> navDates = new ArrayList<>();
        List<Double> navList = new ArrayList<>();

        double nav = 1.0;
        List<String> currentHoldings = List.of(); // 当前(已成交)持仓
        boolean started = false;

        // 逐交易日推进。索引 i 表示「日 d=days[i]」既可能是信号日,也可能是某次持仓的中途日。
        // 我们以「成交日」为节点:在成交日把目标持仓换成新持仓并扣换仓成本,
        // 随后每个相邻交易日对 (t,t+1) 用 currentHoldings 计一段持仓收益。
        //
        // 实现:遍历到日 d=days[i](i 从 0 起),若 d>=start,则它是信号日,
        //   其成交日是 days[i+1](若存在)。在到达成交日时换仓。
        // 为简化:我们在每个交易日开始时,先(可选)换仓,再用换仓后的持仓推进到下一日。

        // 预先算出「每个交易日应在该日成交的目标持仓」:
        //   targetForTradeDay[i+1] = select(panel, days[i], topN),当 days[i] >= start。
        // 即成交日 days[i+1] 使用信号日 days[i] 的选股结果。
        List<List<String>> tradeTarget = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            tradeTarget.add(null);
        }
        for (int i = 0; i + 1 < days.size(); i++) {
            if (!days.get(i).isBefore(start)) { // days[i] >= start
                tradeTarget.set(i + 1, selector.select(panel, days.get(i), topN));
            }
        }

        for (int i = 0; i < days.size(); i++) {
            LocalDate d = days.get(i);
            List<String> targetToday = tradeTarget.get(i); // 今天(若有)要成交的目标持仓

            if (targetToday != null) {
                // 今天是成交日:换仓,扣买卖腿成本(成交价口径=今日收盘价)。
                double turnoverCost = rebalanceCost(currentHoldings, targetToday, cost);
                nav *= (1.0 - turnoverCost);
                currentHoldings = targetToday;
                if (!started) {
                    // 第一个成交日:NAV 记为换仓后净值,作为曲线起点。
                    started = true;
                    navDates.add(d);
                    navList.add(nav);
                } else {
                    // 后续成交日:当日净值已在上一段推进时写入(见下),这里只更新持仓与成本。
                    // 用换仓成本调整当日已写入的最后一个 NAV 点。
                    navList.set(navList.size() - 1, nav);
                }
            }

            if (!started) {
                continue; // 还没开始持仓,跳过收益推进
            }

            // 用当前持仓把净值从今日收盘推进到下一交易日收盘。
            if (i + 1 < days.size()) {
                LocalDate next = days.get(i + 1);
                double grossRet = equalWeightReturn(panel, currentHoldings, d, next);
                nav *= (1.0 + grossRet);
                navDates.add(next);
                navList.add(nav);
            }
        }

        // started 仍为 false:start 之后无可成交日 -> 退化为单点净值 1.0(在最后一交易日)。
        if (!started) {
            navDates.add(days.isEmpty() ? start : days.get(days.size() - 1));
            navList.add(1.0);
        }

        double[] navArr = new double[navList.size()];
        for (int k = 0; k < navArr.length; k++) {
            navArr[k] = navList.get(k);
        }
        return new BacktestResult(navDates, navArr, Metrics.of(navArr));
    }

    /**
     * 等权组合从 from 收盘到 to 收盘的组合收益(简单收益的等权平均)。
     * 某票任一日价格缺失(NaN)时,该票当段按 0 收益处理(视为停牌/不可交易,不拖累也不贡献)。
     */
    private static double equalWeightReturn(MarketPanel panel, List<String> holdings,
                                            LocalDate from, LocalDate to) {
        if (holdings.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (String code : holdings) {
            double p0 = panel.closeOn(code, from);
            double p1 = panel.closeOn(code, to);
            double r = (Double.isNaN(p0) || Double.isNaN(p1) || p0 == 0.0)
                    ? 0.0
                    : p1 / p0 - 1.0;
            sum += r;
        }
        return sum / holdings.size();
    }

    /**
     * 一次换仓的总成本率(占组合净值比例)。
     *
     * <p>等权目标下,每只目标票权重 1/|target|,每只原持仓票权重 1/|prev|。
     * <ul>
     *   <li>卖出腿:原持仓中<b>不在</b>新目标里的票,按各自权重 × sellRate。</li>
     *   <li>买入腿:新目标中<b>不在</b>原持仓里的票,按各自权重 × buyRate。</li>
     * </ul>
     * 继续持有(两边都在)的票本里程碑视为不调整、不计成本(等权微调成本忽略,口径从简)。
     */
    private static double rebalanceCost(List<String> prev, List<String> target, CostConfig cost) {
        double c = 0.0;
        if (!prev.isEmpty()) {
            double wPrev = 1.0 / prev.size();
            for (String code : prev) {
                if (!target.contains(code)) {
                    c += wPrev * cost.sellRate(); // 卖出腿
                }
            }
        }
        if (!target.isEmpty()) {
            double wTarget = 1.0 / target.size();
            for (String code : target) {
                if (!prev.contains(code)) {
                    c += wTarget * cost.buyRate(); // 买入腿
                }
            }
        }
        return c;
    }
}
