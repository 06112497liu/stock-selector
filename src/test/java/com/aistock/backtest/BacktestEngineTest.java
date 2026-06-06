package com.aistock.backtest;

import com.aistock.datasource.Bar;
import com.aistock.feature.MarketPanel;
import com.aistock.selector.FactorSelector;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestEngineTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);
    private static final int N = 40; // 每只票 40 个交易日,够 mom_20/vol_20 起效

    /** 单因子 mom_20 选股器:score 排序 == 动量排序,确定性强,方便控制持仓。 */
    private static FactorSelector momSelector() {
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("mom_20", 1.0);
        return new FactorSelector(w);
    }

    /** 连续 N 天、收盘价 = startClose + slope*i 的线性行情。 */
    private static List<Bar> linearBars(double startClose, double slope) {
        List<Bar> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            double c = startClose + slope * i;
            out.add(new Bar(D0.plusDays(i), c, c, c, c, 1000L));
        }
        return out;
    }

    /** 列表化收盘价(便于篡改单日价格后重建)。code -> 收盘价数组(index=交易日序号)。 */
    private static Map<String, double[]> baseCloses() {
        Map<String, double[]> m = new LinkedHashMap<>();
        // HI 动量最高(斜率最大),会被 topN=1 长期持有;其余作陪衬。
        m.put("HI", closesOf(100.0, 4.0));
        m.put("MID", closesOf(100.0, 2.0));
        m.put("LO", closesOf(100.0, 0.5));
        return m;
    }

    private static double[] closesOf(double startClose, double slope) {
        double[] c = new double[N];
        for (int i = 0; i < N; i++) {
            c[i] = startClose + slope * i;
        }
        return c;
    }

    private static MarketPanel panelFrom(Map<String, double[]> closes) {
        Map<String, List<Bar>> in = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : closes.entrySet()) {
            double[] c = e.getValue();
            List<Bar> bars = new ArrayList<>();
            for (int i = 0; i < c.length; i++) {
                bars.add(new Bar(D0.plusDays(i), c[i], c[i], c[i], c[i], 1000L));
            }
            in.put(e.getKey(), bars);
        }
        return new MarketPanel(in);
    }

    // ---------- 平滑路径 ----------

    @Test
    void smoothRunProducesAlignedNonNaNNav() {
        MarketPanel panel = panelFrom(baseCloses());
        LocalDate start = D0.plusDays(25); // 因子已有值的某天起
        BacktestResult r = new BacktestEngine()
                .run(panel, momSelector(), start, 1, CostConfig.zero());

        assertEquals(r.dates().size(), r.nav().length, "dates 与 nav 必须等长对齐");
        assertTrue(r.nav().length >= 2, "应有多日净值");
        assertEquals(1.0, r.nav()[0], 1e-12, "净值从 1.0 起");
        for (double v : r.nav()) {
            assertFalse(Double.isNaN(v), "净值不应出现 NaN");
            assertTrue(v > 0, "净值应为正");
        }
        // dates 升序
        for (int i = 1; i < r.dates().size(); i++) {
            assertTrue(r.dates().get(i).isAfter(r.dates().get(i - 1)));
        }
    }

    // ---------- T+1:成交价取 d+1 而非 d ----------

    @Test
    void tradesAtNextDayNotSignalDay() {
        // HI 被 topN=1 持有。第一个信号日 d0=start,成交日应为 d0 的下一交易日。
        // NAV[0] 落在成交日;NAV[0]->NAV[1] 的收益 = HI 从成交日收盘到再下一日收盘的收益。
        Map<String, double[]> closes = baseCloses();
        MarketPanel panel = panelFrom(closes);

        // 找 start 对应的索引
        List<LocalDate> days = panel.tradingDays();
        int startIdx = 30;
        LocalDate start = days.get(startIdx);

        BacktestResult r = new BacktestEngine()
                .run(panel, momSelector(), start, 1, CostConfig.zero());

        // 第一个成交日 = days[startIdx+1]
        assertEquals(days.get(startIdx + 1), r.dates().get(0));

        // NAV[0]->NAV[1] 收益应等于 HI 从 days[startIdx+1] 到 days[startIdx+2] 的收益,
        // 绝不能等于「信号日 days[startIdx] 到 startIdx+1」的收益(那是用信号日价成交的错误口径)。
        double[] hi = closes.get("HI");
        double tradeRet = hi[startIdx + 2] / hi[startIdx + 1] - 1.0; // 正确:成交日->次日
        double signalRet = hi[startIdx + 1] / hi[startIdx] - 1.0;     // 错误口径

        double navRet = r.nav()[1] / r.nav()[0] - 1.0;
        assertEquals(tradeRet, navRet, 1e-9, "应使用 d+1 成交日的价格");
        // 两个口径数值不同(线性递增 -> 比值不同),确保测试有判别力
        assertTrue(Math.abs(tradeRet - signalRet) > 1e-9);
    }

    // ---------- 防前视红线:篡改成交日之后的价格只改其之后的 NAV ----------

    @Test
    void tamperingFuturePriceOnlyAffectsLaterNav() {
        LocalDate start = D0.plusDays(30);

        MarketPanel base = panelFrom(baseCloses());
        BacktestResult rBase = new BacktestEngine()
                .run(base, momSelector(), start, 1, CostConfig.zero());

        // 篡改 HI 在某个较晚交易日的收盘价为极端值。
        int tamperIdx = 35; // 远在 start 之后
        Map<String, double[]> tampered = baseCloses();
        tampered.get("HI")[tamperIdx] = 99999.0; // 极端值
        MarketPanel tam = panelFrom(tampered);
        BacktestResult rTam = new BacktestEngine()
                .run(tam, momSelector(), start, 1, CostConfig.zero());

        List<LocalDate> dates = rBase.dates();
        LocalDate tamperDay = base.tradingDays().get(tamperIdx);

        // 找到 NAV 曲线上「成交日序列」中,价格 close[tamperIdx] 首次参与收益计算的日期。
        // close[tamperIdx] 影响的是 跨越 tamperIdx 的那一段收益,即从 tamperIdx-1 到 tamperIdx
        // 以及 tamperIdx 到 tamperIdx+1。对应 NAV 上严格晚于 tamperDay 之前的点不应改变。
        // 红线断言:所有 dates[i] <= (tamperIdx-1 对应日) 的 NAV 必须与 base 完全一致。
        LocalDate beforeTamper = base.tradingDays().get(tamperIdx - 1);

        for (int i = 0; i < dates.size(); i++) {
            LocalDate d = dates.get(i);
            if (!d.isAfter(beforeTamper)) {
                // d <= tamperIdx-1 对应日:该点净值不得被未来篡改影响
                assertEquals(rBase.nav()[i], rTam.nav()[i], 1e-12,
                        "篡改未来价格不得反向影响更早(<=" + beforeTamper + ")的净值, 违规日=" + d);
            }
        }
        // 且必须确实在之后某点产生了差异(否则测试无判别力)
        boolean diverged = false;
        for (int i = 0; i < dates.size(); i++) {
            if (dates.get(i).isAfter(beforeTamper)
                    && Math.abs(rBase.nav()[i] - rTam.nav()[i]) > 1e-6) {
                diverged = true;
                break;
            }
        }
        assertTrue(diverged, "篡改 " + tamperDay + " 的价格应在其之后改变净值");
    }

    // ---------- 成本:slippage>0 净值低于零成本;买卖腿方向 ----------

    @Test
    void slippageLowersNav() {
        LocalDate start = D0.plusDays(25);
        MarketPanel panel = panelFrom(baseCloses());
        FactorSelector sel = momSelector();

        BacktestResult zero = new BacktestEngine()
                .run(panel, sel, start, 1, CostConfig.zero());
        BacktestResult withSlip = new BacktestEngine()
                .run(panel, sel, start, 1, CostConfig.zeroExceptSlippage(0.001));

        double lastZero = zero.nav()[zero.nav().length - 1];
        double lastSlip = withSlip.nav()[withSlip.nav().length - 1];
        assertTrue(lastSlip < lastZero,
                "有滑点的末净值应低于零成本: slip=" + lastSlip + " zero=" + lastZero);
    }

    @Test
    void firstBuyLegChargedOnInitialEntry() {
        // topN=1、持有单票、若全程不换仓,则只有首次建仓的买入腿成本。
        // 末净值/零成本末净值 应约等于 (1 - buyRate)。
        LocalDate start = D0.plusDays(25);
        MarketPanel panel = panelFrom(baseCloses());
        FactorSelector sel = momSelector();

        double buyRate = 0.002;
        CostConfig cost = new CostConfig(0.0, 0.0, buyRate); // 仅滑点充当买入成本

        BacktestResult zero = new BacktestEngine().run(panel, sel, start, 1, CostConfig.zero());
        BacktestResult withCost = new BacktestEngine().run(panel, sel, start, 1, cost);

        // HI 动量恒最高 -> 全程持有 HI、不换仓 -> 只有首次买入腿成本 buyRate(权重1)。
        // 卖出腿只在「卖出已有持仓」时才发生;此处无卖出。
        double ratio = withCost.nav()[withCost.nav().length - 1]
                / zero.nav()[zero.nav().length - 1];
        assertEquals(1.0 - buyRate, ratio, 1e-9,
                "单票全程持有时,仅首次买入腿成本影响净值");
    }

    @Test
    void sellLegOnlyOnExit() {
        // 验证印花税(仅卖出收)只在卖出时计:若全程不卖出,stamp 不影响净值。
        LocalDate start = D0.plusDays(25);
        MarketPanel panel = panelFrom(baseCloses());
        FactorSelector sel = momSelector();

        CostConfig noStamp = new CostConfig(0.0, 0.0, 0.0);
        CostConfig withStamp = new CostConfig(0.0, 0.01, 0.0); // 仅印花税

        BacktestResult a = new BacktestEngine().run(panel, sel, start, 1, noStamp);
        BacktestResult b = new BacktestEngine().run(panel, sel, start, 1, withStamp);

        // 全程持有 HI、从不卖出 -> 印花税不应改变任何净值点
        assertEquals(a.nav().length, b.nav().length);
        for (int i = 0; i < a.nav().length; i++) {
            assertEquals(a.nav()[i], b.nav()[i], 1e-12,
                    "全程不卖出时印花税不应影响净值");
        }
    }
}
