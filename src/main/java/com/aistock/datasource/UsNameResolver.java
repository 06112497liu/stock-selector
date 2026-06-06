package com.aistock.datasource;

import java.util.function.Function;

/**
 * 美股名称解析:实现降级链 <b>内置中文译名 → Yahoo 英文 longName → code</b>。
 *
 * <p>这是「把中文译名包在英文名解析外面一层」的纯函数封装,抽成独立小类便于单测降级链
 * (无需起 Spring 容器、不触网):构造时注入英文名解析函数(生产环境即
 * {@code yahooClient::fetchName},它已自带「英文名失败降级 code」语义)。
 *
 * <ul>
 *   <li>{@link UsChineseNames} 命中 → 返回中文译名;</li>
 *   <li>未命中 → 走注入的英文名解析(Yahoo longName,失败由其自身降级 code);</li>
 *   <li>英文解析意外返回 null → 兜底返回 code,绝不返回 null。</li>
 * </ul>
 *
 * <p>是纯函数(同一 code 结果稳定),故 {@code MarketDataService} 的 nameCache 缓存
 * 语义不变。A 股名称逻辑不经过此类(它走东财中文名)。
 */
public final class UsNameResolver implements Function<String, String> {

    /** 英文名解析(译名表未命中时的下一级,通常是 {@code yahooClient::fetchName})。 */
    private final Function<String, String> englishNameFn;

    /**
     * @param englishNameFn 英文名解析函数(约定:失败时降级返回 code 本身)
     */
    public UsNameResolver(Function<String, String> englishNameFn) {
        this.englishNameFn = englishNameFn;
    }

    /**
     * 解析美股 {@code code} 的展示名(降级链:中文译名 → 英文 longName → code)。
     *
     * @param code 美股代码
     * @return 优先中文译名;否则英文名;再否则 code(绝不返回 null)
     */
    @Override
    public String apply(String code) {
        return UsChineseNames.of(code).orElseGet(() -> {
            String english = englishNameFn.apply(code);
            return english != null ? english : code;
        });
    }
}
