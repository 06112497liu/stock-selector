package com.aistock.notify;

import com.aistock.recommend.RecoItem;
import com.aistock.recommend.Recommendation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SignalFormatter} 纯函数测试:三段标题、名称映射、缺名回退 code、空段「无」、免责文案、标题计数。
 */
class SignalFormatterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 5);

    private static Recommendation reco() {
        return new Recommendation(
                List.of(
                        new RecoItem("AAPL", 180.0, 1.23, "Top1 选中", false),
                        new RecoItem("MSFT", 400.0, 0.88, "已持有(可加仓)", true)),
                List.of(new RecoItem("NVDA", 900.0, 0.55, "继续持有", true)),
                List.of(new RecoItem("TSLA", 200.0, -0.30, "打分转负", true)));
    }

    @Test
    void toMarkdownHasAllSectionsNamesAndDisclaimer() {
        // AAPL/MSFT 有名称,NVDA/TSLA 故意缺名 -> 回退 code
        Map<String, String> names = Map.of("AAPL", "苹果", "MSFT", "微软");
        String md = SignalFormatter.toMarkdown(DAY, reco(), names);

        assertTrue(md.contains("## 该买"), "应含该买段");
        assertTrue(md.contains("## 继续持有"), "应含继续持有段");
        assertTrue(md.contains("## 该卖"), "应含该卖段");

        assertTrue(md.contains("苹果"), "AAPL 名称应映射为苹果");
        assertTrue(md.contains("微软"), "MSFT 名称应映射为微软");
        assertTrue(md.contains("NVDA"), "NVDA 缺名应回退显示 code");
        assertTrue(md.contains("TSLA"), "TSLA 缺名应回退显示 code");

        assertTrue(md.contains("(已持有)"), "已持有项应标注");
        assertTrue(md.contains("默认策略未经样本外验证,不构成投资建议,仅学习/框架演示"),
                "应含免责文案");
    }

    @Test
    void emptySectionsShowWu() {
        Recommendation empty = new Recommendation(List.of(), List.of(), List.of());
        String md = SignalFormatter.toMarkdown(DAY, empty, Map.of());
        // 三段都应有「无」
        long count = md.lines().filter(l -> l.trim().equals("无")).count();
        assertTrue(count >= 3, "三个空段都应显示「无」,实际行数=" + count);
    }

    @Test
    void pushTitleContainsBuySellCounts() {
        String title = SignalFormatter.pushTitle(DAY, reco());
        assertTrue(title.contains("买2"), "标题应含买入计数: " + title);
        assertTrue(title.contains("卖1"), "标题应含卖出计数: " + title);
        assertTrue(title.contains("6/5"), "标题应含日期: " + title);
    }
}
