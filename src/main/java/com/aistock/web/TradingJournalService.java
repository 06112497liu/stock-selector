package com.aistock.web;

import com.aistock.feature.MarketPanel;
import com.aistock.selector.FactorSelector;
import com.aistock.service.MarketDataService;
import com.aistock.storage.ParamsStore;
import com.aistock.storage.StrategyParams;
import com.aistock.storage.TradingJournalStore;
import com.aistock.storage.TradingJournalStore.JournalEntry;
import com.aistock.storage.TradingJournalStore.OperationType;
import com.aistock.storage.WatchlistStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TradingJournalService {

    private final TradingJournalStore store;
    private final MarketDataService us;
    private final MarketDataService cn;
    private final ParamsStore usParams;
    private final ParamsStore cnParams;
    private final PanelCache panelCache;
    private final WatchlistService watchlistService;

    public TradingJournalService(TradingJournalStore tradingJournalStore,
                                 @Qualifier("usMarketDataService") MarketDataService us,
                                 @Qualifier("cnMarketDataService") MarketDataService cn,
                                 @Qualifier("usParams") ParamsStore usParams,
                                 @Qualifier("cnParams") ParamsStore cnParams,
                                 PanelCache panelCache,
                                 WatchlistService watchlistService) {
        this.store = tradingJournalStore;
        this.us = us;
        this.cn = cn;
        this.usParams = usParams;
        this.cnParams = cnParams;
        this.panelCache = panelCache;
        this.watchlistService = watchlistService;
    }

    private MarketDataService serviceFor(String market) {
        market = SignalService.normalizeMarket(market);
        if (WatchlistStore.isWatchlist(market)) {
            return watchlistService.marketDataServiceFor(market);
        }
        return "cn".equals(market) ? cn : us;
    }

    private ParamsStore paramsFor(String market) {
        market = SignalService.normalizeMarket(market);
        if (WatchlistStore.isWatchlist(market)) {
            return watchlistService.paramsFor(market);
        }
        return "cn".equals(market) ? cnParams : usParams;
    }

    /**
     * 查询某标的在最新交易日的系统评分。
     * 如果该标的不在横截面、或面板无数据,返回 null(表示无评分)。
     */
    public Double latestScoreFor(String market, String code) {
        try {
            market = SignalService.normalizeMarket(market);
            PanelCache.PanelBundle bundle = panelCache.bundle(market);
            MarketPanel panel = bundle.panel();
            LocalDate day = bundle.latestDay();
            if (panel == null || day == null) {
                return null;
            }
            StrategyParams params = paramsFor(market).load();
            FactorSelector selector = new FactorSelector(params.factorWeights());
            Map<String, Double> scores = selector.scores(panel, day);
            Double v = scores.get(normalizeCode(market, code));
            return (v != null && !Double.isNaN(v)) ? v : null;
        } catch (RuntimeException ignore) {
            return null;
        }
    }

    /**
     * 查询某标的的名称(找不到回退 code 本身)。
     */
    public String nameOf(String market, String code) {
        try {
            MarketDataService svc = serviceFor(market);
            Map<String, String> names = svc.names();
            String n = names == null ? null : names.get(normalizeCode(market, code));
            return n == null ? code : n;
        } catch (RuntimeException ignore) {
            return code;
        }
    }

    private String normalizeCode(String market, String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        String underlying = watchlistService.underlyingMarket(market);
        return "us".equals(underlying) ? s.toUpperCase(Locale.ROOT) : s;
    }

    /**
     * 新增一笔交易日记,自动查询名称与最新系统评分。
     *
     * @return 新记录 id
     */
    public long addEntry(String market,
                         OperationType operationType,
                         String code,
                         double quantity,
                         double price,
                         LocalDateTime tradeDate,
                         String reason,
                         String marketEnv,
                         String notes) {
        market = SignalService.normalizeMarket(market);
        String normalizedCode = normalizeCode(market, code);
        String name = nameOf(market, normalizedCode);
        Double score = latestScoreFor(market, normalizedCode);
        LocalDateTime date = tradeDate != null ? tradeDate : LocalDateTime.now();
        return store.addEntry(market, operationType, normalizedCode, name,
                quantity, price, date, reason, marketEnv, notes, score);
    }

    public void updateNotes(long id, String notes) {
        store.updateNotes(id, notes);
    }

    public void deleteEntry(long id) {
        store.deleteEntry(id);
    }

    public List<JournalEntry> listAll() {
        return store.listAll();
    }

    public List<JournalEntry> listByMarket(String market) {
        if (market == null || market.isBlank()) {
            return store.listAll();
        }
        return store.listByMarket(SignalService.normalizeMarket(market));
    }

    public List<JournalEntry> listByCode(String code) {
        return store.listByCode(code);
    }

    public JournalEntry getById(long id) {
        return store.getById(id);
    }

    /**
     * 页面视图模型,包含记录列表 + 统计摘要。
     */
    public record JournalPageView(String market,
                                  List<JournalEntry> entries,
                                  double totalBuyAmount,
                                  double totalSellAmount,
                                  int buyCount,
                                  int sellCount) {
        public boolean hasEntries() {
            return entries != null && !entries.isEmpty();
        }

        public String netAmountDisplay() {
            double net = totalSellAmount - totalBuyAmount;
            return String.format(Locale.ROOT, "%,.2f", net);
        }

        public String totalBuyDisplay() {
            return String.format(Locale.ROOT, "%,.2f", totalBuyAmount);
        }

        public String totalSellDisplay() {
            return String.format(Locale.ROOT, "%,.2f", totalSellAmount);
        }
    }

    public JournalPageView pageView(String market) {
        List<JournalEntry> entries = listByMarket(market);
        double buyAmt = 0, sellAmt = 0;
        int buyCnt = 0, sellCnt = 0;
        for (JournalEntry e : entries) {
            if (e.operationType() == OperationType.BUY) {
                buyAmt += e.amount();
                buyCnt++;
            } else {
                sellAmt += e.amount();
                sellCnt++;
            }
        }
        return new JournalPageView(market, entries, buyAmt, sellAmt, buyCnt, sellCnt);
    }
}
