package com.aistock.recommend;

import java.util.List;

/**
 * 一次推荐的纯名单结果:该买哪些 / 继续持有哪些 / 该卖哪些。
 *
 * <p>本系统是半自动选股工具,只给名单建议,人工去券商下单。
 * 这里不涉及资产规模、不算每只买多少股/多少钱、不记账。
 *
 * @param buy  建议买入(选股器当日 topN;已持有的仍保留并标注「已持有(可加仓)」,不剔除)
 * @param hold 建议继续持有(持仓中未触发卖出条件的票)
 * @param sell 建议卖出(打分转负或触发个股止损;不因「掉出 topN」而卖)
 */
public record Recommendation(List<RecoItem> buy, List<RecoItem> hold, List<RecoItem> sell) {
}
