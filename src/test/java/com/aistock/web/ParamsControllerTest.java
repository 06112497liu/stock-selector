package com.aistock.web;

import com.aistock.datasource.Bar;
import com.aistock.datasource.DataSource;
import com.aistock.service.MarketDataService;
import com.aistock.storage.ParamsStore;
import com.aistock.storage.Store;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 参数配置页 Web 端到端:GET 含当前值;POST 合法值保存并 302;非法值回显错误不保存;reset 有效。
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class ParamsControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ParamsStore usParams;

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);

    private static List<Bar> bars(double dailyRet) {
        List<Bar> out = new ArrayList<>();
        double c = 100.0;
        for (int i = 0; i < 40; i++) {
            out.add(new Bar(D0.plusDays(i), c, c, c, c, 1000L));
            c *= (1.0 + dailyRet);
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
            return idx < 0 ? List.of() : bars(0.002 + idx * 0.002);
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
            return TestStores.tmp("us");
        }

        @Bean
        Store cnStore() {
            return TestStores.tmp("cn");
        }

        @Bean
        ParamsStore usParams() {
            return TestStores.tmpParams("us");
        }

        @Bean
        ParamsStore cnParams() {
            return TestStores.tmpParams("cn");
        }
    }

    @Test
    void getParams_returns200_withCurrentValuesAndNav() throws Exception {
        usParams.save(new com.aistock.storage.StrategyParams(7, -0.11, Map.of("mom_20", 1.3)));
        mvc.perform(get("/params").param("market", "us"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("策略参数配置")))
                .andExpect(content().string(containsString("参数配置"))) // 导航链接
                .andExpect(content().string(containsString("value=\"7\"")))
                .andExpect(content().string(containsString("-0.11")))
                .andExpect(content().string(containsString("mom_20")));
    }

    @Test
    void postValidParams_savesAndRedirects() throws Exception {
        mvc.perform(post("/params")
                        .param("market", "us")
                        .param("topN", "4")
                        .param("stopLossPct", "-0.06")
                        .param("w_mom_20", "0.8")
                        .param("w_reversal_5", "0.3")
                        .param("w_vol_20", "-0.4"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/params?market=us&saved=1"));

        com.aistock.storage.StrategyParams p = usParams.load();
        assertEquals(4, p.topN());
        assertEquals(-0.06, p.stopLossPct(), 1e-12);
        assertEquals(0.8, p.factorWeights().get("mom_20"), 1e-12);
    }

    @Test
    void postInvalidTopN_showsErrorAndDoesNotSave() throws Exception {
        usParams.save(new com.aistock.storage.StrategyParams(5, -0.08,
                new LinkedHashMap<>(com.aistock.selector.FactorSelector.DEFAULT_WEIGHTS)));

        mvc.perform(post("/params")
                        .param("market", "us")
                        .param("topN", "0")            // 非法:< 1
                        .param("stopLossPct", "-0.06")
                        .param("w_mom_20", "0.8")
                        .param("w_reversal_5", "0.3")
                        .param("w_vol_20", "-0.4"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("未保存")));

        assertEquals(5, usParams.load().topN(), "非法输入不得改动已存参数");
    }

    @Test
    void postInvalidStopLossPositive_showsErrorAndDoesNotSave() throws Exception {
        usParams.save(new com.aistock.storage.StrategyParams(5, -0.08,
                new LinkedHashMap<>(com.aistock.selector.FactorSelector.DEFAULT_WEIGHTS)));

        mvc.perform(post("/params")
                        .param("market", "us")
                        .param("topN", "5")
                        .param("stopLossPct", "0.05")  // 非法:> 0
                        .param("w_mom_20", "0.8")
                        .param("w_reversal_5", "0.3")
                        .param("w_vol_20", "-0.4"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("未保存")));

        assertEquals(-0.08, usParams.load().stopLossPct(), 1e-12, "非法止损不得改动已存参数");
    }

    @Test
    void postReset_restoresDefaultsAndRedirects() throws Exception {
        usParams.save(new com.aistock.storage.StrategyParams(9, -0.2, Map.of("mom_20", 2.0)));

        mvc.perform(post("/params/reset").param("market", "us"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/params?market=us&saved=1"));

        com.aistock.storage.StrategyParams p = usParams.load();
        assertEquals(com.aistock.storage.StrategyParams.defaults().topN(), p.topN());
    }
}
