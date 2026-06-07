package com.aistock.web;

import com.aistock.storage.WatchlistStore.WatchlistGroup;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

@ControllerAdvice
public class WatchlistAdvice {

    private final WatchlistService watchlistService;

    public WatchlistAdvice(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @ModelAttribute
    public void addWatchlistGroups(Model model) {
        List<WatchlistGroup> groups = watchlistService.listGroups();
        model.addAttribute("watchlistGroups", groups);
        // watchlists.html 和其它没显式传 market 的页面给个默认,顶栏切换需要它
        if (!model.asMap().containsKey("market")) {
            model.addAttribute("market", "us");
        }
    }
}

