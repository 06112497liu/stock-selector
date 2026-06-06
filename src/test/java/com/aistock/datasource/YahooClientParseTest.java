package com.aistock.datasource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link YahooClient#parse(String)}. No network access.
 */
class YahooClientParseTest {

    private final YahooClient client = new YahooClient();

    /**
     * Realistic Yahoo chart payload shaped exactly like a real AAPL response.
     * gmtoffset = -14400 (EDT, UTC-4). The two timestamps are 09:30 ET market opens
     * for 2025-05-19 and 2025-05-20.
     * Index 2 carries all-null OHLC to verify gap bars are skipped.
     */
    private static final String SAMPLE_JSON = """
            {
              "chart": {
                "error": null,
                "result": [
                  {
                    "meta": {
                      "currency": "USD",
                      "symbol": "AAPL",
                      "gmtoffset": -14400,
                      "timezone": "EDT"
                    },
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
    void parsesAllValidBars() throws IOException {
        List<Bar> bars = client.parse(SAMPLE_JSON);

        // The third bar is all-null and must be skipped.
        assertEquals(2, bars.size());

        Bar first = bars.get(0);
        assertEquals(LocalDate.of(2025, 5, 19), first.date());
        assertEquals(301.06, first.open(), 1e-9);
        assertEquals(305.54, first.high(), 1e-9);
        assertEquals(300.40, first.low(), 1e-9);
        assertEquals(304.99, first.close(), 1e-9);
        assertEquals(42965100L, first.volume());

        Bar second = bars.get(1);
        assertEquals(LocalDate.of(2025, 5, 20), second.date());
        assertEquals(306.12, second.open(), 1e-9);
        assertEquals(311.40, second.high(), 1e-9);
        assertEquals(305.84, second.low(), 1e-9);
        assertEquals(308.82, second.close(), 1e-9);
        assertEquals(43670200L, second.volume());
    }

    @Test
    void barsAreOrderedOldestFirst() throws IOException {
        List<Bar> bars = client.parse(SAMPLE_JSON);
        assertTrue(bars.get(0).date().isBefore(bars.get(1).date()));
    }

    @Test
    void emptyResultYieldsEmptyList() throws IOException {
        String json = "{\"chart\":{\"error\":null,\"result\":[]}}";
        assertTrue(client.parse(json).isEmpty());
    }

    @Test
    void apiErrorThrows() {
        String json = """
                {"chart":{"error":{"code":"Not Found","description":"No data found, symbol may be delisted"},"result":null}}
                """;
        IOException ex = assertThrows(IOException.class, () -> client.parse(json));
        assertTrue(ex.getMessage().contains("error"));
    }

    // ---- 市值解析(v7 quote;crumb HTTP 流程靠重建容器实测,这里只测纯解析)----

    @Test
    void parseMarketCapExtractsValue() {
        String json = """
                {"quoteResponse":{"error":null,"result":[
                  {"symbol":"AAPL","longName":"Apple Inc.","marketCap":4571145961472}
                ]}}
                """;
        assertEquals(4571145961472.0, client.parseMarketCap(json).getAsDouble(), 1e-3);
    }

    @Test
    void parseMarketCapEmptyResultIsEmpty() {
        assertTrue(client.parseMarketCap("{\"quoteResponse\":{\"result\":[]}}").isEmpty());
    }

    @Test
    void parseMarketCapMissingFieldIsEmpty() {
        String json = "{\"quoteResponse\":{\"result\":[{\"symbol\":\"AAPL\"}]}}";
        assertTrue(client.parseMarketCap(json).isEmpty());
    }

    @Test
    void parseMarketCapMalformedIsEmpty() {
        assertTrue(client.parseMarketCap("not json").isEmpty());
    }
}
