package com.aistock.selector;

import com.aistock.feature.MarketPanel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加权因子打分选股器(对标 Python 版 FactorSelector)。
 *
 * <p>对某交易日横截面中每只有效票,按权重线性加权各标准化因子得到 score,
 * 取 score 最高的 topN。score = Σ weight_f × normalized_factor_f。
 * 权重正号表示「因子越大越好」,负号表示「越小越好」。
 *
 * <p>{@link #DEFAULT_WEIGHTS} 只是一组基线(baseline)权重,用于把流程跑通;
 * 其是否真能选出超额收益(alpha)尚未经样本外验证,不应据此实盘。
 */
public final class FactorSelector implements Selector {

    /**
     * 基线权重(仅作流程演示,alpha 未验证)。
     * 对应 {@link MarketPanel#FACTORS} 三个因子。
     */
    public static final Map<String, Double> DEFAULT_WEIGHTS;

    static {
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("mom_20", 1.0);
        w.put("reversal_5", 0.5);
        w.put("vol_20", -0.5);
        DEFAULT_WEIGHTS = Map.copyOf(w);
    }

    private final Map<String, Double> weights;

    /**
     * @param weights 因子权重,形如 {"mom_20":1.0,"reversal_5":0.5,"vol_20":-0.5}
     */
    public FactorSelector(Map<String, Double> weights) {
        if (weights == null) {
            throw new IllegalArgumentException("weights must not be null");
        }
        this.weights = Map.copyOf(weights);
    }

    /**
     * 计算某交易日每只有效票的加权得分。
     *
     * <p>某因子在该票缺失(不在该因子横截面中)时,该项按 0 计入(跳过)。
     *
     * @return code -> score(只含当天有效票);保持确定性,可用于展示/调试
     */
    public Map<String, Double> scores(MarketPanel panel, LocalDate day) {
        // 有效票 = 在任一被加权因子的当日横截面中出现的票。
        // 由于 MarketPanel 防前视:有效票在每个因子横截面里要么全在、要么全不在,
        // 因此取并集即可,且并集 == 各因子横截面 key 集合。
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Map.Entry<String, Double> we : weights.entrySet()) {
            String factor = we.getKey();
            double weight = we.getValue();
            Map<String, Double> xs = panel.factorOn(day, factor);
            for (Map.Entry<String, Double> e : xs.entrySet()) {
                String code = e.getKey();
                Double v = e.getValue();
                double contrib = (v == null || Double.isNaN(v)) ? 0.0 : weight * v;
                scores.merge(code, contrib, Double::sum);
            }
        }
        return scores;
    }

    /**
     * 选出某交易日加权得分最高的 topN 只票。
     *
     * <p>按 score 降序;score 并列时用 code 字典序升序兜底,保证结果确定。
     * 当有效票数少于 topN 时返回全部有效票。
     *
     * @param topN 取前 N(必须 >= 0;0 返回空列表)
     * @return 选中的股票代码,按上述排序;不超过 topN 个
     */
    @Override
    public List<String> select(MarketPanel panel, LocalDate day, int topN) {
        if (topN < 0) {
            throw new IllegalArgumentException("topN must be >= 0, got " + topN);
        }
        if (topN == 0) {
            return List.of();
        }
        Map<String, Double> scores = scores(panel, day);
        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort(
                Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue())
                        .reversed()
                        .thenComparing(Map.Entry::getKey));
        List<String> out = new ArrayList<>();
        int limit = Math.min(topN, entries.size());
        for (int i = 0; i < limit; i++) {
            out.add(entries.get(i).getKey());
        }
        return out;
    }
}
