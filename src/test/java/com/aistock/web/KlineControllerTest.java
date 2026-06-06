package com.aistock.web;

import com.aistock.datasource.KlinePoint;
import com.aistock.datasource.KlineSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link KlineController} 的 {@code /api/kline} JSON 接口测试。
 *
 * <p>用 standaloneSetup + stub {@link KlineSource}(返回固定 List),绝不触网。
 */
class KlineControllerTest {

    /** 固定返回两根 K 线的 stub 数据源;按市场注入两个相同 stub。 */
    static class StubSource implements KlineSource {
        @Override
        public List<KlinePoint> fetchKline(String code, com.aistock.datasource.KlinePeriod period) {
            return List.of(
                    new KlinePoint("2024-01-02", 10.0, 12.0, 9.5, 11.0, 1000L),
                    new KlinePoint("2024-01-03", 11.0, 13.0, 10.5, 12.5, 2000L));
        }
    }

    private MockMvc mvc() {
        KlineService service = new KlineService(new StubSource(), new StubSource());
        return MockMvcBuilders.standaloneSetup(new KlineController(service)).build();
    }

    @Test
    void apiKlineReturnsJsonArrayWithAllFields() throws Exception {
        mvc().perform(get("/api/kline")
                        .param("market", "us")
                        .param("code", "AAPL")
                        .param("period", "1d"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].time").value("2024-01-02"))
                .andExpect(jsonPath("$[0].open").value(10.0))
                .andExpect(jsonPath("$[0].high").value(12.0))
                .andExpect(jsonPath("$[0].low").value(9.5))
                .andExpect(jsonPath("$[0].close").value(11.0))
                .andExpect(jsonPath("$[0].volume").value(1000));
    }

    @Test
    void missingCodeReturnsEmptyArrayNot500() throws Exception {
        mvc().perform(get("/api/kline").param("market", "us"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void blankCodeReturnsEmptyArray() throws Exception {
        mvc().perform(get("/api/kline").param("market", "cn").param("code", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
