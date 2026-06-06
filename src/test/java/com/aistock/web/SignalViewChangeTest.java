package com.aistock.web;

import com.aistock.datasource.Bar;
import com.aistock.feature.MarketPanel;
import com.aistock.recommend.Recommendation;
import com.aistock.web.SignalService.SignalView;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SignalView#changeDisplay(String)} / {@link SignalView#changeClass(String)}
 * 纯口径单测(不触网):用几何价合成面板(close[i]=start*(1+r)^i,故每日涨跌幅恒为 r,
 * 可精确断言),覆盖正涨幅(.gain 红)、负涨幅(.loss 绿)、持平(0.00% 不着色)、
 * 数据不足 / 停牌 → "N/A" 无 class。
 */
class SignalViewChangeTest {

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);

    /** 几何复利价:close[i]=start*(1+r)^i,n 天,故每日涨跌幅恒为 r。 */
    private static List<Bar> geom(double start, double r, int n) {
        List<Bar> out = new ArrayList<>();
        LocalDate d = D0;
        double c = start;
        for (int i = 0; i < n; i++) {
            out.add(new Bar(d, c, c, c, c, 1000L));
            c *= (1.0 + r);
            d = d.plusDays(1);
        }
        return out;
    }

    private static SignalView viewOf(Map<String, List<Bar>> barsByCode) {
        MarketPanel panel = new MarketPanel(barsByCode);
        return new SignalView("us", Map.of(), Map.of(), panel, panel.tradingDays().isEmpty()
                ? null : panel.tradingDays().get(panel.tradingDays().size() - 1),
                false, new Recommendation(List.of(), List.of(), List.of()), "");
    }

    @Test
    void positiveReturnShowsSignedPercentAndGainClass() {
        // 日收益 +2% → "+2.00%",涨=红=gain。
        SignalView v = viewOf(Map.of("UP", geom(100.0, 0.02, 25)));
        assertEquals("+2.00%", v.changeDisplay("UP"));
        assertEquals("gain", v.changeClass("UP"));
    }

    @Test
    void negativeReturnShowsSignedPercentAndLossClass() {
        // 日收益 -1.05% → "-1.05%",跌=绿=loss。
        SignalView v = viewOf(Map.of("DOWN", geom(100.0, -0.0105, 25)));
        assertEquals("-1.05%", v.changeDisplay("DOWN"));
        assertEquals("loss", v.changeClass("DOWN"));
    }

    @Test
    void flatReturnShowsZeroWithoutClass() {
        // 日收益 0 → "0.00%",持平不着色(注意带符号格式下 +0.00 会规整为 +0.00%,
        // 这里日收益恰为 0,String.format %+.2f 输出 "+0.00")。
        SignalView v = viewOf(Map.of("FLAT", geom(100.0, 0.0, 25)));
        assertEquals("+0.00%", v.changeDisplay("FLAT"));
        assertEquals("", v.changeClass("FLAT"));
    }

    @Test
    void insufficientHistoryIsNaWithoutClass() {
        // 全市场并集交易日只有 1 天 → 无前一交易日 → N/A,不着色。
        SignalView v = viewOf(Map.of("ONE", geom(100.0, 0.02, 1)));
        assertEquals("N/A", v.changeDisplay("ONE"));
        assertEquals("", v.changeClass("ONE"));
    }

    @Test
    void suspendedOnLatestDayIsNa() {
        // ALIVE 有完整两天;HALT 仅在更早的日子有 bar,在最新/前一交易日均无 close
        // (停牌) → closeOn 为 NaN → N/A 不着色,绝不报错。
        List<Bar> alive = geom(100.0, 0.02, 25);
        // HALT 只覆盖前 3 个交易日(D0..D0+2),并集交易日末两位它都缺。
        List<Bar> halt = geom(50.0, 0.01, 3);
        SignalView v = viewOf(Map.of("ALIVE", alive, "HALT", halt));
        assertEquals("+2.00%", v.changeDisplay("ALIVE"));
        assertEquals("N/A", v.changeDisplay("HALT"));
        assertEquals("", v.changeClass("HALT"));
    }

    @Test
    void nullPanelIsNa() {
        // empty 视图 panel=null → N/A,不报错。
        SignalView v = SignalView.empty("us", Map.of());
        assertEquals("N/A", v.changeDisplay("ANY"));
        assertEquals("", v.changeClass("ANY"));
    }
}
