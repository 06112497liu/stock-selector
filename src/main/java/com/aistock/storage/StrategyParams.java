package com.aistock.storage;

import com.aistock.selector.FactorSelector;
import com.aistock.web.SignalService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 选股策略可配参数(按 market 持久化)。
 *
 * <p>边界:只含<b>选股策略参数</b> —— 买入名单取前 topN、个股止损线 stopLossPct、
 * 三个因子权重 factorWeights(mom_20 / reversal_5 / vol_20)。
 * <b>刻意不含</b> lot(最小交易单位)/ 初始资金 / 回撤护栏:Java 版是纯名单建议、
 * 不记账下单,这些用不上。
 *
 * <p>默认值 {@link #defaults()} 复用现有硬编码常量:topN={@link SignalService#DEFAULT_TOP_N}、
 * stopLoss={@link SignalService#DEFAULT_STOP_LOSS_PCT}、权重 {@link FactorSelector#DEFAULT_WEIGHTS}。
 *
 * @param topN          买入名单取前 N(1..20)
 * @param stopLossPct   个股止损线(≤0 且 ≥-0.5,如 -0.08 表示自入场价跌 8%)
 * @param factorWeights 因子权重 factor -> weight(正=该因子越大越优先,负=越小越优先)
 */
public record StrategyParams(int topN, double stopLossPct, Map<String, Double> factorWeights) {

    public StrategyParams {
        factorWeights = Map.copyOf(factorWeights);
    }

    /** 默认参数:复用现有常量,权重取 DEFAULT_WEIGHTS 的副本。 */
    public static StrategyParams defaults() {
        return new StrategyParams(
                SignalService.DEFAULT_TOP_N,
                SignalService.DEFAULT_STOP_LOSS_PCT,
                new LinkedHashMap<>(FactorSelector.DEFAULT_WEIGHTS));
    }
}
