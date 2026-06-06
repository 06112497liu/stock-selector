package com.aistock.selector;

import com.aistock.feature.MarketPanel;
import com.aistock.feature.TrainingData;

import smile.data.DataFrame;
import smile.data.Tuple;
import smile.data.formula.Formula;
import smile.math.MathEx;
import smile.regression.GradientTreeBoost;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机器学习选股器:Smile 回归树梯度提升(GradientTreeBoost)预测未来收益打分。
 * 对标 Python LightGBM 版 MLSelector。
 *
 * <h2>流程</h2>
 * <ol>
 *   <li>{@link #fit}:在再训练点 {@code trainEnd},用 {@link TrainingData#upToCutoff(LocalDate)}
 *       取「标签区间 ≤ trainEnd」的样本拟合 GBT 回归(features -> 未来 H 日收益)。</li>
 *   <li>{@link #predict}:对决策日当天有效票预测未来收益,作为打分。</li>
 *   <li>{@link #select}:按预测分降序取 topN(并列 code 字典序升序)。</li>
 * </ol>
 *
 * <h2>防前视红线(最高优先级)</h2>
 * <ul>
 *   <li>标签(未来收益)只进训练目标,绝不进特征(由 {@link TrainingData} 保证)。</li>
 *   <li>{@code fit} 只看 {@code upToCutoff(trainEnd)} 的样本——其标签区间结束日 ≤ trainEnd,
 *       于是这些样本的信号日与 trainEnd 之间天然留有至少 H 个交易日的 <b>embargo gap</b>,
 *       标签区间不与 trainEnd 及其后的测试期重叠。</li>
 *   <li>回测里在 split 点用 split 之前数据 {@code fit} 一次后,任何决策日的 {@code predict}
 *       都只用「已训练好的、只见过 split 前标签」的模型 + 决策日当天(≤ 当天)的横截面特征,
 *       篡改决策日之后的价格不会改变该日及更早的预测。</li>
 * </ul>
 *
 * <h2>退化口径</h2>
 * 训练样本数 &lt; {@link #MIN_TRAIN_SAMPLES} 时标记为<b>未训练</b>:{@link #predict} 返回空 map,
 * {@link #select} 返回空列表(不抛异常,便于回测平滑跑完;固化于测试)。
 * 未先 {@code fit} 直接 {@code predict}/{@code select} 同样按未训练处理。
 *
 * <p>该类有状态(持有已拟合模型),非线程安全;回测中每个再训练点重新 {@code fit} 即可。
 */
public final class MLSelector implements Selector {

    /** 训练样本数下限:少于此数视为数据不足、不训练。 */
    public static final int MIN_TRAIN_SAMPLES = 30;

    /** GBT 树数(基线超参,alpha 未验证,纯框架演示)。 */
    private static final int N_TREES = 100;
    /** 固定随机种子:让训练对相同样本完全可复现(防前视证明的前提)。 */
    private static final long SEED = 20240601L;
    /** 标签列名(刻意区别于特征,杜绝把标签当特征)。 */
    private static final String LABEL_COL = "y";

    private final List<String> factorKeys;
    private final int horizon;

    private GradientTreeBoost model; // null = 未训练
    private String[] featureColumns; // 训练时用的特征列名(预测须一致)

    /**
     * @param factorKeys 特征因子键(顺序即特征顺序),如 {@link MarketPanel#FACTORS}
     * @param horizon    标签前瞻交易日数 H(必须 >= 1)
     */
    public MLSelector(List<String> factorKeys, int horizon) {
        if (factorKeys == null || factorKeys.isEmpty()) {
            throw new IllegalArgumentException("factorKeys must not be empty");
        }
        if (horizon < 1) {
            throw new IllegalArgumentException("horizon must be >= 1, got " + horizon);
        }
        this.factorKeys = List.copyOf(factorKeys);
        this.horizon = horizon;
    }

    /** 是否已成功训练(样本充足并拟合)。 */
    public boolean isTrained() {
        return model != null;
    }

    /**
     * 在再训练点 trainEnd 拟合模型:只用标签区间 ≤ trainEnd 的样本(防前视 + embargo)。
     * 样本数 &lt; {@link #MIN_TRAIN_SAMPLES} 时标记未训练并返回。
     *
     * @return 实际入选训练的样本数(供测试观测「只用了 trainEnd 前样本」)
     */
    public int fit(MarketPanel panel, LocalDate trainEnd, int horizonArg) {
        if (horizonArg != this.horizon) {
            throw new IllegalArgumentException(
                    "horizon mismatch: selector=" + this.horizon + " fit=" + horizonArg);
        }
        TrainingData td = new TrainingData(panel, factorKeys, horizon);
        List<TrainingData.Sample> train = td.upToCutoff(trainEnd);

        if (train.size() < MIN_TRAIN_SAMPLES) {
            this.model = null;
            this.featureColumns = null;
            return train.size();
        }

        int p = factorKeys.size();
        // 列名:f0..f{p-1} + LABEL_COL。建 double[][] 矩阵,最后一列是标签。
        String[] cols = new String[p + 1];
        for (int k = 0; k < p; k++) {
            cols[k] = "f" + k;
        }
        cols[p] = LABEL_COL;

        double[][] matrix = new double[train.size()][p + 1];
        for (int r = 0; r < train.size(); r++) {
            TrainingData.Sample s = train.get(r);
            double[] f = s.features();
            for (int k = 0; k < p; k++) {
                matrix[r][k] = f[k];
            }
            matrix[r][p] = s.label();
        }

        DataFrame df = DataFrame.of(matrix, cols);
        Formula formula = Formula.lhs(LABEL_COL);

        // 确定性训练:固定种子 + 全样本采样(sampling_rate=1.0),让模型成为训练数据的
        // 纯函数。这样「相同训练数据 -> 相同模型 -> 相同预测」,是防前视证明的前提:
        // 篡改决策日之后的价格既不改变训练样本,也不改变模型与该日预测。
        MathEx.setSeed(SEED);
        java.util.Properties props = new java.util.Properties();
        props.setProperty("smile.gradient_boost.trees", Integer.toString(N_TREES));
        props.setProperty("smile.gradient_boost.sampling_rate", "1.0");
        this.model = GradientTreeBoost.fit(formula, df, props);

        String[] featCols = new String[p];
        System.arraycopy(cols, 0, featCols, 0, p);
        this.featureColumns = featCols;
        return train.size();
    }

    /**
     * 对决策日当天有效票预测未来收益打分。未训练时返回空 map。
     *
     * <p>只用 {@code day}(≤ day)的横截面特征;不触碰 day 之后的任何数据。
     *
     * @return code -> 预测分(预测的未来 H 日收益);未训练或当天无有效票时为空 map
     */
    public Map<String, Double> predict(MarketPanel panel, LocalDate day) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (model == null) {
            return out;
        }
        int p = factorKeys.size();

        // 收集当天「全因子有效」的票及其特征。
        List<String> codes = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        for (String code : panel.factorOn(day, factorKeys.get(0)).keySet()) {
            double[] feats = new double[p];
            boolean ok = true;
            for (int k = 0; k < p; k++) {
                Double v = panel.factorOn(day, factorKeys.get(k)).get(code);
                if (v == null || Double.isNaN(v)) {
                    ok = false;
                    break;
                }
                feats[k] = v;
            }
            if (ok) {
                codes.add(code);
                rows.add(feats);
            }
        }
        if (codes.isEmpty()) {
            return out;
        }

        double[][] matrix = new double[rows.size()][p];
        for (int r = 0; r < rows.size(); r++) {
            matrix[r] = rows.get(r);
        }
        DataFrame df = DataFrame.of(matrix, featureColumns);

        for (int r = 0; r < codes.size(); r++) {
            Tuple t = df.get(r);
            out.put(codes.get(r), model.predict(t));
        }
        return out;
    }

    /**
     * 按预测分降序取 topN(并列 code 字典序升序)。未训练时返回空列表。
     */
    @Override
    public List<String> select(MarketPanel panel, LocalDate day, int topN) {
        if (topN < 0) {
            throw new IllegalArgumentException("topN must be >= 0, got " + topN);
        }
        if (topN == 0 || model == null) {
            return List.of();
        }
        Map<String, Double> scores = predict(panel, day);
        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort(
                Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue())
                        .reversed()
                        .thenComparing(Map.Entry::getKey));
        List<String> result = new ArrayList<>();
        int limit = Math.min(topN, entries.size());
        for (int i = 0; i < limit; i++) {
            result.add(entries.get(i).getKey());
        }
        return result;
    }
}
