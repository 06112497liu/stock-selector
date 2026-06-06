package com.aistock.backtest;

import com.aistock.datasource.Bar;
import com.aistock.feature.MarketPanel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestComparisonTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);
    private static final int N = 80;
    private static final int H = 5;

    private static MarketPanel panel() {
        Map<String, double[]> closes = new LinkedHashMap<>();
        double[] slopes = {4.0, 3.0, 2.0, 1.0, 0.5, -0.5, -1.0, 2.5};
        String[] codes = {"A", "B", "C", "D", "E", "F", "G", "Z"};
        for (int k = 0; k < codes.length; k++) {
            double[] c = new double[N];
            for (int i = 0; i < N; i++) {
                c[i] = 100.0 + slopes[k] * i + Math.sin(i / 7.0) * (k + 1);
            }
            closes.put(codes[k], c);
        }
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

    @Test
    void comparisonRunsBothCurvesAligned() {
        MarketPanel panel = panel();
        LocalDate split = panel.tradingDays().get(55);

        BacktestComparison.Result r = new BacktestComparison()
                .run(panel, split, 3, CostConfig.zero(), H);

        assertTrue(r.mlTrained(), "样本充足时 ML 应训练成功");
        assertTrue(r.mlTrainSamples() >= MarketPanel.FACTORS.size(),
                "应有训练样本计数");

        BacktestResult f = r.factor();
        BacktestResult m = r.ml();

        // 两条 NAV 长度与日期对齐、无 NaN、从 1.0 起
        assertEquals(f.dates().size(), f.nav().length);
        assertEquals(m.dates().size(), m.nav().length);
        assertEquals(f.nav().length, m.nav().length, "factor 与 ml 净值长度应对齐");
        assertEquals(f.dates(), m.dates(), "factor 与 ml 决策日序列应一致");

        for (double v : f.nav()) {
            assertFalse(Double.isNaN(v));
            assertTrue(v > 0);
        }
        for (double v : m.nav()) {
            assertFalse(Double.isNaN(v));
            assertTrue(v > 0);
        }
        assertEquals(1.0, f.nav()[0], 1e-12);
        assertEquals(1.0, m.nav()[0], 1e-12);
    }
}
