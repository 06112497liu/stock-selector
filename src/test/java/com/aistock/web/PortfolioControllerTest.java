package com.aistock.web;

import com.aistock.datasource.Bar;
import com.aistock.datasource.DataSource;
import com.aistock.service.MarketDataService;
import com.aistock.storage.Store;
import com.aistock.storage.Store.Position;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账本页端到端:GET /portfolio、/reconcile 返回 200;POST /reconcile 替换持仓 + 清 nav;
 * /signals 用 Store 真实持仓带出 hold/sell(「掉出 topN 但 score>0 不卖」仍成立)。
 *
 * <p>用 tmp SQLite Store(静态持有,便于断言),合成盘面,绝不触网。
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class PortfolioControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    @Qualifier("usStore")
    private Store usStore;

    // ---- 合成盘面(同 SignalControllerTest:12 票动量严格递增)----------------

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);

    private static List<Bar> bars(double startClose, double dailyRet) {
        List<Bar> out = new ArrayList<>();
        LocalDate d = D0;
        double c = startClose;
        for (int i = 0; i < 40; i++) {
            out.add(new Bar(d, c, c, c, c, 1000L));
            c *= (1.0 + dailyRet);
            d = d.plusDays(1);
        }
        return out;
    }

    static final List<String> CODES = new ArrayList<>();
    static {
        for (int i = 0; i < 12; i++) {
            CODES.add(String.format("C%02d", i));
        }
    }

    static class SyntheticSource implements DataSource {
        @Override
        public List<Bar> updateBars(String code) {
            int idx = CODES.indexOf(code);
            return idx < 0 ? List.of() : bars(100.0, 0.002 + idx * 0.002);
        }
    }

    private static MarketDataService syntheticService() {
        Map<String, String> names = new LinkedHashMap<>();
        for (String c : CODES) {
            names.put(c, "名称-" + c);
        }
        return new MarketDataService(new SyntheticSource(), names::get,
                c -> java.util.OptionalDouble.empty(), CODES);
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        MarketDataService usMarketDataService() {
            return syntheticService();
        }

        @Bean
        MarketDataService cnMarketDataService() {
            return syntheticService();
        }

        @Bean
        Store usStore() {
            return tmp("us");
        }

        @Bean
        Store cnStore() {
            return tmp("cn");
        }

        @Bean
        com.aistock.storage.ParamsStore usParams() {
            return TestStores.tmpParams("us");
        }

        @Bean
        com.aistock.storage.ParamsStore cnParams() {
            return TestStores.tmpParams("cn");
        }

        private static Store tmp(String name) {
            try {
                return new Store(Files.createTempDirectory("portctl-" + name)
                        .resolve(name + "_ledger.sqlite").toString());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ---- 测试 --------------------------------------------------------------

    @Test
    void portfolioPage_returns200_withSummaryAndNavHint() throws Exception {
        // 空账本:净值为 0,曲线提示「暂无净值记录,先去对账」(首访已记一笔 0,故曲线非空但摘要为 0)。
        String html = mvc.perform(get("/portfolio").param("market", "us"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("净值")))
                .andExpect(content().string(Matchers.containsString("持仓")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("账户") || html.contains("现金"), "应含账户摘要字段");
    }

    @Test
    void reconcilePage_returns200_withForm() throws Exception {
        mvc.perform(get("/reconcile").param("market", "us"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("对账")))
                .andExpect(content().string(Matchers.containsString("avg_cost")))
                .andExpect(content().string(Matchers.containsString("现金")));
    }

    @Test
    void postReconcile_replacesPositions_clearsNav_andRedirects() throws Exception {
        // 先污染:旧持仓 + 旧净值
        usStore.upsertPosition("OLD", 1, 1);
        usStore.recordNav("2024-01-01", 9999.0);

        mvc.perform(post("/reconcile")
                        .param("market", "us")
                        .param("code", "C11", "C00", "")  // 第三行空 code 应被跳过
                        .param("shares", "10", "5", "3")
                        .param("avgCost", "100", "200", "300")
                        .param("cash", "2500"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/portfolio?market=us&reconciled=1"));

        Map<String, Position> pos = usStore.getPositions();
        assertEquals(2, pos.size(), "空 code 行跳过,只剩 2 笔");
        assertFalse(pos.containsKey("OLD"), "旧持仓应被整体替换");
        assertEquals(10, pos.get("C11").shares(), 1e-9);
        assertEquals(100, pos.get("C11").avgCost(), 1e-9);
        assertEquals(2500.0, usStore.getCash(), 1e-9);
        assertTrue(usStore.navHistory().isEmpty(), "对账后净值历史必须清空(基准重置)");
    }

    @Test
    void signals_usesStoreHoldings_forHoldAndSell() throws Exception {
        // 账本里放 C06(score>0 但在 top5 外)与 C00(score<0)。
        usStore.replaceAllPositions(new LinkedHashMap<>(Map.of(
                "C06", new Position(10, 100.0),
                "C00", new Position(10, 100.0))));

        // 不传 holdings 参数 -> 应默认用账本持仓
        String html = mvc.perform(get("/signals").param("market", "us"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int holdIdx = html.indexOf("<!-- 继续持有 -->");
        int sellIdx = html.indexOf("<!-- 该卖 -->");
        assertTrue(holdIdx >= 0 && sellIdx > holdIdx, "页面应含继续持有与该卖两段");
        String holdSection = html.substring(holdIdx, sellIdx);
        String sellSection = html.substring(sellIdx);

        assertTrue(holdSection.contains("C06"),
                "C06 掉出 topN 但 score>0,用账本持仓应继续持有而非卖出");
        assertFalse(sellSection.contains("C06"), "C06 不应在该卖名单");
        assertTrue(sellSection.contains("C00"), "C00 打分转负,应进该卖名单");
    }
}
