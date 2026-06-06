package com.aistock.web;

import com.aistock.datasource.Bar;
import com.aistock.datasource.DataSource;
import com.aistock.notify.ServerChanNotifier;
import com.aistock.service.MarketDataService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SendKey 未配置场景:POST /push 回显「未配置」,且绝不触网(send 不被调用);
 * /signals 页按钮旁出现「未配置 Server酱 SendKey」提示。
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class PushControllerNotConfiguredTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ServerChanNotifier injectedNotifier;

    private NeverNotifier notifier() {
        return (NeverNotifier) injectedNotifier;
    }

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

    /** fake:未配置;若 send 被调用即记标志,用于断言「绝不触网」。 */
    static class NeverNotifier extends ServerChanNotifier {
        volatile boolean called = false;

        NeverNotifier() {
            super("");
        }

        @Override
        public boolean isConfigured() {
            return false;
        }

        @Override
        public boolean send(String title, String markdownBody) {
            called = true;
            return true;
        }
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
        ServerChanNotifier serverChanNotifier() {
            return new NeverNotifier();
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
    void pushWithoutKey_showsNotConfigured_andNeverCallsSend() throws Exception {
        mvc.perform(post("/push").param("market", "us"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("未配置")));

        assertFalse(notifier().called, "未配置时不应调用 send(绝不触网)");
    }

    @Test
    void signalsPage_showsNotConfiguredHint() throws Exception {
        mvc.perform(get("/signals").param("market", "us"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("未配置 Server酱 SendKey")));
    }
}
