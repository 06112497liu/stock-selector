package com.aistock.feature;

import com.aistock.datasource.Bar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 全市场、按交易日对齐的因子面板(对标 Python 版 build_market_panel)。
 *
 * <p>输入每只股票按 date 升序的日线序列,内部对每只票计算各时间序列因子,
 * 再<b>按交易日做横截面标准化</b>:对每个交易日、每个因子,取当天所有非 NaN 的
 * 票做 {@link CrossSection#rankNormalize}。
 *
 * <p><b>防前视(look-ahead bias)</b>:某天某票若任一所需因子为 NaN(历史不足),
 * 该票当天<b>不进入横截面</b>——既不参与排名,也不可被选。这样回测时取任意历史
 * 交易日的横截面都只依赖当天及之前的数据,不会用到未来信息。
 *
 * <p>因子窗口命名与 Python 版保持一致:
 * <ul>
 *   <li>{@code mom_20}     = momentum(20)</li>
 *   <li>{@code reversal_5} = reversal(5)</li>
 *   <li>{@code vol_20}     = volatility(20)</li>
 * </ul>
 *
 * 构造后不可变,可被回测安全复用。
 */
public final class MarketPanel {

    /** 本面板计算并标准化的因子名(顺序即展示顺序)。 */
    public static final List<String> FACTORS = List.of("mom_20", "reversal_5", "vol_20");

    private static final int MOM_WINDOW = 20;
    private static final int REVERSAL_WINDOW = 5;
    private static final int VOL_WINDOW = 20;

    /** 全市场并集交易日(升序,不可变)。 */
    private final List<LocalDate> tradingDays;

    /**
     * 横截面标准化后的因子值:factor -> (day -> (code -> normalized value))。
     * 只包含当天「所有所需因子均非 NaN」的有效票。
     */
    private final Map<String, Map<LocalDate, Map<String, Double>>> normalized;

    /** 收盘价快速查表:code -> (day -> close)。 */
    private final Map<String, Map<LocalDate, Double>> closeByCodeDay;

    /**
     * @param barsByCode 每只股票按 date 升序的日线序列(key=股票代码)
     */
    public MarketPanel(Map<String, List<Bar>> barsByCode) {
        // 1. 收盘价表 + 全市场并集交易日
        Map<String, Map<LocalDate, Double>> closes = new LinkedHashMap<>();
        TreeSet<LocalDate> allDays = new TreeSet<>();
        for (Map.Entry<String, List<Bar>> e : barsByCode.entrySet()) {
            String code = e.getKey();
            List<Bar> bars = e.getValue();
            Map<LocalDate, Double> byDay = new LinkedHashMap<>();
            for (Bar b : bars) {
                byDay.put(b.date(), b.close());
                allDays.add(b.date());
            }
            closes.put(code, byDay);
        }
        this.closeByCodeDay = closes;
        this.tradingDays = List.copyOf(allDays);

        // 2. 每只票算原始时间序列因子,索引为该票自己的 date 序列
        //    raw: factor -> (code -> (day -> raw value))
        Map<String, Map<String, Map<LocalDate, Double>>> raw = new LinkedHashMap<>();
        for (String f : FACTORS) {
            raw.put(f, new LinkedHashMap<>());
        }
        for (Map.Entry<String, List<Bar>> e : barsByCode.entrySet()) {
            String code = e.getKey();
            List<Bar> bars = e.getValue();
            putRaw(raw.get("mom_20"), code, bars, Factors.momentum(bars, MOM_WINDOW));
            putRaw(raw.get("reversal_5"), code, bars, Factors.reversal(bars, REVERSAL_WINDOW));
            putRaw(raw.get("vol_20"), code, bars, Factors.volatility(bars, VOL_WINDOW));
        }

        // 3. 逐交易日横截面标准化;防前视:任一因子 NaN 的票当天整体剔除
        Map<String, Map<LocalDate, Map<String, Double>>> norm = new LinkedHashMap<>();
        for (String f : FACTORS) {
            norm.put(f, new TreeMap<>());
        }
        for (LocalDate day : this.tradingDays) {
            // 找出当天「所有所需因子均有有效值」的票
            List<String> validCodes = new ArrayList<>();
            for (String code : barsByCode.keySet()) {
                boolean ok = true;
                for (String f : FACTORS) {
                    Double v = raw.get(f).get(code).get(day);
                    if (v == null || Double.isNaN(v)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    validCodes.add(code);
                }
            }
            // 对每个因子,只用有效票做横截面 rankNormalize
            for (String f : FACTORS) {
                Map<String, Double> xs = new LinkedHashMap<>();
                for (String code : validCodes) {
                    xs.put(code, raw.get(f).get(code).get(day));
                }
                norm.get(f).put(day, CrossSection.rankNormalize(xs));
            }
        }
        this.normalized = norm;
    }

    private static void putRaw(Map<String, Map<LocalDate, Double>> target,
                               String code, List<Bar> bars, double[] values) {
        Map<LocalDate, Double> byDay = new LinkedHashMap<>();
        for (int i = 0; i < bars.size(); i++) {
            byDay.put(bars.get(i).date(), values[i]);
        }
        target.put(code, byDay);
    }

    /**
     * @return 全市场并集交易日,升序,不可变。
     */
    public List<LocalDate> tradingDays() {
        return tradingDays;
    }

    /**
     * 某交易日某因子的「横截面标准化后」取值,只含当天有效票
     * (历史不足或某因子缺失的票已被剔除)。
     *
     * @param day    交易日
     * @param factor 因子名(见 {@link #FACTORS})
     * @return code -> 标准化值(落在 rankNormalize 区间 [-0.5, 0.5]);
     *         无该因子或该日无数据时返回空 map
     */
    public Map<String, Double> factorOn(LocalDate day, String factor) {
        Map<LocalDate, Map<String, Double>> byDay = normalized.get(factor);
        if (byDay == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> xs = byDay.get(day);
        if (xs == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(xs);
    }

    /**
     * 某票某交易日的收盘价(给选股/回测取价用)。
     *
     * @return 收盘价;该票当天无 bar 时返回 {@link Double#NaN}
     */
    public double closeOn(String code, LocalDate day) {
        Map<LocalDate, Double> byDay = closeByCodeDay.get(code);
        if (byDay == null) {
            return Double.NaN;
        }
        Double v = byDay.get(day);
        return v == null ? Double.NaN : v;
    }
}
