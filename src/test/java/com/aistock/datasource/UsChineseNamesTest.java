package com.aistock.datasource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UsChineseNames}:命中、大小写不敏感、未命中降级,以及当前篮子
 * 20 只全部有内置中文译名(遍历断言)。无网络、无 Spring。
 */
class UsChineseNamesTest {

    /** 当前 application.yml 美股篮子(us)20 只——译名表必须全覆盖。 */
    private static final List<String> BASKET = List.of(
            "AAPL", "MSFT", "NVDA", "GOOGL", "AMZN", "META", "TSLA", "AVGO", "JPM", "V",
            "WMT", "MA", "UNH", "XOM", "JNJ", "PG", "HD", "COST", "ORCL", "NFLX");

    @Test
    void hitsReturnChineseName() {
        assertEquals(Optional.of("苹果"), UsChineseNames.of("AAPL"));
        assertEquals(Optional.of("英伟达"), UsChineseNames.of("NVDA"));
    }

    @Test
    void lookupIsCaseInsensitive() {
        assertEquals(Optional.of("苹果"), UsChineseNames.of("aapl"));
        assertEquals(Optional.of("苹果"), UsChineseNames.of("Aapl"));
        assertEquals(Optional.of("微软"), UsChineseNames.of("  msft  "));
    }

    @Test
    void missReturnsEmpty() {
        assertEquals(Optional.empty(), UsChineseNames.of("ZZZZ"));
    }

    @Test
    void nullAndBlankReturnEmpty() {
        assertEquals(Optional.empty(), UsChineseNames.of(null));
        assertEquals(Optional.empty(), UsChineseNames.of(""));
        assertEquals(Optional.empty(), UsChineseNames.of("   "));
    }

    @Test
    void everyBasketCodeHasChineseName() {
        for (String code : BASKET) {
            Optional<String> name = UsChineseNames.of(code);
            assertTrue(name.isPresent(), "篮子内 " + code + " 应有内置中文译名");
            assertTrue(!name.get().isBlank(), "篮子内 " + code + " 的译名不应为空白");
        }
    }
}
