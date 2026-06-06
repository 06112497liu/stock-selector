package com.aistock.datasource;

import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link UsNameResolver} 的降级链 <b>中文译名 → 英文 longName → code</b>。
 *
 * <p>无网络:用注入的假英文名解析函数(模拟 {@code yahooClient::fetchName})断言三级
 * 降级。命中译名表时假函数<b>不应被调用</b>(短路),也一并断言。
 */
class UsNameResolverTest {

    @Test
    void hitChineseNameShortCircuitsEnglishFn() {
        // 假英文函数:被调用就抛,以证明命中译名时根本不会触达英文名解析。
        Function<String, String> englishFn = code -> {
            throw new AssertionError("译名命中时不应调用英文名解析: " + code);
        };
        UsNameResolver resolver = new UsNameResolver(englishFn);
        assertEquals("苹果", resolver.apply("AAPL"));
        assertEquals("苹果", resolver.apply("aapl")); // 大小写不敏感
    }

    @Test
    void missFallsBackToEnglishName() {
        // 译名表外的代码 → 走英文名(模拟 Yahoo longName)。
        Function<String, String> englishFn = code -> "Some Foreign Corp.";
        UsNameResolver resolver = new UsNameResolver(englishFn);
        assertEquals("Some Foreign Corp.", resolver.apply("ZZZZ"));
    }

    @Test
    void missAndEnglishUnavailableFallsBackToCode() {
        // 译名表外 + 英文函数按约定降级返回 code 本身 → 最终是 code。
        Function<String, String> englishFn = code -> code;
        UsNameResolver resolver = new UsNameResolver(englishFn);
        assertEquals("ZZZZ", resolver.apply("ZZZZ"));
    }

    @Test
    void nullEnglishResultIsDefendedWithCode() {
        // 英文函数意外返回 null,兜底返回 code,绝不返回 null。
        Function<String, String> englishFn = code -> null;
        UsNameResolver resolver = new UsNameResolver(englishFn);
        assertEquals("ZZZZ", resolver.apply("ZZZZ"));
    }
}
