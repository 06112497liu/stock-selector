package com.aistock.datasource;

import java.util.List;

/**
 * K 线行情数据源的统一抽象:按 code + 周期取一段 K 线。
 *
 * <p>由 {@link YahooClient}(美股)与 {@link EastMoneyClient}(A 股)实现,
 * {@code KlineService} 按 market 分流注入。实现方<b>绝不抛异常</b>:任何网络 /
 * 限流 / 代理拦截 / 结构异常一律降级为返回空 List(前端显示「暂无数据」)。
 */
public interface KlineSource {

    /**
     * 取 {@code code} 在周期 {@code period} 下的 K 线序列(oldest-first)。
     *
     * @param code   股票代码(美股如 {@code "AAPL"},A 股如 {@code "600519"})
     * @param period 周期枚举
     * @return K 线列表;失败降级为空 List,绝不抛
     */
    List<KlinePoint> fetchKline(String code, KlinePeriod period);
}
