package com.aistock.web;

import com.aistock.notify.ServerChanNotifier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Map;

/**
 * 页面控制器:今日选股建议页(/、/signals)与样本外回测页(/backtest)。
 *
 * <p>所有页面均为只读建议视图——不提供下单 / 记账入口。免责声明由模板渲染。
 */
@Controller
public class SignalController {

    private final SignalService signalService;
    private final ServerChanNotifier notifier;
    private final PanelCache panelCache;

    public SignalController(SignalService signalService, ServerChanNotifier notifier, PanelCache panelCache) {
        this.signalService = signalService;
        this.notifier = notifier;
        this.panelCache = panelCache;
    }

    @GetMapping({"/", "/signals"})
    public String signals(@RequestParam(name = "market", required = false, defaultValue = "us") String market,
                          @RequestParam(name = "holdings", required = false) String holdings,
                          @RequestParam(name = "entryPrice", required = false) String entryPrice,
                          Model model) {
        Map<String, Double> entries = SignalService.parseEntryPrice(entryPrice);
        SignalService.SignalView view = signalService.signals(market, holdings, entries);
        model.addAttribute("view", view);
        model.addAttribute("market", view.market());
        model.addAttribute("pushConfigured", notifier.isConfigured());
        return "signals";
    }

    /**
     * 把当日选股建议推送到微信(Server酱)。持仓口径固定为空持仓(纯名单)。
     * 推送结果回显在 signals 页顶部横幅。
     */
    @PostMapping("/push")
    public String push(@RequestParam(name = "market", required = false, defaultValue = "us") String market,
                       Model model) {
        SignalService.PushResult result = signalService.push(market);

        SignalService.SignalView view = signalService.signals(market, null, Map.of());
        model.addAttribute("view", view);
        model.addAttribute("market", view.market());
        model.addAttribute("pushConfigured", notifier.isConfigured());
        model.addAttribute("pushResult", result.message());
        return "signals";
    }

    /**
     * 手动「刷新最新数据」(对标 Python 的刷新按钮):清掉该 market 的面板 + 回测缓存,
     * 下次访问重新触网拉真实数据。重定向回来源页(默认 /signals)。
     */
    @PostMapping("/refresh")
    public String refresh(@RequestParam(name = "market", required = false, defaultValue = "us") String market,
                          @RequestParam(name = "from", required = false) String from) {
        String normalized = SignalService.normalizeMarket(market);
        panelCache.invalidate(normalized);
        String target = (from != null && (from.equals("/signals") || from.equals("/backtest") || from.equals("/portfolio")))
                ? from : "/signals";
        return "redirect:" + target + "?market=" + encode(normalized);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    @GetMapping("/backtest")
    public String backtest(@RequestParam(name = "market", required = false, defaultValue = "us") String market,
                           Model model) {
        SignalService.BacktestView view = signalService.backtest(market);
        model.addAttribute("view", view);
        model.addAttribute("market", view.market());
        return "backtest";
    }
}
