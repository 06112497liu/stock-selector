package com.aistock.web;

import com.aistock.storage.TradingJournalStore.OperationType;
import com.aistock.storage.WatchlistStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Controller
public class TradingJournalController {

    private final TradingJournalService journalService;
    private final WatchlistService watchlistService;

    public TradingJournalController(TradingJournalService journalService, WatchlistService watchlistService) {
        this.journalService = journalService;
        this.watchlistService = watchlistService;
    }

    @GetMapping("/journal")
    public String list(@RequestParam(name = "market", required = false, defaultValue = "") String market,
                       @RequestParam(name = "msg", required = false) String msg,
                       @RequestParam(name = "err", required = false) String err,
                       Model model) {
        TradingJournalService.JournalPageView view = journalService.pageView(market);
        model.addAttribute("view", view);
        model.addAttribute("market", market);
        model.addAttribute("markets", buildMarketOptions());
        model.addAttribute("msg", msg);
        model.addAttribute("err", err);
        return "trading-journal";
    }

    @PostMapping("/journal/add")
    public String addEntry(@RequestParam(name = "market", required = false, defaultValue = "us") String market,
                           @RequestParam(name = "operationType") String operationType,
                           @RequestParam(name = "code") String code,
                           @RequestParam(name = "quantity") String quantityStr,
                           @RequestParam(name = "price") String priceStr,
                           @RequestParam(name = "tradeDate", required = false) String tradeDateStr,
                           @RequestParam(name = "reason", required = false) String reason,
                           @RequestParam(name = "marketEnv", required = false) String marketEnv,
                           @RequestParam(name = "notes", required = false) String notes) {
        try {
            String safeCode = code == null ? "" : code.trim();
            if (safeCode.isEmpty()) {
                return redirect(market, null, "股票代码不能为空");
            }
            double quantity = parseDouble(quantityStr, 0);
            if (quantity <= 0) {
                return redirect(market, null, "数量必须大于 0");
            }
            double price = parseDouble(priceStr, 0);
            if (price <= 0) {
                return redirect(market, null, "价格必须大于 0");
            }
            OperationType op;
            try {
                op = OperationType.valueOf(operationType.toUpperCase());
            } catch (IllegalArgumentException e) {
                return redirect(market, null, "操作类型必须是 BUY 或 SELL");
            }
            LocalDateTime tradeDate = parseDateTime(tradeDateStr);
            journalService.addEntry(market, op, safeCode, quantity, price, tradeDate,
                    reason, marketEnv, notes);
            return redirect(market, "交易记录已添加", null);
        } catch (Exception e) {
            return redirect(market, null, "添加失败:" + e.getMessage());
        }
    }

    @PostMapping("/journal/delete")
    public String deleteEntry(@RequestParam(name = "id") long id,
                              @RequestParam(name = "market", required = false, defaultValue = "") String market) {
        journalService.deleteEntry(id);
        return redirect(market, "记录已删除", null);
    }

    @PostMapping("/journal/updateNotes")
    public String updateNotes(@RequestParam(name = "id") long id,
                              @RequestParam(name = "notes", required = false) String notes,
                              @RequestParam(name = "market", required = false, defaultValue = "") String market) {
        journalService.updateNotes(id, notes);
        return redirect(market, "备注已更新", null);
    }

    private String redirect(String market, String msg, String err) {
        StringBuilder sb = new StringBuilder("redirect:/journal");
        boolean first = true;
        if (market != null && !market.isBlank()) {
            sb.append(first ? '?' : '&').append("market=").append(encode(market));
            first = false;
        }
        if (msg != null && !msg.isBlank()) {
            sb.append(first ? '?' : '&').append("msg=").append(encode(msg));
            first = false;
        }
        if (err != null && !err.isBlank()) {
            sb.append(first ? '?' : '&').append("err=").append(encode(err));
        }
        return sb.toString();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDateTime.parse(s + "T00:00:00", DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e2) {
                return LocalDateTime.now();
            }
        }
    }

    /**
     * 构建市场选项列表(内置 us/cn + 所有自选股分组)。
     */
    private java.util.List<MarketOption> buildMarketOptions() {
        java.util.List<MarketOption> out = new java.util.ArrayList<>();
        out.add(new MarketOption("", "全部市场"));
        out.add(new MarketOption("us", "美股(Yahoo)"));
        out.add(new MarketOption("cn", "A股(东财)"));
        for (WatchlistStore.WatchlistGroup g : watchlistService.listGroups()) {
            String label = g.groupName() + "(" + ("cn".equals(g.marketType()) ? "A" : "美") + "股自选)";
            out.add(new MarketOption(g.groupId(), label));
        }
        return out;
    }

    public record MarketOption(String value, String label) {
    }
}
