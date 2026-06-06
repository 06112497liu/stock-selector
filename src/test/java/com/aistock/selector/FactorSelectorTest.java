package com.aistock.selector;

import com.aistock.datasource.Bar;
import com.aistock.feature.MarketPanel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactorSelectorTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);
    private static final LocalDate DAY = D0.plusDays(20); // index 20:mom_20/vol_20 首个有值日

    /** close = startClose + slope*i,30 天连续。slope 越大,20 日动量越大。 */
    private static List<Bar> bars(double startClose, double slope) {
        List<Bar> out = new ArrayList<>();
        LocalDate d = D0;
        for (int i = 0; i < 30; i++) {
            double c = startClose + slope * i;
            out.add(new Bar(d, c, c, c, c, 1000L));
            d = d.plusDays(1);
        }
        return out;
    }

    /**
     * 构造一个只有 mom_20 起作用的横截面:
     * 用单因子权重 {mom_20:1.0},score 排序 == 动量大小排序。
     * 四只票动量严格递增:LO < MID1 < MID2 < HI。
     */
    private static MarketPanel monotoneMomentumPanel() {
        Map<String, List<Bar>> in = new LinkedHashMap<>();
        // 同起点、不同斜率 -> 不同动量;斜率越大动量越大
        in.put("LO", bars(100.0, 0.5));
        in.put("MID1", bars(100.0, 1.0));
        in.put("MID2", bars(100.0, 2.0));
        in.put("HI", bars(100.0, 4.0));
        return new MarketPanel(in);
    }

    @Test
    void selectPicksHighestMomentum() {
        MarketPanel panel = monotoneMomentumPanel();
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("mom_20", 1.0);
        FactorSelector sel = new FactorSelector(w);

        // top2 应为动量最高的两只:HI、MID2
        List<String> top2 = sel.select(panel, DAY, 2);
        assertEquals(List.of("HI", "MID2"), top2);

        // 完整排序 top4:HI > MID2 > MID1 > LO
        List<String> top4 = sel.select(panel, DAY, 4);
        assertEquals(List.of("HI", "MID2", "MID1", "LO"), top4);
    }

    @Test
    void negativeWeightFlipsRanking() {
        MarketPanel panel = monotoneMomentumPanel();
        // 负权重:动量越小越好 -> LO 居首
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("mom_20", -1.0);
        FactorSelector sel = new FactorSelector(w);
        assertEquals(List.of("LO", "MID1"), sel.select(panel, DAY, 2));
    }

    @Test
    void tieBrokenByCodeLexicographically() {
        // 两只票动量完全相同 -> mom_20 标准化值并列(rankNormalize 给相同平均名次)。
        // 用相同斜率制造并列,code 选 "zebra" 与 "alpha" 测字典序兜底。
        Map<String, List<Bar>> in = new LinkedHashMap<>();
        in.put("zebra", bars(100.0, 1.0));
        in.put("alpha", bars(100.0, 1.0));
        MarketPanel panel = new MarketPanel(in);

        Map<String, Double> w = new LinkedHashMap<>();
        w.put("mom_20", 1.0);
        FactorSelector sel = new FactorSelector(w);

        // 两只 score 并列(各为 0.0,k<=1? 不,k=2 且值相等 -> 平均名次 -> 都 0.0)
        Map<String, Double> scores = sel.scores(panel, DAY);
        assertEquals(scores.get("zebra"), scores.get("alpha"));
        // 并列时 code 字典序:alpha 在前
        assertEquals(List.of("alpha", "zebra"), sel.select(panel, DAY, 2));
        assertEquals(List.of("alpha"), sel.select(panel, DAY, 1));
    }

    @Test
    void topNGreaterThanValidCountReturnsAll() {
        MarketPanel panel = monotoneMomentumPanel(); // 4 只有效票
        FactorSelector sel = new FactorSelector(FactorSelector.DEFAULT_WEIGHTS);
        List<String> all = sel.select(panel, DAY, 100);
        assertEquals(4, all.size());
        assertTrue(all.containsAll(List.of("LO", "MID1", "MID2", "HI")));
    }

    @Test
    void topNZeroReturnsEmpty() {
        MarketPanel panel = monotoneMomentumPanel();
        FactorSelector sel = new FactorSelector(FactorSelector.DEFAULT_WEIGHTS);
        assertTrue(sel.select(panel, DAY, 0).isEmpty());
    }
}
