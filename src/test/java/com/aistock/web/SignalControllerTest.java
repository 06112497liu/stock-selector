package com.aistock.web;

import com.aistock.datasource.Bar;
import com.aistock.datasource.DataSource;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层端到端测试:MockMvc + stub 数据源(绝不触网)。
 *
 * <p>用 {@link TestConfiguration} 覆盖真实的两个 {@code MarketDataService} Bean,
 * 注入<b>合成面板 + 合成名称</b>,让 SignalService 真实跑 RecommendEngine /
 * BacktestEngine,验证页面渲染与关键业务口径。
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class SignalControllerTest {

    @Autowired
    private MockMvc mvc;

    // ---- 合成盘面 ----------------------------------------------------------

    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);

    /**
     * 几何复利价格:close[i] = startClose * (1 + dailyRet)^i,40 天连续。
     *
     * <p>用几何(恒定日收益)而非线性:日收益恒定 -> vol_20 = 0,且 mom_20 与
     * reversal_5 随 dailyRet <b>同向单调</b>,二者加权叠加不互相抵消。
     * 这样最新交易日横截面 score 对各票有真实区分度(动量主导),
     * 可端到端验证选股排名与「掉出 topN 但 score>0 不卖」规则。
     */
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

    /**
     * 12 只票,日收益率严格递增 -> 横截面动量严格递增。
     * rankNormalize(k=12) 后约上半为正、下半为负;DEFAULT_TOP_N=5,
     * 故「score>0 的票数(6) > topN(5)」,存在「score>0 但排在 top5 外」的票,
     * 用来端到端验证「掉出 topN 但 score>0 不卖」。
     * 代码命名 C00..C11,日收益随序号递增,C11 动量最高。
     */
    static final List<String> CODES = new ArrayList<>();
    static {
        for (int i = 0; i < 12; i++) {
            CODES.add(String.format("C%02d", i));
        }
    }

    /** 合成 DataSource:返回内存 Bar,永不触网。 */
    static class SyntheticSource implements DataSource {
        @Override
        public List<Bar> updateBars(String code) {
            int idx = CODES.indexOf(code);
            if (idx < 0) {
                return List.of();
            }
            return bars(100.0, 0.002 + idx * 0.002); // 日收益随序号递增(0.4%..2.6%)
        }
    }

    private static MarketDataService syntheticService() {
        Map<String, String> names = new LinkedHashMap<>();
        for (String c : CODES) {
            names.put(c, "名称-" + c); // 合成名称,绝不触网
        }
        // 合成市值:C11=4.57万亿(美股$),其余固定值;绝不触网。
        java.util.Map<String, java.util.OptionalDouble> caps = new LinkedHashMap<>();
        for (String c : CODES) {
            caps.put(c, "C11".equals(c)
                    ? java.util.OptionalDouble.of(4571145961472.0)
                    : java.util.OptionalDouble.of(1.5e9));
        }
        return new MarketDataService(new SyntheticSource(), names::get, caps::get, CODES);
    }

    @TestConfiguration
    static class StubConfig {
        // 同名覆盖真实 Bean(allow-bean-definition-overriding=true);
        // 不加 @Primary,以免两个同类型 Bean 同时 primary 造成歧义,
        // 由 SignalService 构造参数名(usMarketDataService/cnMarketDataService)精确按名注入。
        @Bean
        MarketDataService usMarketDataService() {
            return syntheticService();
        }

        @Bean
        MarketDataService cnMarketDataService() {
            return syntheticService();
        }

        // 账本用 tmp SQLite(空库),不联网。默认空持仓 -> 与原行为(holdings 来自手输)一致。
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

    // ---- 测试 --------------------------------------------------------------

    @Test
    void signalsPage_returns200_withBuyTableNamesAndDisclaimer() throws Exception {
        mvc.perform(get("/signals").param("market", "us"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("该买")))
                // 免责声明文案
                .andExpect(content().string(org.hamcrest.Matchers.containsString("未经样本外验证")))
                // 至少一个合成 code 的合成名称出现(top 应含动量最高的 C11)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("名称-C11")))
                // 新增「市值」列表头
                .andExpect(content().string(org.hamcrest.Matchers.containsString("市值")))
                // 新增「涨跌幅」列表头
                .andExpect(content().string(org.hamcrest.Matchers.containsString("涨跌幅")))
                // 合成几何价日收益恒正 → 买入行应渲染出带符号正涨跌幅(C11 日收益 2.4% → +2.40%)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("+2.40%")))
                // C11 合成市值 4.57万亿(美股 $ 前缀)应渲染进买入行
                .andExpect(content().string(org.hamcrest.Matchers.containsString("$4.57万亿")));
    }

    @Test
    void heldButHighScore_outOfTopN_isNotSold_butHeld() throws Exception {
        // C06:动量排名中上(score>0),但排在 top5(C11..C07)之外 -> 规则:不卖,进继续持有。
        // C00:动量最低(score<0)-> 规则:打分转负 -> 进该卖。
        String html = mvc.perform(get("/signals")
                        .param("market", "us")
                        .param("holdings", "C06,C00"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 用模板里唯一的 HTML 注释作锚点切段(免责声明里也含「继续持有 / 该卖」字样,
        // 不能用纯中文词定位)。
        int holdIdx = html.indexOf("<!-- 继续持有 -->");
        int sellIdx = html.indexOf("<!-- 该卖 -->");
        org.junit.jupiter.api.Assertions.assertTrue(holdIdx >= 0 && sellIdx > holdIdx,
                "页面应同时含继续持有与该卖两段");
        String holdSection = html.substring(holdIdx, sellIdx);
        String sellSection = html.substring(sellIdx);

        org.junit.jupiter.api.Assertions.assertTrue(holdSection.contains("C06"),
                "C06 掉出 topN 但 score>0,应继续持有而非卖出");
        org.junit.jupiter.api.Assertions.assertFalse(sellSection.contains("C06"),
                "C06 不应出现在该卖名单");
        org.junit.jupiter.api.Assertions.assertTrue(sellSection.contains("C00"),
                "C00 打分转负,应进该卖名单");
    }

    @Test
    void refresh_returns302_redirectsBackToSource() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/refresh").param("market", "us").param("from", "/signals"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/signals?market=us"));
    }

    @Test
    void backtestPage_returns200_withMetricsFields() throws Exception {
        mvc.perform(get("/backtest").param("market", "us"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("annReturn")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("sharpe")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("maxDrawdown")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("winRate")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("未经验证")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("样本外回测")));
    }
}
