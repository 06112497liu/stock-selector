package com.aistock.datasource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link EastMoneyClient#parseKline(String)} 纯解析。无网络。 */
class EastMoneyKlineParseTest {

    private final EastMoneyClient client = new EastMoneyClient();

    // 每条:日期,开,收,高,低,成交量,成交额 —— 注意是 开/收/高/低,不是标准 OHLC。
    private static final String DAILY_JSON = """
            {
              "data": {
                "code": "600519",
                "name": "贵州茅台",
                "klines": [
                  "2024-01-02,1685.01,1672.00,1695.88,1661.11,33967,5.69E9",
                  "2024-01-03,1668.00,1660.50,1675.00,1652.30,28154,4.70E9"
                ]
              }
            }
            """;

    @Test
    void parsesOpenCloseHighLowCorrectly() {
        List<KlinePoint> pts = client.parseKline(DAILY_JSON);
        assertEquals(2, pts.size());

        KlinePoint p = pts.get(0);
        assertEquals("2024-01-02", p.time());
        assertEquals(1685.01, p.open(), 1e-9);   // 第2列=开
        assertEquals(1672.00, p.close(), 1e-9);  // 第3列=收(不可与开搞反)
        assertEquals(1695.88, p.high(), 1e-9);   // 第4列=高
        assertEquals(1661.11, p.low(), 1e-9);    // 第5列=低
        assertEquals(33967L, p.volume());
        // 开 > 收 → 阴线,确认 open/close 未搞反
        assertTrue(p.open() > p.close());
    }

    @Test
    void intradayTimeKeepsHourMinute() {
        String json = """
                {"data":{"klines":["2024-01-02 14:30,1685.01,1672.00,1695.88,1661.11,33967,5.69E9"]}}
                """;
        List<KlinePoint> pts = client.parseKline(json);
        assertEquals(1, pts.size());
        assertEquals("2024-01-02 14:30", pts.get(0).time());
    }

    @Test
    void nullDataOrMalformedYieldsEmptyList() {
        assertTrue(client.parseKline("{\"data\":null}").isEmpty());
        assertTrue(client.parseKline("{\"data\":{\"klines\":[]}}").isEmpty());
        assertTrue(client.parseKline("not json").isEmpty());
    }

    @Test
    void shortRowsAreSkipped() {
        String json = "{\"data\":{\"klines\":[\"2024-01-02,1685.01,1672.00\"]}}";
        assertTrue(client.parseKline(json).isEmpty());
    }
}
