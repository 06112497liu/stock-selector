package com.aistock.storage;

import com.aistock.storage.PortfolioView.Row;
import com.aistock.storage.PortfolioView.Summary;
import com.aistock.storage.Store.Position;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PortfolioView} 工具单测:浮盈计算、缺现价不崩、账户摘要数值。 */
class PortfolioViewTest {

    @Test
    void positionView_computesPnl() {
        Map<String, Position> pos = new LinkedHashMap<>();
        pos.put("AAPL", new Position(10, 100));
        Map<String, String> names = Map.of("AAPL", "苹果");
        Map<String, Double> px = Map.of("AAPL", 120.0);

        List<Row> rows = PortfolioView.positionView(pos, names, px);
        assertEquals(1, rows.size());
        Row r = rows.get(0);
        assertEquals("AAPL", r.code());
        assertEquals("苹果", r.name());
        assertTrue(r.hasPrice());
        assertEquals(1200.0, r.marketValue(), 1e-9);   // 10*120
        assertEquals(200.0, r.pnl(), 1e-9);             // 1200 - 1000
        assertEquals(0.20, r.pnlPct(), 1e-9);           // 120/100 - 1
    }

    @Test
    void positionView_missingPrice_doesNotCrash() {
        Map<String, Position> pos = new LinkedHashMap<>();
        pos.put("MSFT", new Position(5, 400));
        // 名称缺失 -> 回退 code;现价缺失 -> 市值退成本、浮盈 NaN
        List<Row> rows = PortfolioView.positionView(pos, Map.of(), Map.of());
        Row r = rows.get(0);
        assertEquals("MSFT", r.name(), "缺名称回退 code");
        assertFalse(r.hasPrice());
        assertTrue(Double.isNaN(r.price()));
        assertEquals(2000.0, r.marketValue(), 1e-9, "缺现价市值退回成本计 5*400");
        assertTrue(Double.isNaN(r.pnl()), "缺现价浮盈为 NaN");
        assertTrue(Double.isNaN(r.pnlPct()));
    }

    @Test
    void positionView_nanPrice_treatedAsMissing() {
        Map<String, Position> pos = new LinkedHashMap<>();
        pos.put("X", new Position(2, 50));
        Map<String, Double> px = new LinkedHashMap<>();
        px.put("X", Double.NaN);
        Row r = PortfolioView.positionView(pos, Map.of(), px).get(0);
        assertFalse(r.hasPrice());
        assertEquals(100.0, r.marketValue(), 1e-9);
    }

    @Test
    void accountSummary_numbers() {
        Map<String, Position> pos = new LinkedHashMap<>();
        pos.put("AAPL", new Position(10, 100)); // 现价 120 -> 1200
        pos.put("MSFT", new Position(5, 400));  // 缺价 -> 退成本 2000
        Map<String, Double> px = Map.of("AAPL", 120.0);

        Summary s = PortfolioView.accountSummary(1000.0, pos, px);
        assertEquals(1000.0, s.cash(), 1e-9);
        assertEquals(3200.0, s.positionsValue(), 1e-9); // 1200 + 2000
        assertEquals(4200.0, s.netValue(), 1e-9);       // cash + 市值
    }

    @Test
    void emptyPositions_summaryIsCashOnly() {
        Summary s = PortfolioView.accountSummary(777.0, Map.of(), Map.of());
        assertEquals(0.0, s.positionsValue(), 1e-9);
        assertEquals(777.0, s.netValue(), 1e-9);
        assertTrue(PortfolioView.positionView(Map.of(), Map.of(), Map.of()).isEmpty());
    }
}
