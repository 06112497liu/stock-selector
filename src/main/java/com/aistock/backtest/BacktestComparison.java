package com.aistock.backtest;

import com.aistock.feature.MarketPanel;
import com.aistock.selector.FactorSelector;
import com.aistock.selector.MLSelector;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Factor 策略 vs ML 策略的样本外回测对比入口。
 *
 * <p>两条策略都从同一 {@code split}(样本外起点信号日)起回测、同一成本口径,
 * 便于把净值曲线与指标并排展示。
 *
 * <h2>防前视红线(ML 侧最关键)</h2>
 * ML 选股器在 <b>split 点用 split 之前的数据训练一次</b>:
 * {@link MLSelector#fit(MarketPanel, LocalDate, int)} 传入 {@code trainEnd=split},
 * 内部只取「标签区间结束日 ≤ split」的样本——于是训练样本的信号日与 split 之间
 * 至少留 H 个交易日的 <b>embargo gap</b>,标签区间不与样本外测试期(≥ split)重叠。
 * 训练后模型固定不变,回测里任何决策日的预测都只用该模型 + 决策日当天(≤ 当天)的
 * 横截面特征,绝不会用到决策日之后训练的模型/数据。
 *
 * <p><b>免责</b>:ML 与 Factor 同样 alpha 未验证、可能过拟合,纯框架演示,不可据此实盘。
 */
public final class BacktestComparison {

    /** 对比结果:两条 {@link BacktestResult} + ML 是否实际训练成功。 */
    public record Result(BacktestResult factor,
                        BacktestResult ml,
                        boolean mlTrained,
                        int mlTrainSamples) {
    }

    private final BacktestEngine engine = new BacktestEngine();

    /**
     * 跑一次 Factor vs ML 对比回测。
     *
     * @param panel   行情面板(已防前视)
     * @param split   样本外起点信号日(≥ split 的交易日才产生信号)
     * @param topN    等权组合持仓数
     * @param cost    交易成本费率
     * @param horizon ML 标签前瞻交易日数 H(也是 embargo 长度下限)
     * @return factor 与 ml 两条净值结果 + ML 训练状态
     */
    public Result run(MarketPanel panel, LocalDate split, int topN,
                      CostConfig cost, int horizon) {
        return run(panel, split, topN, cost, horizon, FactorSelector.DEFAULT_WEIGHTS);
    }

    /**
     * 跑一次 Factor vs ML 对比回测,Factor 侧使用<b>给定因子权重</b>(在线可配)。
     *
     * @param factorWeights Factor 策略的因子权重(对标参数配置页)
     */
    public Result run(MarketPanel panel, LocalDate split, int topN,
                      CostConfig cost, int horizon, Map<String, Double> factorWeights) {
        // Factor:可配权重,从 split 起回测。
        FactorSelector factorSelector = new FactorSelector(factorWeights);
        BacktestResult factorResult = engine.run(panel, factorSelector, split, topN, cost);

        // ML:在 split 点用 split 之前数据训练一次(留 embargo),再从 split 起回测。
        List<String> factorKeys = MarketPanel.FACTORS;
        MLSelector mlSelector = new MLSelector(factorKeys, horizon);
        int trainSamples = mlSelector.fit(panel, split, horizon);
        BacktestResult mlResult = engine.run(panel, mlSelector, split, topN, cost);

        return new Result(factorResult, mlResult, mlSelector.isTrained(), trainSamples);
    }
}
