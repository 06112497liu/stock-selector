package com.aistock.feature;

import com.aistock.datasource.Bar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingDataTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);
    private static final int N = 40;
    private static final int H = 5;

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

    private static double[] closesOf(double start, double slope) {
        double[] c = new double[N];
        for (int i = 0; i < N; i++) {
            c[i] = start + slope * i;
        }
        return c;
    }

    private static Map<String, double[]> baseCloses() {
        Map<String, double[]> m = new LinkedHashMap<>();
        m.put("HI", closesOf(100.0, 4.0));
        m.put("MID", closesOf(100.0, 2.0));
        m.put("LO", closesOf(100.0, 0.5));
        return m;
    }

    /** 标签 = 「d 之后第 H 个交易日」的收益,且用对了交易日定位(非自然日)。 */
    @Test
    void labelIsFutureHTradingDayReturn() {
        Map<String, double[]> closes = baseCloses();
        MarketPanel panel = panelFrom(closes);
        TrainingData td = new TrainingData(panel, MarketPanel.FACTORS, H);
        List<LocalDate> days = panel.tradingDays();

        // 找一条 HI 在某早期可标注日的样本,手工核对标签 = close[d+H]/close[d]-1。
        boolean checked = false;
        for (TrainingData.Sample s : td.all()) {
            if (!s.code().equals("HI")) {
                continue;
            }
            int di = days.indexOf(s.day());
            // labelEndDay 必须正好是 d 之后第 H 个交易日
            assertEquals(days.get(di + H), s.labelEndDay(),
                    "labelEndDay 必须是 d 之后第 H 个交易日");
            double[] hi = closes.get("HI");
            double expected = hi[di + H] / hi[di] - 1.0;
            assertEquals(expected, s.label(), 1e-12,
                    "标签必须 = close[d+H]/close[d]-1");
            checked = true;
        }
        assertTrue(checked, "应至少核对到一条 HI 样本");
    }

    /** 不足 H 个未来交易日的样本必须被剔除:最后 H 个交易日不产出任何样本。 */
    @Test
    void insufficientHorizonSamplesDropped() {
        MarketPanel panel = panelFrom(baseCloses());
        TrainingData td = new TrainingData(panel, MarketPanel.FACTORS, H);
        List<LocalDate> days = panel.tradingDays();

        LocalDate lastSignalAllowed = days.get(days.size() - 1 - H);
        for (TrainingData.Sample s : td.all()) {
            assertFalse(s.day().isAfter(lastSignalAllowed),
                    "信号日 " + s.day() + " 之后不足 H 个交易日,不应产出样本");
        }
        // 且最后 H 个交易日确实无样本
        for (TrainingData.Sample s : td.all()) {
            int di = days.indexOf(s.day());
            assertTrue(di + H < days.size(), "样本必须有完整 H 日前瞻");
        }
    }

    /** 标签绝不进特征:特征维度 == 因子数,不含标签列。 */
    @Test
    void labelNotInFeatures() {
        MarketPanel panel = panelFrom(baseCloses());
        TrainingData td = new TrainingData(panel, MarketPanel.FACTORS, H);
        for (TrainingData.Sample s : td.all()) {
            assertEquals(MarketPanel.FACTORS.size(), s.features().length,
                    "特征维度必须等于因子数,标签不得混入特征");
            for (double f : s.features()) {
                assertFalse(Double.isNaN(f), "特征不应为 NaN(NaN 样本应被剔除)");
            }
        }
    }

    /** cutoff 过滤(防泄漏):只保留 labelEndDay <= cutoff 的样本。 */
    @Test
    void upToCutoffKeepsOnlyClosedLabels() {
        MarketPanel panel = panelFrom(baseCloses());
        TrainingData td = new TrainingData(panel, MarketPanel.FACTORS, H);
        List<LocalDate> days = panel.tradingDays();

        LocalDate cutoff = days.get(25);
        List<TrainingData.Sample> filtered = td.upToCutoff(cutoff);
        assertFalse(filtered.isEmpty(), "应有满足条件的样本");
        for (TrainingData.Sample s : filtered) {
            assertFalse(s.labelEndDay().isAfter(cutoff),
                    "labelEndDay 必须 <= cutoff,防止标签区间与测试期重叠");
            // 推论:信号日与 cutoff 之间至少隔 H 个交易日(embargo)
            int di = days.indexOf(s.day());
            int ci = days.indexOf(cutoff);
            assertTrue(ci - di >= H,
                    "信号日与 cutoff 之间至少留 H 个交易日 embargo");
        }
    }

    /** 篡改 cutoff 之后的价格,不改变 upToCutoff 内已有样本的标签(防前视)。 */
    @Test
    void tamperingFuturePriceDoesNotChangeEarlierLabels() {
        MarketPanel base = panelFrom(baseCloses());
        TrainingData tdBase = new TrainingData(base, MarketPanel.FACTORS, H);
        List<LocalDate> days = base.tradingDays();
        LocalDate cutoff = days.get(25);
        List<TrainingData.Sample> baseSamples = tdBase.upToCutoff(cutoff);

        // 篡改远晚于 cutoff+embargo 的价格
        Map<String, double[]> tampered = baseCloses();
        tampered.get("HI")[35] = 99999.0;
        MarketPanel tam = panelFrom(tampered);
        TrainingData tdTam = new TrainingData(tam, MarketPanel.FACTORS, H);
        List<TrainingData.Sample> tamSamples = tdTam.upToCutoff(cutoff);

        assertEquals(baseSamples.size(), tamSamples.size(),
                "cutoff 内样本数不应被未来价格影响");
        for (int i = 0; i < baseSamples.size(); i++) {
            TrainingData.Sample a = baseSamples.get(i);
            TrainingData.Sample b = tamSamples.get(i);
            assertEquals(a.day(), b.day());
            assertEquals(a.code(), b.code());
            assertEquals(a.label(), b.label(), 1e-12,
                    "篡改未来价格不得改变 cutoff 内样本标签");
        }
    }
}
