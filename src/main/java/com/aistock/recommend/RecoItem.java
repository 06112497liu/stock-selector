package com.aistock.recommend;

/**
 * 单条选股建议(纯名单视角,不含仓位/金额)。
 *
 * @param code   股票代码
 * @param price  参考价(当天收盘价;数据缺失时可能为 {@link Double#NaN})
 * @param score  当天加权得分(数据缺失时为 {@link Double#NaN})
 * @param reason 人读理由(如「Top1 选中」「已持有(可加仓)」「打分转负」「触发止损 -8.00%」「继续持有」「数据缺失,建议人工复核」)
 * @param held   该 code 是否已在当前持仓集合中
 */
public record RecoItem(String code, double price, double score, String reason, boolean held) {
}
