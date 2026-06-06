package com.aistock.feature;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 监督学习训练样本构建器(带防前视的标签),供 ML 选股层使用。
 *
 * <h2>样本定义</h2>
 * 对每个交易日 d、每只当天有效票 code,产出一条样本 {@link Sample}:
 * <ul>
 *   <li><b>features</b> = {@code [panel.factorOn(d, f) for f in factorKeys]}
 *       (横截面标准化后的因子,已 ≤ d)。任一因子在该票当天缺失/NaN -> 该样本<b>跳过</b>。</li>
 *   <li><b>label</b> = 未来 H 日收益 = {@code close[d+H]/close[d] - 1},
 *       其中 d+H 指「{@link MarketPanel#tradingDays()} 中 d 之后第 H 个交易日」。
 *       若 d 之后不足 H 个交易日,或区间端点价格缺失 -> 该样本<b>无标签、不产出</b>。</li>
 * </ul>
 *
 * <h2>防前视红线(最高优先级)</h2>
 * <ul>
 *   <li>标签 label 是未来收益,<b>只能</b>作训练目标,<b>绝不进 features</b>。</li>
 *   <li>每条样本记录其「标签区间结束日」{@link Sample#labelEndDay()} = d+H 对应交易日。
 *       训练时<b>只能用标签区间已完全落在决策日之前</b>的样本——即
 *       {@code labelEndDay <= cutoff}。{@link #upToCutoff(LocalDate)} 即据此过滤,
 *       天然产生 embargo:决策日 cutoff 与最后一条训练样本的信号日 d 之间至少隔 H 个
 *       交易日(因为要求 d+H <= cutoff)。</li>
 * </ul>
 *
 * 该类只读 panel 的 ≤ 各日横截面与收盘价,不修改任何状态;构造即固化全量样本。
 */
public final class TrainingData {

    /**
     * 一条训练样本。
     *
     * @param day         信号日 d(特征截面所属交易日,所有特征 ≤ d)
     * @param code        股票代码
     * @param features    特征向量(横截面标准化因子,顺序同 factorKeys),不含标签
     * @param label       标签 = 未来 H 日收益 close[d+H]/close[d]-1
     * @param labelEndDay 标签区间结束日 = d 之后第 H 个交易日(防前视过滤的关键)
     */
    public record Sample(LocalDate day,
                         String code,
                         double[] features,
                         double label,
                         LocalDate labelEndDay) {
    }

    private final List<String> factorKeys;
    private final int horizon;
    private final List<Sample> samples;

    /**
     * @param panel      行情面板(已防前视)
     * @param factorKeys 特征所用因子键(顺序即特征顺序),如 [mom_20, reversal_5, vol_20]
     * @param horizon    标签前瞻交易日数 H(必须 >= 1)
     */
    public TrainingData(MarketPanel panel, List<String> factorKeys, int horizon) {
        if (panel == null) {
            throw new IllegalArgumentException("panel must not be null");
        }
        if (factorKeys == null || factorKeys.isEmpty()) {
            throw new IllegalArgumentException("factorKeys must not be empty");
        }
        if (horizon < 1) {
            throw new IllegalArgumentException("horizon must be >= 1, got " + horizon);
        }
        this.factorKeys = List.copyOf(factorKeys);
        this.horizon = horizon;
        this.samples = build(panel);
    }

    private List<Sample> build(MarketPanel panel) {
        List<LocalDate> days = panel.tradingDays();
        List<Sample> out = new ArrayList<>();

        for (int i = 0; i < days.size(); i++) {
            LocalDate d = days.get(i);
            int endIdx = i + horizon;
            if (endIdx >= days.size()) {
                break; // d 之后不足 H 个交易日 -> 之后的 d 也都不足,提前结束
            }
            LocalDate labelEnd = days.get(endIdx);

            // 当天「所有所需因子均有效」的票:以第一个因子的横截面 key 集为基准,
            // 逐票检查全部因子非缺失。MarketPanel 防前视已保证横截面只含 ≤ d 数据。
            for (String code : panel.factorOn(d, factorKeys.get(0)).keySet()) {
                double[] feats = new double[factorKeys.size()];
                boolean ok = true;
                for (int k = 0; k < factorKeys.size(); k++) {
                    Double v = panel.factorOn(d, factorKeys.get(k)).get(code);
                    if (v == null || Double.isNaN(v)) {
                        ok = false;
                        break;
                    }
                    feats[k] = v;
                }
                if (!ok) {
                    continue;
                }
                double p0 = panel.closeOn(code, d);
                double p1 = panel.closeOn(code, labelEnd);
                if (Double.isNaN(p0) || Double.isNaN(p1) || p0 == 0.0) {
                    continue; // 端点价缺失 -> 无有效标签
                }
                double label = p1 / p0 - 1.0;
                out.add(new Sample(d, code, feats, label, labelEnd));
            }
        }
        return out;
    }

    /** 特征所用因子键(顺序即特征顺序),不可变。 */
    public List<String> factorKeys() {
        return factorKeys;
    }

    /** 标签前瞻交易日数 H。 */
    public int horizon() {
        return horizon;
    }

    /** 全量样本(不含防前视过滤),不可变。 */
    public List<Sample> all() {
        return Collections.unmodifiableList(samples);
    }

    /**
     * 防前视过滤:只保留<b>标签区间已完全落在 cutoff 之前</b>的样本,
     * 即 {@code labelEndDay <= cutoff}。用于回测/训练按时间切分、留 embargo。
     *
     * <p>由 {@code labelEndDay = d+H} 且要求 {@code d+H <= cutoff} 可知:
     * 入选样本的信号日 d 与 cutoff 之间至少隔 H 个交易日,标签区间不会与 cutoff
     * 及其之后的测试期重叠,杜绝标签泄漏。
     *
     * @param cutoff 决策日(含):标签区间结束日须 ≤ 此日
     * @return 满足条件的样本(顺序与 all() 一致),不可变
     */
    public List<Sample> upToCutoff(LocalDate cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff must not be null");
        }
        List<Sample> out = new ArrayList<>();
        for (Sample s : samples) {
            if (!s.labelEndDay().isAfter(cutoff)) { // labelEndDay <= cutoff
                out.add(s);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
