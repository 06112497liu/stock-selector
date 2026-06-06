package com.aistock.datasource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EastMoneyClient#parse(String)} and
 * {@link EastMoneyClient#toSecid(String)}. No network access.
 */
class EastMoneyParseTest {

    private final EastMoneyClient client = new EastMoneyClient();

    /**
     * Realistic East Money K-line payload shaped like a real 600519 (贵州茅台)
     * response. Each kline string is: 日期,开盘,收盘,最高,最低,成交量,成交额
     * — note the order is open/CLOSE/HIGH/LOW, not standard OHLC.
     */
    private static final String SAMPLE_JSON = """
            {
              "rc": 0,
              "rt": 17,
              "data": {
                "code": "600519",
                "market": 1,
                "name": "贵州茅台",
                "klt": 101,
                "fqt": 1,
                "klines": [
                  "2024-01-02,1685.01,1672.00,1695.88,1661.11,33967,5.69E9",
                  "2024-01-03,1668.00,1660.50,1675.00,1652.30,28154,4.70E9"
                ]
              }
            }
            """;

    @Test
    void parsesBarsWithCorrectOhlcMapping() throws IOException {
        List<Bar> bars = client.parse(SAMPLE_JSON);

        assertEquals(2, bars.size());

        // Row: open=1685.01, close=1672.00, high=1695.88, low=1661.11
        Bar first = bars.get(0);
        assertEquals(LocalDate.of(2024, 1, 2), first.date());
        assertEquals(1685.01, first.open(), 1e-9);
        assertEquals(1695.88, first.high(), 1e-9);
        assertEquals(1661.11, first.low(), 1e-9);
        assertEquals(1672.00, first.close(), 1e-9);
        assertEquals(33967L, first.volume());

        Bar second = bars.get(1);
        assertEquals(LocalDate.of(2024, 1, 3), second.date());
        assertEquals(1668.00, second.open(), 1e-9);
        assertEquals(1675.00, second.high(), 1e-9);
        assertEquals(1652.30, second.low(), 1e-9);
        assertEquals(1660.50, second.close(), 1e-9);
        assertEquals(28154L, second.volume());
    }

    @Test
    void barsAreOrderedOldestFirst() throws IOException {
        List<Bar> bars = client.parse(SAMPLE_JSON);
        assertTrue(bars.get(0).date().isBefore(bars.get(1).date()));
    }

    @Test
    void emptyKlinesYieldsEmptyList() throws IOException {
        String json = """
                {"rc":0,"data":{"code":"600519","name":"贵州茅台","klines":[]}}
                """;
        assertTrue(client.parse(json).isEmpty());
    }

    @Test
    void nullDataYieldsEmptyList() throws IOException {
        // East Money returns data:null for an unknown/delisted secid.
        String json = "{\"rc\":0,\"rt\":17,\"data\":null}";
        assertTrue(client.parse(json).isEmpty());
    }

    @Test
    void shanghaiCodeMapsToOnePrefix() {
        assertEquals("1.600519", EastMoneyClient.toSecid("600519"));
    }

    @Test
    void shenzhenMainBoardCodeMapsToZeroPrefix() {
        assertEquals("0.000001", EastMoneyClient.toSecid("000001"));
    }

    @Test
    void chiNextCodeMapsToZeroPrefix() {
        assertEquals("0.300750", EastMoneyClient.toSecid("300750"));
    }

    @Test
    void unknownMarketThrows() {
        assertThrows(IllegalArgumentException.class, () -> EastMoneyClient.toSecid("900001"));
    }

    // ---- 市值解析(f116 总市值;本机代理挡触网,这里只测纯解析)----

    @Test
    void parseMarketCapExtractsF116() {
        String json = "{\"rc\":0,\"data\":{\"f57\":\"600519\",\"f58\":\"贵州茅台\",\"f116\":2200000000000}}";
        assertEquals(2_200_000_000_000.0, client.parseMarketCap(json).getAsDouble(), 1e-3);
    }

    @Test
    void parseMarketCapNullDataIsEmpty() {
        assertTrue(client.parseMarketCap("{\"rc\":0,\"data\":null}").isEmpty());
    }

    @Test
    void parseMarketCapMissingFieldIsEmpty() {
        String json = "{\"rc\":0,\"data\":{\"f57\":\"600519\",\"f58\":\"贵州茅台\"}}";
        assertTrue(client.parseMarketCap(json).isEmpty());
    }

    @Test
    void parseMarketCapMalformedIsEmpty() {
        assertTrue(client.parseMarketCap("not json").isEmpty());
    }
}
