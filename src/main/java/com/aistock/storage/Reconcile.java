package com.aistock.storage;

import com.aistock.storage.Store.Position;

import java.util.Map;

/**
 * 对账:把账本改写成券商真实持仓 + 真实现金,并<b>清空净值历史、重置净值基准</b>。
 *
 * <p><b>关键教训(Python 踩过的真 bug)</b>:对账后,旧的虚假净值峰值会让回撤护栏
 * 误判暴跌而清仓。修复是——对账时先清空该市场的 nav_history、以对账后的净值为新基准
 * 重新锚定。本类的 {@link #applyReconciliation} 即照此实现:对账时 {@code clearNav()}。
 */
public final class Reconcile {

    private Reconcile() {
    }

    /**
     * 应用一次对账:整体替换持仓 + 设置现金 + 清空净值历史(重置基准)。
     *
     * @param store          目标账本
     * @param truePositions  券商真实持仓(整体替换;为空则清空持仓)
     * @param trueCash       券商真实现金
     */
    public static void applyReconciliation(Store store,
                                           Map<String, Position> truePositions,
                                           double trueCash) {
        store.replaceAllPositions(truePositions);
        store.setCash(trueCash);
        // 上面那条 Python 教训:清空净值历史,让对账后的净值成为新基准,
        // 不让旧净值峰值污染后续回撤判断与曲线。
        store.clearNav();
    }

    /**
     * 按当前持仓 + 最新价算净值 = 现金 + Σ shares × price。
     *
     * <p>某票无现价(latestClose 缺失或 NaN)时,该票市值<b>退回用成本价计</b>
     * (shares × avgCost),避免净值因偶发缺价突然塌陷。
     *
     * @param cash        现金
     * @param positions   当前持仓
     * @param latestClose code -> 最新收盘价(可缺项)
     * @return 净值
     */
    public static double netValue(double cash,
                                  Map<String, Position> positions,
                                  Map<String, Double> latestClose) {
        double total = cash;
        if (positions != null) {
            for (Map.Entry<String, Position> e : positions.entrySet()) {
                Position p = e.getValue();
                Double px = latestClose == null ? null : latestClose.get(e.getKey());
                double usePx = (px != null && !Double.isNaN(px)) ? px : p.avgCost();
                total += p.shares() * usePx;
            }
        }
        return total;
    }
}
