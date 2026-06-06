package com.aistock.datasource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link YahooClient#parseKline(String, KlinePeriod)} 纯解析。无网络。 */
class YahooKlineParseTest {

    private final YahooClient client = new YahooClient();

    // gmtoffset = -14400 (EDT, UTC-4)。三根:第三根 OHLC 全 null 应被跳过。
    private static final String SAMPLE_JSON = """
            {
              "chart": {
                "error": null,
                "result": [
                  {
                    "meta": { "symbol": "AAPL", "gmtoffset": -14400 },
                    "timestamp": [1747661400, 1747747800, 1747834200],
                    "indicators": {
                      "quote": [
                        {
                          "open":   [301.06, 306.12, null],
                          "high":   [305.54, 311.40, null],
                          "low":    [300.40, 305.84, null],
                          "close":  [304.99, 308.82, null],
                          "volume": [42965100, 43670200, null]
                        }
                      ]
                    }
                  }
                ]
              }
            }
            """;

    @Test
    void parsesOhlcvAndSkipsNullBar() {
        List<KlinePoint> pts = client.parseKline(SAMPLE_JSON, KlinePeriod.DAY);
        assertEquals(2, pts.size(), "全 null 的第三根应被跳过");

        KlinePoint first = pts.get(0);
        assertEquals(301.06, first.open(), 1e-9);
        assertEquals(305.54, first.high(), 1e-9);
        assertEquals(300.40, first.low(), 1e-9);
        assertEquals(304.99, first.close(), 1e-9);
        assertEquals(42965100L, first.volume());
    }

    @Test
    void dailyTimeIsDateOnly() {
        List<KlinePoint> pts = client.parseKline(SAMPLE_JSON, KlinePeriod.DAY);
        // 1747661400 @ UTC-4 -> 2025-05-19 09:30 ET,日档只保留日期。
        assertEquals("2025-05-19", pts.get(0).time());
        assertEquals("2025-05-20", pts.get(1).time());
    }

    @Test
    void intradayTimeHasHourMinute() {
        List<KlinePoint> pts = client.parseKline(SAMPLE_JSON, KlinePeriod.MIN_5);
        // 同一时间戳分钟档应带时分。
        assertEquals("2025-05-19 09:30", pts.get(0).time());
        assertEquals("2025-05-20 09:30", pts.get(1).time());
    }

    @Test
    void emptyOrMalformedYieldsEmptyList() {
        assertTrue(client.parseKline("{\"chart\":{\"result\":[]}}", KlinePeriod.DAY).isEmpty());
        assertTrue(client.parseKline("not json", KlinePeriod.DAY).isEmpty());
        assertTrue(client.parseKline("{}", KlinePeriod.DAY).isEmpty());
    }
}
