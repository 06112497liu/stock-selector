package com.aistock.recommend;

import com.aistock.feature.MarketPanel;
import com.aistock.selector.FactorSelector;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐引擎:把选股器的横截面打分,翻译成「该买 / 继续持有 / 该卖」三张纯名单
 * (对标 Python 版 app/recommend.py)。
 *
 * <p><b>定位铁律</b>(来自真实用户反馈):
 * <ul>
 *   <li>半自动工具:只给名单建议,人工去券商下单。不纳入资产规模、不算股数/金额、不记账。</li>
 *   <li>买入名单<b>不剔除</b>已持有票:仍在 topN 的已持有票标注「已持有(可加仓)」。</li>
 *   <li>卖出<b>不能</b>因「掉出 topN」就卖;卖出条件只有两条:打分转负 或 触发个股止损。</li>
 *   <li>数据缺失的持仓票<b>保守不动</b>:进 hold 并标注人工复核,绝不强卖。</li>
 * </ul>
 *
 * <p>默认策略 alpha 未经验证,本类只是把流程串起来的 baseline,不代表能赚钱。
 */
public final class RecommendEngine {

    /**
     * 生成某交易日的纯名单推荐。
     *
     * @param panel       因子面板
     * @param day         交易日
     * @param selector    打分选股器(沿用其确定性排序)
     * @param topN        买入名单取前 N
     * @param holdings    当前持有的 code 集合(无持仓传空集)
     * @param entryPrice  已持有票的入场参考价(用于算止损跌幅;某票缺入场价则跳过止损判断,
     *                    但仍可因打分转负而卖)
     * @param stopLossPct 止损线,负数(如 -0.08 表示自入场价跌 8% 触发)
     * @return 三张名单:buy / hold / sell
     */
    public Recommendation recommend(MarketPanel panel,
                                    LocalDate day,
                                    FactorSelector selector,
                                    int topN,
                                    Set<String> holdings,
                                    Map<String, Double> entryPrice,
                                    double stopLossPct) {
        Set<String> held = holdings == null ? Collections.emptySet() : holdings;
        Map<String, Double> entries = entryPrice == null ? Collections.emptyMap() : entryPrice;

        Map<String, Double> scores = selector.scores(panel, day);
        List<String> top = selector.select(panel, day, topN);

        // 1) 买入名单:topN 全保留(不剔除已持有);按选股器名次顺序。
        List<RecoItem> buy = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            String code = top.get(i);
            boolean isHeld = held.contains(code);
            double score = scoreOf(scores, code);
            double price = panel.closeOn(code, day);
            String reason = isHeld ? "已持有(可加仓)" : "Top" + (i + 1) + " 选中";
            buy.add(new RecoItem(code, price, score, reason, isHeld));
        }

        // 2) 持仓分流:卖出 / 继续持有 / 数据缺失保守不动。
        List<RecoItem> sell = new ArrayList<>();
        List<RecoItem> hold = new ArrayList<>();
        for (String code : held) {
            double price = panel.closeOn(code, day);
            boolean hasScore = scores.containsKey(code);

            if (!hasScore) {
                // 该票当天不在横截面/NaN:数据缺失,保守不动 -> 进 hold 并标注复核,绝不强卖。
                hold.add(new RecoItem(code, price, Double.NaN, "数据缺失,建议人工复核", true));
                continue;
            }

            double score = scores.get(code);

            // 卖出条件一:打分转负。
            if (score < 0) {
                sell.add(new RecoItem(code, price, score, "打分转负", true));
                continue;
            }

            // 卖出条件二:有入场价且自入场价跌幅触及止损线。
            Double entry = entries.get(code);
            if (entry != null && entry > 0 && !Double.isNaN(price)) {
                double drawdown = price / entry - 1.0;
                if (drawdown <= stopLossPct) {
                    sell.add(new RecoItem(code, price, score,
                            "触发止损 " + formatPct(stopLossPct), true));
                    continue;
                }
            }

            // 否则继续持有(掉出 topN 但 score>=0 且未触发止损的,在此保留)。
            hold.add(new RecoItem(code, price, score, "继续持有", true));
        }

        // 3) 名单内部排序固化:buy 已按选股器名次;hold/sell 按 code 字典序。
        sell.sort((a, b) -> a.code().compareTo(b.code()));
        hold.sort((a, b) -> a.code().compareTo(b.code()));

        return new Recommendation(
                Collections.unmodifiableList(buy),
                Collections.unmodifiableList(hold),
                Collections.unmodifiableList(sell));
    }

    /** 取分数;数据缺失(不在横截面)时返回 NaN。 */
    private static double scoreOf(Map<String, Double> scores, String code) {
        Double v = scores.get(code);
        return v == null ? Double.NaN : v;
    }

    /** 把止损线格式化为百分比,如 -0.08 -> "-8.00%"。 */
    private static String formatPct(double pct) {
        return String.format("%.2f%%", pct * 100.0);
    }
}
