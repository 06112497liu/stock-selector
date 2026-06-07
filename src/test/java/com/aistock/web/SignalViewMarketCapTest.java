package com.aistock.web;

import com.aistock.recommend.Recommendation;
import com.aistock.web.SignalService.SignalView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SignalView#marketCapDisplay(String)} 纯格式化单测(不触网):
 * 覆盖 万亿 / 亿 / 小值 / 空→N/A,以及美股 {@code $} 与 A 股 {@code ¥} 币种。
 */
class SignalViewMarketCapTest {

    private static SignalView view(String market, Map<String, OptionalDouble> caps) {
        return new SignalView(market, market, Map.of(), caps, null, null, false,
                new Recommendation(List.of(), List.of(), List.of()), "");
    }

    @Test
    void usTrillionUsesDollarAndWanyi() {
        // 美股 Apple 量级:4571145961472 → $4.57万亿
        SignalView v = view("us", Map.of("AAPL", OptionalDouble.of(4571145961472.0)));
        assertEquals("$4.57万亿", v.marketCapDisplay("AAPL"));
    }

    @Test
    void cnTrillionUsesYuanAndWanyi() {
        // A股 2200000000000 → ¥2.20万亿
        SignalView v = view("cn", Map.of("600519", OptionalDouble.of(2_200_000_000_000.0)));
        assertEquals("¥2.20万亿", v.marketCapDisplay("600519"));
    }

    @Test
    void cnYiUsesYuanAndYi() {
        // 1.5e9 → ¥15.00亿
        SignalView v = view("cn", Map.of("000001", OptionalDouble.of(1.5e9)));
        assertEquals("¥15.00亿", v.marketCapDisplay("000001"));
    }

    @Test
    void smallValueKeepsZeroDecimals() {
        // < 1 亿:原值保留 0 位,带币种前缀。
        SignalView v = view("us", Map.of("TINY", OptionalDouble.of(12345678.0)));
        assertEquals("$12345678", v.marketCapDisplay("TINY"));
    }

    @Test
    void emptyOrMissingIsNa() {
        SignalView v = view("us", Map.of("X", OptionalDouble.empty()));
        assertEquals("N/A", v.marketCapDisplay("X"));   // 显式 empty
        assertEquals("N/A", v.marketCapDisplay("Y"));   // 不在 map 里
    }
}
