package com.aistock.web;

import com.aistock.datasource.Bar;
import com.aistock.datasource.DataSource;
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
 * 无数据降级的端到端渲染:数据源完全取不到(联网失败且无缓存)时,
 * 页面必须返回 200 且含友好提示,绝不抛 500。
 *
 * <p>单独的 context(空 stub)以免与正常盘面测试互相干扰。
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class SignalControllerEmptyDataTest {

    @Autowired
    private MockMvc mvc;

    /** 永远返回空的 DataSource。 */
    static class EmptySource implements DataSource {
        @Override
        public List<Bar> updateBars(String code) {
            return List.of();
        }
    }

    private static MarketDataService emptyService() {
        return new MarketDataService(new EmptySource(), c -> c,
                c -> java.util.OptionalDouble.empty(), List.of("X"));
    }

    @TestConfiguration
    static class StubConfig {
        // 同名覆盖,不加 @Primary;按构造参数名精确注入。
        @Bean
        MarketDataService usMarketDataService() {
            return emptyService();
        }

        @Bean
        MarketDataService cnMarketDataService() {
            return emptyService();
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
    }

    @Test
    void signals_emptyData_returns200_withFriendlyMessage() throws Exception {
        mvc.perform(get("/signals").param("market", "us"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("暂无数据")));
    }

    @Test
    void backtest_emptyData_returns200_withFriendlyMessage() throws Exception {
        mvc.perform(get("/backtest").param("market", "us"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("暂无数据")));
    }
}
