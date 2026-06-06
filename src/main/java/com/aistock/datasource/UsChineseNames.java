package com.aistock.datasource;

import java.util.Map;
import java.util.Optional;

/**
 * 美股代码 → 中文译名的内置静态映射表(固定映射,非个股数据)。
 *
 * <p>Yahoo 行情接口只提供英文 {@code longName}(如 "Apple Inc."),不提供中文名。
 * 本表收录公认通用译名(如 AAPL→苹果、NVDA→英伟达),供页面优先展示中文。这是
 * 一张<b>人工维护的固定字典</b>,既不向任何接口请求、也不编造个股行情,只把代码翻成
 * 中文俗称——译名缺失时调用方自行降级到英文名 / code(见 {@link #of(String)})。
 *
 * <p>用法(美股 nameFn 降级链):
 * <pre>
 * code -&gt; UsChineseNames.of(code).orElseGet(() -&gt; yahooClient.fetchName(code))
 * </pre>
 * 即:<b>内置中文译名 → Yahoo 英文 longName → code</b>(最后一级由 fetchName 兜底)。
 *
 * <p>查表<b>大小写不敏感</b>:内部统一 {@code toUpperCase};表外代码返回
 * {@link Optional#empty()},绝不抛。A 股名称走东财中文名,与本表无关。
 */
public final class UsChineseNames {

    /** 代码(大写)→ 中文译名。覆盖当前篮子 20 只 + 常见美股,便于日后加票。 */
    private static final Map<String, String> NAMES = Map.ofEntries(
            // —— 当前篮子 20 只 ——
            Map.entry("AAPL", "苹果"),
            Map.entry("MSFT", "微软"),
            Map.entry("NVDA", "英伟达"),
            Map.entry("GOOGL", "谷歌"),
            Map.entry("AMZN", "亚马逊"),
            Map.entry("META", "Meta"),
            Map.entry("TSLA", "特斯拉"),
            Map.entry("AVGO", "博通"),
            Map.entry("JPM", "摩根大通"),
            Map.entry("V", "Visa"),
            Map.entry("WMT", "沃尔玛"),
            Map.entry("MA", "万事达"),
            Map.entry("UNH", "联合健康"),
            Map.entry("XOM", "埃克森美孚"),
            Map.entry("JNJ", "强生"),
            Map.entry("PG", "宝洁"),
            Map.entry("HD", "家得宝"),
            Map.entry("COST", "好市多"),
            Map.entry("ORCL", "甲骨文"),
            Map.entry("NFLX", "奈飞"),
            // —— 常见美股(篮子外,便于日后扩充)——
            Map.entry("GOOG", "谷歌"),
            Map.entry("KO", "可口可乐"),
            Map.entry("PEP", "百事"),
            Map.entry("DIS", "迪士尼"),
            Map.entry("NKE", "耐克"),
            Map.entry("MCD", "麦当劳"),
            Map.entry("INTC", "英特尔"),
            Map.entry("AMD", "AMD"),
            Map.entry("CRM", "Salesforce"),
            Map.entry("ADBE", "Adobe"),
            Map.entry("PYPL", "PayPal"),
            Map.entry("BAC", "美国银行"),
            Map.entry("WFC", "富国银行"),
            Map.entry("ABBV", "艾伯维"),
            Map.entry("LLY", "礼来"),
            Map.entry("PFE", "辉瑞"),
            Map.entry("MRK", "默克"),
            Map.entry("TMO", "赛默飞"),
            Map.entry("ABT", "雅培"),
            Map.entry("CVX", "雪佛龙"),
            Map.entry("CSCO", "思科"),
            Map.entry("QCOM", "高通"),
            Map.entry("TXN", "德州仪器"),
            Map.entry("IBM", "IBM"),
            Map.entry("GE", "通用电气"),
            Map.entry("BA", "波音"),
            Map.entry("CAT", "卡特彼勒"),
            Map.entry("TSM", "台积电"),
            Map.entry("BABA", "阿里巴巴"),
            Map.entry("JD", "京东"),
            Map.entry("PDD", "拼多多"),
            Map.entry("BIDU", "百度"),
            Map.entry("NIO", "蔚来")
    );

    private UsChineseNames() {
    }

    /**
     * 查美股代码的内置中文译名。
     *
     * @param code 美股代码(大小写不敏感;可为 null)
     * @return 命中返回中文译名;表外 / null / 空白返回 {@link Optional#empty()}
     */
    public static Optional<String> of(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String key = code.trim().toUpperCase();
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(NAMES.get(key));
    }
}
