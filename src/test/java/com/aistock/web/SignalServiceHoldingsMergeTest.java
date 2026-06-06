package com.aistock.web;

import com.aistock.recommend.RecoItem;
import com.aistock.service.MarketDataService;
import com.aistock.storage.Store;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归:手输持仓是「补充试算」,不得抹掉账本真实持仓的「已持有」标注。
 *
 * <p>历史 bug:{@code holdings = manual.isEmpty() ? ledger : manual} 让手输非空时
 * 整本替换账本,导致「该买」名单里账本持仓的「已持有」标注在点按钮后消失。
 * 修复为 union(账本 ∪ 手输)。本测试用合成盘面(复用 {@link SignalControllerTest}
 * 的 C00..C11,日收益随序号递增,top5 = C11..C07)直接验证。
 */
class SignalServiceHoldingsMergeTest {

    private static SignalService service(Store usStore) {
        MarketDataService svc = new MarketDataService(
                new SignalControllerTest.SyntheticSource(),
                c -> "名称-" + c,
                c -> java.util.OptionalDouble.empty(),
                SignalControllerTest.CODES);
        PanelCache cache = new PanelCache(svc, svc, 1800, System::currentTimeMillis);
        return new SignalService(usStore, TestStores.tmp("cn"),
                TestStores.tmpParams("us"), TestStores.tmpParams("cn"), null, cache);
    }

    private static boolean buyHeld(SignalService.SignalView view, String code) {
        for (RecoItem b : view.reco().buy()) {
            if (b.code().equals(code)) {
                return b.held();
            }
        }
        return false;
    }

    @Test
    void manualHoldingDoesNotDropLedgerHeldFlag() {
        Store usStore = TestStores.tmp("us");
        usStore.upsertPosition("C11", 10, 100.0); // 账本持有 top 票 C11

        // 手输另一只 top 票 C10(账本里没有)—— 模拟「输入股票+价格点按钮」。
        SignalService.SignalView view = service(usStore).signals("us", "C10", Map.of());

        assertTrue(buyHeld(view, "C11"),
                "账本持仓 C11 的『已持有』标注不应因手输 C10 而消失(union 而非替换)");
        assertTrue(buyHeld(view, "C10"),
                "手输的 C10 也应标注已持有");
    }

    @Test
    void lowercaseManualHoldingMatchesUppercaseCode() {
        // 大小写:手输小写应匹配数据里的大写 code(A股纯数字不受影响)。
        SignalService.SignalView view = service(TestStores.tmp("us")).signals("us", "c11", Map.of());
        assertTrue(buyHeld(view, "C11"), "小写 c11 应匹配 C11 并标注已持有");
    }
}
