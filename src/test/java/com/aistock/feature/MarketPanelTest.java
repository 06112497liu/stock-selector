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

class MarketPanelTest {

    /** 构造 n 根连续工作日 bar,close = start + slope*i,保证递增/递减可控。 */
    private static List<Bar> bars(LocalDate start, int n, double startClose, double slope) {
        List<Bar> out = new ArrayList<>();
        LocalDate d = start;
        for (int i = 0; i < n; i++) {
            double c = startClose + slope * i;
            out.add(new Bar(d, c, c, c, c, 1000L));
            d = d.plusDays(1);
        }
        return out;
    }

    @Test
    void tradingDaysIsSortedUnion() {
        // A 从 2024-01-01 起 30 天;B 从 2024-01-05 起 30 天(部分重叠、部分独有)
        LocalDate a0 = LocalDate.of(2024, 1, 1);
        LocalDate b0 = LocalDate.of(2024, 1, 5);
        Map<String, List<Bar>> in = new LinkedHashMap<>();
        in.put("A", bars(a0, 30, 100.0, 1.0));
        in.put("B", bars(b0, 30, 50.0, 2.0));
        MarketPanel panel = new MarketPanel(in);

        List<LocalDate> days = panel.tradingDays();
        // 并集:1/1 .. 2/7(A 到 1/30,B 到 2/3),共 a0..b0+29
        LocalDate last = b0.plusDays(29);
        assertEquals(a0, days.get(0));
        assertEquals(last, days.get(days.size() - 1));
        // 严格升序、无重复
        for (int i = 1; i < days.size(); i++) {
            assertTrue(days.get(i).isAfter(days.get(i - 1)));
        }
    }

    @Test
    void factorOnContainsOnlyValidCodesWithinRange() {
        // 三只票各 30 天、完全对齐;mom/vol 需要 20+,reversal 需要 5+
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        Map<String, List<Bar>> in = new LinkedHashMap<>();
        in.put("A", bars(d0, 30, 100.0, 1.0));
        in.put("B", bars(d0, 30, 100.0, 2.0));
        in.put("C", bars(d0, 30, 100.0, 0.5));
        MarketPanel panel = new MarketPanel(in);

        // 第 21 根 bar(index 20)起 momentum(20)/volatility(20) 才有值 -> 该天进横截面
        LocalDate validDay = d0.plusDays(20);
        Map<String, Double> mom = panel.factorOn(validDay, "mom_20");
        assertEquals(3, mom.size());
        for (double v : mom.values()) {
            assertTrue(v >= -0.5 && v <= 0.5, "rankNormalize 取值需落在 [-0.5,0.5],实际 " + v);
        }

        // 早期某天(index 5)momentum(20) 还是 NaN,所有票都不满足 -> 横截面为空
        LocalDate earlyDay = d0.plusDays(5);
        assertTrue(panel.factorOn(earlyDay, "mom_20").isEmpty());
    }

    @Test
    void shortHistoryCodeExcludedFromCrossSection() {
        // A 有 30 天(够);D 只有 10 天,无论如何都凑不齐 mom_20/vol_20
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        Map<String, List<Bar>> in = new LinkedHashMap<>();
        in.put("A", bars(d0, 30, 100.0, 1.0));
        in.put("B", bars(d0, 30, 100.0, 2.0));
        in.put("D", bars(d0, 10, 100.0, 1.5));
        MarketPanel panel = new MarketPanel(in);

        LocalDate validDay = d0.plusDays(20);
        Map<String, Double> mom = panel.factorOn(validDay, "mom_20");
        // 只有 A、B 进横截面,历史不足的 D 被剔除
        assertTrue(mom.containsKey("A"));
        assertTrue(mom.containsKey("B"));
        assertFalse(mom.containsKey("D"));
        assertEquals(2, mom.size());
    }

    @Test
    void closeOnReturnsValueOrNaN() {
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        Map<String, List<Bar>> in = new LinkedHashMap<>();
        in.put("A", bars(d0, 30, 100.0, 1.0));
        MarketPanel panel = new MarketPanel(in);

        // index 3 -> close = 100 + 1*3 = 103
        assertEquals(103.0, panel.closeOn("A", d0.plusDays(3)));
        // A 该天没有 bar -> NaN
        assertTrue(Double.isNaN(panel.closeOn("A", d0.plusDays(100))));
        // 未知 code -> NaN
        assertTrue(Double.isNaN(panel.closeOn("ZZZ", d0)));
    }
}
