package com.aistock.selector;

import com.aistock.backtest.BacktestEngine;
import com.aistock.backtest.BacktestResult;
import com.aistock.backtest.CostConfig;
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

class MLSelectorTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);
    private static final int N = 80;   // 较长面板,保证 split 前训练样本充足
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

    /** 多只票,斜率各异,制造横截面差异;价格随交易日缓慢变化便于学习。 */
    private static Map<String, double[]> baseCloses() {
        Map<String, double[]> m = new LinkedHashMap<>();
        double[] slopes = {4.0, 3.0, 2.0, 1.0, 0.5, -0.5, -1.0, 2.5};
        String[] codes = {"A", "B", "C", "D", "E", "F", "G", "Z"};
        for (int k = 0; k < codes.length; k++) {
            double[] c = new double[N];
            for (int i = 0; i < N; i++) {
                // 轻微非线性,避免完全共线导致退化
                c[i] = 100.0 + slopes[k] * i + Math.sin(i / 7.0) * (k + 1);
            }
            m.put(codes[k], c);
        }
        return m;
    }

    // ---------- 退化:样本不足时返回空、不抛 ----------

    @Test
    void untrainedReturnsEmpty() {
        MarketPanel panel = panelFrom(baseCloses());
        MLSelector ml = new MLSelector(MarketPanel.FACTORS, H);
        // 未调用 fit
        assertFalse(ml.isTrained());
        assertTrue(ml.predict(panel, panel.tradingDays().get(40)).isEmpty());
        assertTrue(ml.select(panel, panel.tradingDays().get(40), 3).isEmpty());

        // 用极早的 cutoff 训练 -> 样本不足 -> 仍未训练、返回空
        LocalDate earlySplit = panel.tradingDays().get(22);
        int n = ml.fit(panel, earlySplit, H);
        if (!ml.isTrained()) {
            assertTrue(n < MLSelector.MIN_TRAIN_SAMPLES);
            assertTrue(ml.select(panel, earlySplit, 3).isEmpty(),
                    "未训练时 select 必须返回空列表");
        }
    }

    // ---------- fit 只用 trainEnd 前样本(可观测计数) ----------

    @Test
    void fitUsesOnlySamplesWithLabelBeforeTrainEnd() {
        MarketPanel panel = panelFrom(baseCloses());
        List<LocalDate> days = panel.tradingDays();

        MLSelector mlEarly = new MLSelector(MarketPanel.FACTORS, H);
        MLSelector mlLate = new MLSelector(MarketPanel.FACTORS, H);

        int nEarly = mlEarly.fit(panel, days.get(45), H);
        int nLate = mlLate.fit(panel, days.get(70), H);

        // 更晚的 trainEnd 能看到更多「标签区间已闭合」的样本
        assertTrue(nLate > nEarly,
                "trainEnd 越晚,可用(标签已闭合)训练样本越多: early=" + nEarly + " late=" + nLate);
        assertTrue(mlLate.isTrained(), "样本充足应训练成功");
    }

    // ---------- 防前视红线(最重要):篡改 split 之后某日之后的价格,不改更早预测/净值 ----------

    @Test
    void tamperingFuturePriceDoesNotChangeEarlierPredictionsOrNav() {
        List<LocalDate> days = panelFrom(baseCloses()).tradingDays();
        LocalDate split = days.get(50);
        int tamperIdx = 70; // 远在 split 之后

        // 基线:split 前训练一次,从 split 起回测。
        MarketPanel base = panelFrom(baseCloses());
        MLSelector mlBase = new MLSelector(MarketPanel.FACTORS, H);
        mlBase.fit(base, split, H);
        BacktestResult navBase = new BacktestEngine()
                .run(base, mlBase, split, 3, CostConfig.zero());

        // 篡改 split 之后某晚交易日的价格,重新走同样流程(同样只用 split 前训练)。
        Map<String, double[]> tampered = baseCloses();
        tampered.get("A")[tamperIdx] = 99999.0;
        MarketPanel tam = panelFrom(tampered);
        MLSelector mlTam = new MLSelector(MarketPanel.FACTORS, H);
        mlTam.fit(tam, split, H);

        // 1) 训练只用 split 前数据:篡改 split 之后价格不应改变模型在 split 当天的预测。
        Map<String, Double> predBase = mlBase.predict(base, split);
        Map<String, Double> predTam = mlTam.predict(tam, split);
        assertEquals(predBase.keySet(), predTam.keySet(),
                "split 当天有效票集合不应被未来价格改变");
        for (String code : predBase.keySet()) {
            assertEquals(predBase.get(code), predTam.get(code), 1e-9,
                    "篡改 split 之后价格不得改变 split 当天的预测分: " + code);
        }

        // 2) 净值:篡改日之前(<= tamperIdx-1 对应日)的 NAV 必须与基线完全一致。
        BacktestResult navTam = new BacktestEngine()
                .run(tam, mlTam, split, 3, CostConfig.zero());
        LocalDate beforeTamper = days.get(tamperIdx - 1);
        List<LocalDate> dates = navBase.dates();
        // 两次回测决策日序列应一致(因为预测在 <tamperIdx 处都不依赖未来价)
        assertEquals(dates.size(), navTam.dates().size(), "NAV 长度应一致");
        for (int i = 0; i < dates.size(); i++) {
            LocalDate d = dates.get(i);
            assertFalse(Double.isNaN(navBase.nav()[i]), "净值不应 NaN");
            if (!d.isAfter(beforeTamper)) {
                assertEquals(navBase.nav()[i], navTam.nav()[i], 1e-12,
                        "篡改未来价格不得反向影响更早净值, 违规日=" + d);
            }
        }
    }

    // ---------- 平滑:训练后 select 给出确定、可用的名单 ----------

    @Test
    void trainedSelectIsDeterministicAndBounded() {
        MarketPanel panel = panelFrom(baseCloses());
        LocalDate split = panel.tradingDays().get(60);
        MLSelector ml = new MLSelector(MarketPanel.FACTORS, H);
        ml.fit(panel, split, H);
        assertTrue(ml.isTrained());

        List<String> a = ml.select(panel, split, 3);
        List<String> b = ml.select(panel, split, 3);
        assertEquals(a, b, "相同输入必须确定性输出");
        assertTrue(a.size() <= 3, "不超过 topN");
        assertFalse(a.isEmpty(), "训练成功且当天有有效票时应非空");
    }
}
