package com.aistock.recommend;

import com.aistock.datasource.Bar;
import com.aistock.feature.MarketPanel;
import com.aistock.selector.FactorSelector;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RecommendEngine 测试:合成盘面,手算 score,覆盖买/持/卖三张名单及边界口径。
 *
 * <p>盘面口径:单因子权重 {mom_20:1.0},四只同起点不同斜率的票,
 * 横截面动量严格递增 LO < MID1 < MID2 < HI,rankNormalize 后
 * score 分别为 -0.5、-1/6、+1/6、+0.5(k=4)。
 * 于是 HI/MID2 的 score 为正,LO/MID1 的 score 为负。
 */
class RecommendEngineTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);
    private static final LocalDate DAY = D0.plusDays(20); // mom_20 首个有值日

    /** close = startClose + slope*i,30 天连续。 */
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

    private static MarketPanel monotonePanel() {
        Map<String, List<Bar>> in = new LinkedHashMap<>();
        in.put("LO", bars(100.0, 0.5));   // score = -0.5  (负)
        in.put("MID1", bars(100.0, 1.0)); // score = -1/6  (负)
        in.put("MID2", bars(100.0, 2.0)); // score = +1/6  (正)
        in.put("HI", bars(100.0, 4.0));   // score = +0.5  (正)
        return new MarketPanel(in);
    }

    private static FactorSelector momentumSelector() {
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("mom_20", 1.0);
        return new FactorSelector(w);
    }

    private static RecoItem find(List<RecoItem> list, String code) {
        return list.stream().filter(it -> it.code().equals(code)).findFirst().orElse(null);
    }

    // ---- 买入名单:含已持有票,held=true,不被剔除,名次正确 ----
    @Test
    void buyKeepsHeldStocksAndMarksThem() {
        MarketPanel panel = monotonePanel();
        FactorSelector sel = momentumSelector();
        RecommendEngine eng = new RecommendEngine();

        // HI 已持有,且仍在 top2;不应被剔除,应标注「已持有(可加仓)」。
        Recommendation r = eng.recommend(panel, DAY, sel, 2,
                Set.of("HI"), Map.of(), -0.08);

        List<String> buyCodes = r.buy().stream().map(RecoItem::code).toList();
        assertEquals(List.of("HI", "MID2"), buyCodes, "buy 按选股器名次");

        RecoItem hi = find(r.buy(), "HI");
        assertTrue(hi.held(), "已持有票 held=true");
        assertEquals("已持有(可加仓)", hi.reason());

        RecoItem mid2 = find(r.buy(), "MID2");
        assertFalse(mid2.held());
        assertEquals("Top2 选中", mid2.reason());
    }

    // ---- 卖出:打分转负 ----
    @Test
    void sellWhenScoreTurnsNegative() {
        MarketPanel panel = monotonePanel();
        FactorSelector sel = momentumSelector();
        RecommendEngine eng = new RecommendEngine();

        // LO 持有,score=-0.5<0 -> 打分转负,卖出。入场价不触止损(价格高于入场价)。
        Recommendation r = eng.recommend(panel, DAY, sel, 2,
                Set.of("LO"), Map.of("LO", 90.0), -0.08);

        List<String> sellCodes = r.sell().stream().map(RecoItem::code).toList();
        assertEquals(List.of("LO"), sellCodes);
        assertEquals("打分转负", find(r.sell(), "LO").reason());
        assertTrue(r.hold().isEmpty());
    }

    // ---- 卖出:触发止损(score>=0 但价格跌破入场价止损线) ----
    @Test
    void sellWhenStopLossTriggered() {
        MarketPanel panel = monotonePanel();
        FactorSelector sel = momentumSelector();
        RecommendEngine eng = new RecommendEngine();

        // HI 持有,score=+0.5>=0(不会打分转负)。
        // HI 在 DAY 的收盘价 = 100 + 4*20 = 180;入场价设 200 -> 跌幅 = 180/200-1 = -10% <= -8% 触发止损。
        double hiClose = panel.closeOn("HI", DAY);
        assertEquals(180.0, hiClose, 1e-9);

        Recommendation r = eng.recommend(panel, DAY, sel, 2,
                Set.of("HI"), Map.of("HI", 200.0), -0.08);

        List<String> sellCodes = r.sell().stream().map(RecoItem::code).toList();
        assertEquals(List.of("HI"), sellCodes);
        assertEquals("触发止损 -8.00%", find(r.sell(), "HI").reason());
    }

    // ---- 关键回归:掉出 topN 但 score>0 且未触发止损的持有票,不被卖 ----
    @Test
    void doesNotSellHeldStockThatFellOutOfTopNButScorePositive() {
        MarketPanel panel = monotonePanel();
        FactorSelector sel = momentumSelector();
        RecommendEngine eng = new RecommendEngine();

        // topN=1 -> 只有 HI 入选,MID2 掉出 topN。
        // MID2 持有,score=+1/6>0,入场价 100,DAY 收盘 = 100+2*20 = 140,涨了不触止损。
        // 期望:MID2 不在 sell,而在 hold(继续持有)。
        double mid2Close = panel.closeOn("MID2", DAY);
        assertEquals(140.0, mid2Close, 1e-9);

        Recommendation r = eng.recommend(panel, DAY, sel, 1,
                Set.of("MID2"), Map.of("MID2", 100.0), -0.08);

        assertTrue(r.sell().isEmpty(), "掉出 topN 但 score>0 不止损,绝不卖");
        List<String> holdCodes = r.hold().stream().map(RecoItem::code).toList();
        assertEquals(List.of("MID2"), holdCodes);
        assertEquals("继续持有", find(r.hold(), "MID2").reason());

        // 同时确认 buy 没有把 MID2 剔除 holdings 的逻辑影响:buy 仅含 topN=1 的 HI。
        assertEquals(List.of("HI"), r.buy().stream().map(RecoItem::code).toList());
    }

    // ---- hold = holdings 去掉 sell ----
    @Test
    void holdIsHoldingsMinusSell() {
        MarketPanel panel = monotonePanel();
        FactorSelector sel = momentumSelector();
        RecommendEngine eng = new RecommendEngine();

        // 持有 LO(打分转负->卖)、MID2(score>0->持)、HI(score>0->持)。
        Recommendation r = eng.recommend(panel, DAY, sel, 4,
                Set.of("LO", "MID2", "HI"), Map.of(), -0.08);

        assertEquals(List.of("LO"), r.sell().stream().map(RecoItem::code).toList());
        // hold 按 code 字典序:HI, MID2
        assertEquals(List.of("HI", "MID2"), r.hold().stream().map(RecoItem::code).toList());
        for (RecoItem it : r.hold()) {
            assertEquals("继续持有", it.reason());
            assertTrue(it.held());
        }
    }

    // ---- 无持仓:sell/hold 空,buy 正常 ----
    @Test
    void noHoldingsYieldsEmptySellAndHold() {
        MarketPanel panel = monotonePanel();
        FactorSelector sel = momentumSelector();
        RecommendEngine eng = new RecommendEngine();

        Recommendation r = eng.recommend(panel, DAY, sel, 2,
                Set.of(), Map.of(), -0.08);

        assertTrue(r.sell().isEmpty());
        assertTrue(r.hold().isEmpty());
        assertEquals(List.of("HI", "MID2"), r.buy().stream().map(RecoItem::code).toList());
        // 全是新买,held=false
        for (RecoItem it : r.buy()) {
            assertFalse(it.held());
        }
        assertEquals("Top1 选中", find(r.buy(), "HI").reason());
        assertEquals("Top2 选中", find(r.buy(), "MID2").reason());
    }

    // ---- 数据缺失票:保守不动,进 hold 并标注,绝不强卖 ----
    @Test
    void missingDataStockGoesToHoldNotSell() {
        MarketPanel panel = monotonePanel();
        FactorSelector sel = momentumSelector();
        RecommendEngine eng = new RecommendEngine();

        // GHOST 不在盘面横截面 -> scores 拿不到 -> 数据缺失。
        // 即便给了一个会触发止损的入场价,也绝不强卖,只标注复核。
        Recommendation r = eng.recommend(panel, DAY, sel, 2,
                Set.of("GHOST"), Map.of("GHOST", 1000.0), -0.08);

        assertTrue(r.sell().isEmpty(), "数据缺失绝不强卖");
        List<String> holdCodes = r.hold().stream().map(RecoItem::code).toList();
        assertEquals(List.of("GHOST"), holdCodes);

        RecoItem ghost = find(r.hold(), "GHOST");
        assertEquals("数据缺失,建议人工复核", ghost.reason());
        assertTrue(Double.isNaN(ghost.score()), "数据缺失 score=NaN");
        assertTrue(Double.isNaN(ghost.price()), "GHOST 无 bar,price=NaN");
        assertTrue(ghost.held());
    }

    // ---- 缺入场价时跳过止损,但仍可因打分转负而卖 ----
    @Test
    void noEntryPriceSkipsStopLossButScoreSellStillWorks() {
        MarketPanel panel = monotonePanel();
        FactorSelector sel = momentumSelector();
        RecommendEngine eng = new RecommendEngine();

        // MID1 持有,score=-1/6<0,无入场价 -> 仍因打分转负卖出。
        // HI 持有,score>0,无入场价 -> 止损判断被跳过 -> 继续持有。
        Recommendation r = eng.recommend(panel, DAY, sel, 4,
                Set.of("MID1", "HI"), Map.of(), -0.08);

        assertEquals(List.of("MID1"), r.sell().stream().map(RecoItem::code).toList());
        assertEquals("打分转负", find(r.sell(), "MID1").reason());
        assertEquals(List.of("HI"), r.hold().stream().map(RecoItem::code).toList());
        assertNull(find(r.hold(), "MID1"));
    }
}
