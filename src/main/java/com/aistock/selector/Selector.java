package com.aistock.selector;

import com.aistock.feature.MarketPanel;

import java.time.LocalDate;
import java.util.List;

/**
 * 选股器统一抽象:给定行情面板与某交易日,选出该日的目标持仓(股票代码)。
 *
 * <p>所有实现都必须遵守<b>防前视(look-ahead bias)</b>红线:对交易日 {@code day}
 * 的选股只能依赖 {@code <= day} 的信息。{@link MarketPanel} 已保证横截面因子只含
 * 当天及之前的数据;对需要训练的实现(如 {@link MLSelector}),训练样本的标签区间
 * 也必须落在「决策日之前」,详见各实现说明。
 *
 * <p>实现要求确定性:相同输入必须给出相同顺序的结果(并列时用 code 字典序兜底)。
 */
public interface Selector {

    /**
     * 选出某交易日的目标持仓(最多 topN 只)。
     *
     * @param panel 行情面板(已防前视)
     * @param day   决策日
     * @param topN  取前 N(实现须保证 topN==0 返回空列表,topN<0 抛异常)
     * @return 选中的股票代码,按实现定义的打分降序、并列 code 字典序升序;不超过 topN 个
     */
    List<String> select(MarketPanel panel, LocalDate day, int topN);
}
