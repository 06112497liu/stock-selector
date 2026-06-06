package com.aistock.storage;

import com.aistock.storage.Store.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 持仓 / 净值展示工具(纯函数,不触库)。
 *
 * <p>把账本里的持仓 + 名称 + 最新价,组装成给前端逐行渲染的视图模型;
 * 并给出账户摘要(现金 / 持仓市值 / 净值)。某票无现价时市值退回成本计、
 * 浮盈标 NaN,绝不崩。
 */
public final class PortfolioView {

    private PortfolioView() {
    }

    /**
     * 持仓单行:code / 名称 / 股数 / 成本价 / 现价 / 市值 / 浮动盈亏额 / 浮动盈亏%。
     *
     * <p>无现价时 {@code price} 为 NaN、{@code marketValue} 退回 shares×avgCost、
     * {@code pnl} / {@code pnlPct} 为 NaN。
     */
    public record Row(String code,
                      String name,
                      double shares,
                      double avgCost,
                      double price,
                      double marketValue,
                      double pnl,
                      double pnlPct) {
        public boolean hasPrice() {
            return !Double.isNaN(price);
        }
    }

    /** 账户摘要:现金 / 持仓市值合计 / 净值(cash + 市值)。 */
    public record Summary(double cash, double positionsValue, double netValue) {
    }

    /**
     * 逐行持仓视图。
     *
     * @param positions   当前持仓
     * @param names       code -> 名称(缺则回退 code)
     * @param latestClose code -> 最新收盘价(可缺项)
     */
    public static List<Row> positionView(Map<String, Position> positions,
                                         Map<String, String> names,
                                         Map<String, Double> latestClose) {
        List<Row> rows = new ArrayList<>();
        if (positions == null) {
            return rows;
        }
        for (Map.Entry<String, Position> e : positions.entrySet()) {
            String code = e.getKey();
            Position p = e.getValue();
            String name = names == null ? null : names.get(code);
            if (name == null) {
                name = code;
            }
            Double pxObj = latestClose == null ? null : latestClose.get(code);
            boolean hasPx = pxObj != null && !Double.isNaN(pxObj);
            double price = hasPx ? pxObj : Double.NaN;

            double marketValue;
            double pnl;
            double pnlPct;
            if (hasPx) {
                marketValue = p.shares() * price;
                double cost = p.shares() * p.avgCost();
                pnl = marketValue - cost;
                pnlPct = p.avgCost() > 0 ? (price / p.avgCost() - 1.0) : Double.NaN;
            } else {
                // 缺现价:市值退回成本计,浮盈无法算 -> NaN(不崩、不编造)。
                marketValue = p.shares() * p.avgCost();
                pnl = Double.NaN;
                pnlPct = Double.NaN;
            }
            rows.add(new Row(code, name, p.shares(), p.avgCost(), price, marketValue, pnl, pnlPct));
        }
        return rows;
    }

    /**
     * 账户摘要。持仓市值同 {@link #positionView} 口径(缺现价退回成本计),
     * 净值 = cash + 持仓市值,与 {@link Reconcile#netValue} 一致。
     */
    public static Summary accountSummary(double cash,
                                         Map<String, Position> positions,
                                         Map<String, Double> latestClose) {
        double posValue = 0.0;
        if (positions != null) {
            for (Map.Entry<String, Position> e : positions.entrySet()) {
                Position p = e.getValue();
                Double px = latestClose == null ? null : latestClose.get(e.getKey());
                double usePx = (px != null && !Double.isNaN(px)) ? px : p.avgCost();
                posValue += p.shares() * usePx;
            }
        }
        return new Summary(cash, posValue, cash + posValue);
    }
}
