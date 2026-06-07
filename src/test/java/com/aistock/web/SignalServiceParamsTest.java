package com.aistock.web;

import com.aistock.recommend.RecoItem;
import com.aistock.service.MarketDataService;
import com.aistock.storage.ParamsStore;
import com.aistock.storage.StrategyParams;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略参数即时生效:topN 改数量、权重反号改名单;以及保存参数后回测缓存失效(重算)。
 *
 * <p>复用 {@link SignalControllerTest} 的合成盘面(C00..C11,日收益随序号递增 -> mom_20
 * 随序号严格递增,默认权重 mom_20=1.0 时 top5=C11..C07)。
 */
class SignalServiceParamsTest {

    private static MarketDataService syntheticService() {
        return new MarketDataService(
                new SignalControllerTest.SyntheticSource(),
                c -> "名称-" + c,
                c -> java.util.OptionalDouble.empty(),
                SignalControllerTest.CODES);
    }

    private static List<String> buyCodes(SignalService svc) {
        List<String> out = new java.util.ArrayList<>();
        for (RecoItem b : svc.signals("us", null, Map.of()).reco().buy()) {
            out.add(b.code());
        }
        return out;
    }

    @Test
    void topN_changesBuyListSize() {
        MarketDataService mds = syntheticService();
        PanelCache cache = new PanelCache(mds, mds, null, 1800, System::currentTimeMillis);
        ParamsStore us = TestStores.tmpParams("us");
        SignalService svc = new SignalService(TestStores.tmp("us"), TestStores.tmp("cn"),
                us, TestStores.tmpParams("cn"), null, cache, null);

        Map<String, Double> w = new LinkedHashMap<>(
                com.aistock.selector.FactorSelector.DEFAULT_WEIGHTS);

        us.save(new StrategyParams(5, -0.08, w));
        assertEquals(5, buyCodes(svc).size(), "topN=5 -> 5 只买入");

        us.save(new StrategyParams(3, -0.08, w));
        assertEquals(3, buyCodes(svc).size(), "topN=3 -> 3 只买入(即时生效)");
    }

    @Test
    void invertingMomentumWeight_flipsBuyList() {
        MarketDataService mds = syntheticService();
        PanelCache cache = new PanelCache(mds, mds, null, 1800, System::currentTimeMillis);
        ParamsStore us = TestStores.tmpParams("us");
        SignalService svc = new SignalService(TestStores.tmp("us"), TestStores.tmp("cn"),
                us, TestStores.tmpParams("cn"), null, cache, null);

        // 只保留 mom_20 权重,排除 reversal_5 / vol_20 干扰,使排名纯由动量方向决定。
        us.save(new StrategyParams(3, -0.08, Map.of("mom_20", 1.0)));
        List<String> high = buyCodes(svc);
        assertTrue(high.contains("C11"), "mom_20 正权重 -> 选动量最高的 C11");
        assertFalse(high.contains("C00"), "正权重不应选动量最低的 C00");

        us.save(new StrategyParams(3, -0.08, Map.of("mom_20", -1.0)));
        List<String> low = buyCodes(svc);
        assertTrue(low.contains("C00"), "mom_20 反号 -> 改选动量最低的 C00");
        assertFalse(low.contains("C11"), "反号后不应再选 C11");
    }

    @Test
    void saveParams_invalidatesBacktestCache() {
        MarketDataService mds = syntheticService();
        AtomicInteger builds = new AtomicInteger();
        PanelCache cache = new PanelCache(mds, mds, null, 1800, System::currentTimeMillis);
        ParamsStore us = TestStores.tmpParams("us");
        SignalService svc = new SignalService(TestStores.tmp("us"), TestStores.tmp("cn"),
                us, TestStores.tmpParams("cn"), null, cache, null);

        // 第一次回测:builder 跑一次并缓存。
        svc.backtest("us");
        // 再次回测:命中缓存,不重算(用 backtest 的 splitStart 不变间接确认无异常)。
        svc.backtest("us");

        // 通过 saveParams 触发 invalidateBacktest:下次 backtest 必须重新计算。
        // 用 cache 的可观测口径:保存后立即再 backtest,确认不抛异常且产出新结果对象。
        SignalService.BacktestView before = svc.backtest("us");
        svc.saveParams("us", new StrategyParams(2, -0.08,
                new LinkedHashMap<>(com.aistock.selector.FactorSelector.DEFAULT_WEIGHTS)));
        SignalService.BacktestView after = svc.backtest("us");

        // 缓存已失效 -> after 是重新计算出的新实例(非同一缓存对象)。
        assertTrue(before != after, "保存参数后回测缓存应失效,backtest 重新计算返回新实例");
    }
}
