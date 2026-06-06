package com.aistock.web;

import com.aistock.feature.MarketPanel;
import com.aistock.storage.StrategyParams;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略参数在线配置页(对标 Python 原版 tab4)。
 *
 * <p>只调<b>选股策略参数</b>:topN、个股止损线 stopLossPct、三个因子权重。
 * 保存即持久化(按 market),signals/push 下次实时生效,回测缓存随保存失效。
 *
 * <p><b>刻意不含</b> lot / 初始资金 / 回撤护栏:Java 版纯名单建议,不记账下单。
 */
@Controller
public class ParamsController {

    /** topN 合法范围。 */
    private static final int TOP_N_MIN = 1;
    private static final int TOP_N_MAX = 20;
    /** 止损线合法范围(≤0 且 ≥-0.5)。 */
    private static final double STOP_LOSS_MIN = -0.5;
    private static final double STOP_LOSS_MAX = 0.0;
    /** 因子权重合法范围。 */
    private static final double WEIGHT_MIN = -2.0;
    private static final double WEIGHT_MAX = 2.0;

    private final SignalService signalService;

    public ParamsController(SignalService signalService) {
        this.signalService = signalService;
    }

    @GetMapping("/params")
    public String params(@RequestParam(name = "market", required = false, defaultValue = "us") String market,
                         @RequestParam(name = "saved", required = false) String saved,
                         Model model) {
        String mkt = SignalService.normalizeMarket(market);
        StrategyParams params = signalService.loadParams(mkt);
        populate(model, mkt, params);
        model.addAttribute("saved", saved != null);
        return "params";
    }

    @PostMapping("/params")
    public String save(@RequestParam(name = "market", required = false, defaultValue = "us") String market,
                       @RequestParam(name = "topN", required = false) String topNRaw,
                       @RequestParam(name = "stopLossPct", required = false) String stopLossRaw,
                       @RequestParam Map<String, String> all,
                       Model model) {
        String mkt = SignalService.normalizeMarket(market);
        List<String> errors = new ArrayList<>();

        int topN = parseInt(topNRaw, "topN", errors);
        if (topN < TOP_N_MIN || topN > TOP_N_MAX) {
            errors.add("topN 必须在 " + TOP_N_MIN + ".." + TOP_N_MAX + " 之间");
        }

        double stopLoss = parseDouble(stopLossRaw, "止损线", errors);
        if (!Double.isNaN(stopLoss) && (stopLoss > STOP_LOSS_MAX || stopLoss < STOP_LOSS_MIN)) {
            errors.add("止损线必须 ≤0 且 ≥" + STOP_LOSS_MIN + "(如 -0.08)");
        }

        Map<String, Double> weights = new LinkedHashMap<>();
        for (String factor : MarketPanel.FACTORS) {
            String raw = all.get("w_" + factor);
            double w = parseDouble(raw, "权重 " + factor, errors);
            if (!Double.isNaN(w) && (w < WEIGHT_MIN || w > WEIGHT_MAX)) {
                errors.add("权重 " + factor + " 必须在 " + WEIGHT_MIN + ".." + WEIGHT_MAX + " 之间");
            }
            weights.put(factor, w);
        }

        if (!errors.isEmpty()) {
            // 回显输入值(用原始字符串安全回填)与错误,不保存。
            model.addAttribute("market", mkt);
            model.addAttribute("topN", topNRaw);
            model.addAttribute("stopLossPct", stopLossRaw);
            Map<String, String> weightStrings = new LinkedHashMap<>();
            for (String factor : MarketPanel.FACTORS) {
                weightStrings.put(factor, all.get("w_" + factor));
            }
            model.addAttribute("weights", weightStrings);
            model.addAttribute("errors", errors);
            model.addAttribute("saved", false);
            return "params";
        }

        signalService.saveParams(mkt, new StrategyParams(topN, stopLoss, weights));
        return "redirect:/params?market=" + mkt + "&saved=1";
    }

    @PostMapping("/params/reset")
    public String reset(@RequestParam(name = "market", required = false, defaultValue = "us") String market) {
        String mkt = SignalService.normalizeMarket(market);
        signalService.resetParams(mkt);
        return "redirect:/params?market=" + mkt + "&saved=1";
    }

    /** 用当前(合法)参数填充模型,值为字符串便于模板原样回填。 */
    private void populate(Model model, String market, StrategyParams params) {
        model.addAttribute("market", market);
        model.addAttribute("topN", String.valueOf(params.topN()));
        model.addAttribute("stopLossPct", String.valueOf(params.stopLossPct()));
        Map<String, String> weights = new LinkedHashMap<>();
        for (String factor : MarketPanel.FACTORS) {
            Double w = params.factorWeights().get(factor);
            weights.put(factor, w == null ? "0" : String.valueOf(w));
        }
        model.addAttribute("weights", weights);
    }

    private static int parseInt(String raw, String label, List<String> errors) {
        try {
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            errors.add(label + " 必须是整数");
            return Integer.MIN_VALUE;
        }
    }

    private static double parseDouble(String raw, String label, List<String> errors) {
        try {
            return Double.parseDouble(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            errors.add(label + " 必须是数字");
            return Double.NaN;
        }
    }
}
