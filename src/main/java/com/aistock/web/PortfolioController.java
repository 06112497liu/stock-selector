package com.aistock.web;

import com.aistock.storage.Store.Position;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账本页控制器:「持仓与净值」(/portfolio)与「对账」(/reconcile)。
 *
 * <p>对账是把账本改成券商真实持仓的唯一入口;对账后清空净值历史、重置净值基准
 * (避免旧的虚假净值峰值污染回撤判断)。这些页面不下单、不按资产算金额。
 */
@Controller
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/portfolio")
    public String portfolio(@RequestParam(name = "market", required = false, defaultValue = "us") String market,
                            Model model) {
        PortfolioService.PortfolioPageView view = portfolioService.portfolio(market);
        model.addAttribute("view", view);
        model.addAttribute("market", view.market());
        return "portfolio";
    }

    @GetMapping("/reconcile")
    public String reconcileForm(@RequestParam(name = "market", required = false, defaultValue = "us") String market,
                                Model model) {
        PortfolioService.ReconcilePageView view = portfolioService.reconcileForm(market);
        model.addAttribute("view", view);
        model.addAttribute("market", view.market());
        return "reconcile";
    }

    /**
     * 应用对账:解析多行 code/shares/avg_cost + 现金 -> 整体替换持仓、设现金、清空净值历史,
     * 重定向回 /portfolio 并提示「账本已更新、净值基准已重置」。
     *
     * <p>三个数组按行平行对齐;code 空白行跳过;shares/avg_cost 非法按 0 计。
     */
    @PostMapping("/reconcile")
    public String applyReconcile(@RequestParam(name = "market", required = false, defaultValue = "us") String market,
                                 @RequestParam(name = "code", required = false) List<String> codes,
                                 @RequestParam(name = "shares", required = false) List<String> sharesList,
                                 @RequestParam(name = "avgCost", required = false) List<String> avgCostList,
                                 @RequestParam(name = "cash", required = false, defaultValue = "0") String cashStr) {
        Map<String, Position> positions = parsePositions(codes, sharesList, avgCostList);
        double cash = parseDouble(cashStr, 0.0);
        portfolioService.applyReconcile(market, positions, cash);
        String normalized = SignalService.normalizeMarket(market);
        return "redirect:/portfolio?market=" + normalized + "&reconciled=1";
    }

    private static Map<String, Position> parsePositions(List<String> codes,
                                                        List<String> sharesList,
                                                        List<String> avgCostList) {
        Map<String, Position> out = new LinkedHashMap<>();
        if (codes == null) {
            return out;
        }
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i) == null ? "" : codes.get(i).trim();
            if (code.isEmpty()) {
                continue; // 允许增删行:空 code 行视为删除/未填,跳过。
            }
            double shares = parseDouble(at(sharesList, i), 0.0);
            double avgCost = parseDouble(at(avgCostList, i), 0.0);
            out.put(code, new Position(shares, avgCost));
        }
        return out;
    }

    private static String at(List<String> list, int i) {
        return (list != null && i < list.size()) ? list.get(i) : null;
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
