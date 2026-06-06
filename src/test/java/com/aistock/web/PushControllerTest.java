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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /push 端到端测试:MockMvc + stub 数据源 + fake {@link ServerChanNotifier}(绝不触网)。
 *
 * <p>fake notifier 已配置且 send 返回 true,断言页面回显「已推送」;
 * 并断言 fake 被调用(即推送链路真实跑通,而非短路)。「未配置」分支见
 * {@link PushControllerNotConfiguredTest}。
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class PushControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ServerChanNotifier injectedNotifier;

    private FakeNotifier notifier() {
        return (FakeNotifier) injectedNotifier;
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

    /** fake:已配置,send 永远成功,并记录被调用。 */
    static class FakeNotifier extends ServerChanNotifier {
        volatile boolean called = false;
        volatile String lastTitle;
        volatile String lastBody;

        FakeNotifier() {
            super("FAKE-KEY");
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public boolean send(String title, String markdownBody) {
            called = true;
            lastTitle = title;
            lastBody = markdownBody;
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
            return new FakeNotifier();
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
    void pushReturns200_andShowsSuccess_andCallsNotifier() throws Exception {
        mvc.perform(post("/push").param("market", "us"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已推送")));

        FakeNotifier notifier = notifier();
        assertTrue(notifier.called, "应真实调用 notifier.send");
        assertTrue(notifier.lastTitle != null && notifier.lastTitle.contains("选股"),
                "标题应来自 SignalFormatter.pushTitle: " + notifier.lastTitle);
        assertTrue(notifier.lastBody != null && notifier.lastBody.contains("该买"),
                "正文应来自 SignalFormatter.toMarkdown");
    }
}
