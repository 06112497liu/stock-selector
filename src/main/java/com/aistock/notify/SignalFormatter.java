package com.aistock.notify;

import com.aistock.recommend.RecoItem;
import com.aistock.recommend.Recommendation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 把当日选股建议格式化成微信 markdown(纯函数,无 I/O、无状态)。
 *
 * <p>输出对标 Python 版 ServerChan 推送:三段「该买 / 继续持有 / 该卖」纯名单,
 * 配合醒目免责声明。名称缺失时回退显示 code,空段显示「无」。
 */
public final class SignalFormatter {

    private SignalFormatter() {
    }

    private static final String DISCLAIMER =
            "默认策略未经样本外验证,不构成投资建议,仅学习/框架演示";

    /**
     * 生成微信 markdown 正文。
     *
     * @param day   数据最新交易日
     * @param reco  当日建议名单
     * @param names code -> 名称映射(缺失回退 code)
     */
    public static String toMarkdown(LocalDate day, Recommendation reco, Map<String, String> names) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(day).append(" 半自动选股建议(纯名单,人工下单)\n\n");

        section(sb, "## 该买", reco == null ? null : reco.buy(), names, true);
        section(sb, "## 继续持有", reco == null ? null : reco.hold(), names, false);
        section(sb, "## 该卖", reco == null ? null : reco.sell(), names, false);

        sb.append("\n---\n");
        sb.append("> ").append(DISCLAIMER).append("\n");
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, List<RecoItem> items,
                                Map<String, String> names, boolean withRank) {
        sb.append(title).append("\n\n");
        if (items == null || items.isEmpty()) {
            sb.append("无\n\n");
            return;
        }
        int rank = 1;
        for (RecoItem item : items) {
            sb.append("- ");
            if (withRank) {
                sb.append(rank++).append(". ");
            }
            sb.append(item.code()).append(' ').append(nameOf(names, item.code()))
                    .append(" | 现价 ").append(fmt(item.price()))
                    .append(" | score ").append(fmt(item.score()))
                    .append(" | ").append(item.reason());
            if (item.held()) {
                sb.append("(已持有)");
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

    /**
     * 生成推送标题,如「6/5 选股:买3 卖1」。
     */
    public static String pushTitle(LocalDate day, Recommendation reco) {
        int buy = reco == null || reco.buy() == null ? 0 : reco.buy().size();
        int sell = reco == null || reco.sell() == null ? 0 : reco.sell().size();
        return day.getMonthValue() + "/" + day.getDayOfMonth()
                + " 选股:买" + buy + " 卖" + sell;
    }

    private static String nameOf(Map<String, String> names, String code) {
        String n = names == null ? null : names.get(code);
        return n == null ? code : n;
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "N/A" : String.format("%.2f", v);
    }
}
