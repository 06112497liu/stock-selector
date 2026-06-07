package com.aistock.web;

import com.aistock.storage.WatchlistStore;
import com.aistock.storage.WatchlistStore.WatchlistGroup;
import com.aistock.storage.WatchlistStore.WatchlistStock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    /** 分组详情(给模板用):group + stocks。 */
    public record GroupDetail(WatchlistGroup group, List<WatchlistStock> stocks) {
    }

    @GetMapping("/watchlists")
    public String list(@RequestParam(name = "msg", required = false) String msg,
                       @RequestParam(name = "err", required = false) String err,
                       Model model) {
        List<WatchlistGroup> groups = watchlistService.listGroups();
        List<GroupDetail> details = new ArrayList<>();
        for (WatchlistGroup g : groups) {
            details.add(new GroupDetail(g, watchlistService.listStocks(g.groupId())));
        }
        model.addAttribute("groups", details);
        model.addAttribute("msg", msg);
        model.addAttribute("err", err);
        return "watchlists";
    }

    @PostMapping("/watchlists/create")
    public String createGroup(@RequestParam(name = "groupName") String groupName,
                              @RequestParam(name = "marketType", defaultValue = "us") String marketType) {
        String safeName = groupName == null ? "" : groupName.trim();
        if (safeName.isEmpty()) {
            return "redirect:/watchlists?err=" + encode("分组名不能为空");
        }
        try {
            String id = watchlistService.createGroup(safeName, marketType);
            return "redirect:/watchlists?msg=" + encode("已创建分组:" + safeName);
        } catch (IllegalArgumentException e) {
            return "redirect:/watchlists?err=" + encode(e.getMessage());
        }
    }

    @PostMapping("/watchlists/rename")
    public String renameGroup(@RequestParam(name = "groupId") String groupId,
                              @RequestParam(name = "newName") String newName) {
        try {
            watchlistService.renameGroup(groupId, newName);
            return "redirect:/watchlists?msg=" + encode("分组已重命名");
        } catch (IllegalArgumentException e) {
            return "redirect:/watchlists?err=" + encode(e.getMessage());
        }
    }

    @PostMapping("/watchlists/delete")
    public String deleteGroup(@RequestParam(name = "groupId") String groupId) {
        watchlistService.deleteGroup(groupId);
        return "redirect:/watchlists?msg=" + encode("分组已删除");
    }

    @PostMapping("/watchlists/addStock")
    public String addStock(@RequestParam(name = "groupId") String groupId,
                           @RequestParam(name = "code") String code) {
        String safeCode = code == null ? "" : code.trim();
        if (safeCode.isEmpty()) {
            return "redirect:/watchlists?err=" + encode("股票代码不能为空");
        }
        try {
            String normalized = watchlistService.addStock(groupId, safeCode);
            return "redirect:/watchlists?msg=" + encode("已加入:" + normalized);
        } catch (IllegalArgumentException e) {
            return "redirect:/watchlists?err=" + encode(e.getMessage());
        }
    }

    @PostMapping("/watchlists/removeStock")
    public String removeStock(@RequestParam(name = "groupId") String groupId,
                              @RequestParam(name = "code") String code) {
        watchlistService.removeStock(groupId, code);
        return "redirect:/watchlists?msg=" + encode("已移除:" + code);
    }

    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
