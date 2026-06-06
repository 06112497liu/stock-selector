package com.aistock.web;

import com.aistock.datasource.Bar;
import com.aistock.datasource.DataSource;
import com.aistock.datasource.KlinePoint;
import com.aistock.datasource.KlineSource;
import com.aistock.service.MarketDataService;
import com.aistock.storage.Store;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 个股 K 线详情页({@code /chart})的 MockMvc 渲染测试,以及 signals 表 code
 * 链接断言。用 stub {@link KlineSource} / 合成行情服务覆盖真实 Bean,绝不触网。
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class ChartPageTest {

    @Autowired
    private MockMvc mvc;

    static class StubKlineSource implements KlineSource {
        @Override
        public List<KlinePoint> fetchKline(String code, com.aistock.datasource.KlinePeriod period) {
            return List.of(new KlinePoint("2024-01-02", 10.0, 12.0, 9.5, 11.0, 1000L));
        }
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        KlineSource usKlineSource() {
            return new StubKlineSource();
        }

        @Bean
        KlineSource cnKlineSource() {
            return new StubKlineSource();
        }

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
            return TestStores.tmp("us");
        }

        @Bean
        Store cnStore() {
            return TestStores.tmp("cn");
        }

        @Bean
        com.aistock.storage.ParamsStore usParams() {
            return TestStores.tmpParams("us");
        }

        @Bean
        com.aistock.storage.ParamsStore cnParams() {
            return TestStores.tmpParams("cn");
        }

        // 12 只合成票 C00..C11,几何复利价(日收益随序号递增),
        // 让横截面动量有区分度、signals 页能渲出买入表(含 C11 链接)。
        private static final java.util.List<String> CODES = new java.util.ArrayList<>();
        static {
            for (int i = 0; i < 12; i++) {
                CODES.add(String.format("C%02d", i));
            }
        }

        private static java.util.List<Bar> bars(double startClose, double dailyRet) {
            java.util.List<Bar> out = new java.util.ArrayList<>();
            java.time.LocalDate d = java.time.LocalDate.of(2024, 1, 1);
            double c = startClose;
            for (int i = 0; i < 40; i++) {
                out.add(new Bar(d, c, c, c, c, 1000L));
                c *= (1.0 + dailyRet);
                d = d.plusDays(1);
            }
            return out;
        }

        private static MarketDataService syntheticService() {
            DataSource src = code -> {
                int idx = CODES.indexOf(code);
                return idx < 0 ? java.util.List.of() : bars(100.0, 0.002 + idx * 0.002);
            };
            java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
            java.util.Map<String, java.util.OptionalDouble> caps = new java.util.LinkedHashMap<>();
            for (String c : CODES) {
                names.put(c, "名称-" + c);
                caps.put(c, java.util.OptionalDouble.of(1.5e9));
            }
            return new MarketDataService(src, names::get, caps::get, CODES);
        }
    }

    @Test
    void chartPage_returns200_withEchartsAndPeriodButtons() throws Exception {
        mvc.perform(get("/chart").param("market", "us").param("code", "AAPL"))
                .andExpect(status().isOk())
                // 标题含 code
                .andExpect(content().string(Matchers.containsString("AAPL")))
                // ECharts 容器
                .andExpect(content().string(Matchers.containsString("id=\"kline\"")))
                // 引了 vendored echarts.min.js
                .andExpect(content().string(Matchers.containsString("/js/echarts.min.js")))
                // 周期按钮文案
                .andExpect(content().string(Matchers.containsString("1分")))
                .andExpect(content().string(Matchers.containsString("60分")))
                .andExpect(content().string(Matchers.containsString("周")))
                .andExpect(content().string(Matchers.containsString("月")))
                // 免责声明照旧保留
                .andExpect(content().string(Matchers.containsString("未经样本外验证")));
    }

    @Test
    void signalsPage_codeCellsLinkToChart() throws Exception {
        String html = mvc.perform(get("/signals").param("market", "us"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // code 现在是链接到 /chart 的 <a>(C11 动量最高,必在买入表)
        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("/chart?market=us&amp;code=C11")
                        || html.contains("/chart?market=us&code=C11"),
                "signals 表 code 应链接到 /chart");
        // 原有断言风格:code 文本仍可被找到
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("C11"),
                "code 文本仍应渲染");
    }
}
