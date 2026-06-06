package com.aistock.web;

import com.aistock.datasource.KlinePoint;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * 个股 K 线:详情页({@code /chart})+ 行情 JSON 接口({@code /api/kline})。
 *
 * <p>纯行情展示,不下单、不记账。详情页用 ECharts 画蜡烛图 + 成交量,周期可切换;
 * 前端按需调 {@code /api/kline} 拉数据。所有降级(空数据)在 {@link KlineService} /
 * 数据源内完成,接口<b>绝不抛 500</b>。
 */
@Controller
public class KlineController {

    private final KlineService klineService;

    public KlineController(KlineService klineService) {
        this.klineService = klineService;
    }

    /**
     * 个股详情页:渲染 K 线图骨架(标题 code + 周期按钮 + ECharts 容器),
     * 数据由前端异步从 {@link #kline} 拉取。
     */
    @GetMapping("/chart")
    public String chart(@RequestParam(name = "market", required = false, defaultValue = "us") String market,
                        @RequestParam(name = "code", required = false) String code,
                        Model model) {
        String normalized = SignalService.normalizeMarket(market);
        model.addAttribute("market", normalized);
        model.addAttribute("code", code == null ? "" : code.trim());
        return "chart";
    }

    /**
     * K 线 JSON 接口:返回 {@link KlinePoint} 数组(Jackson 自动转 JSON)。
     *
     * <p>code 缺失 / 空 → 返回空数组(前端显示「暂无数据」);任何数据源失败同样
     * 降级为空数组,绝不 500。
     *
     * @param market 市场("us" | "cn")
     * @param code   股票代码
     * @param period 周期码(默认 {@code "1d"} 日线)
     * @return K 线列表(oldest-first),可能为空
     */
    @GetMapping("/api/kline")
    @ResponseBody
    public List<KlinePoint> kline(
            @RequestParam(name = "market", required = false, defaultValue = "us") String market,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "period", required = false, defaultValue = "1d") String period) {
        return klineService.kline(market, code, period);
    }
}
