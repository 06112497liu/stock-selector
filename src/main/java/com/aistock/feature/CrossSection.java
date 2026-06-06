package com.aistock.feature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 横截面标准化(对标 Python 版 core/features/cross_section)。
 *
 * <p>输入是某一交易日全市场某因子的取值 {@code Map<股票代码, 因子值>},
 * 输出是同一批股票标准化后的取值。NaN(缺失)统一保留为 NaN,不参与统计、
 * 也不被赋值。
 *
 * 全部为 static 方法,无状态。
 */
public final class CrossSection {

    private CrossSection() {
    }

    /**
     * 名次归一化(rank normalize):把横截面名次线性映射到 {@code [-0.5, 0.5]}。
     *
     * <p>关键口径(Python 版踩过的坑):<b>不能用百分位 rank</b>(那样端点到不了
     * ±0.5)。这里用名次公式 {@code (rank - 1) / (k - 1) - 0.5},其中:
     * <ul>
     *   <li>{@code rank} 是 1..k 的名次,最小值名次为 1,最大值名次为 k;</li>
     *   <li>并列(相等)取<b>平均名次</b>;</li>
     *   <li>{@code k} 是非 NaN 元素个数。</li>
     * </ul>
     * 于是最小值映射到 -0.5,最大值映射到 +0.5。
     *
     * <p>当 {@code k <= 1}(没有可比较的对手)时,该值返回 0.0。NaN 保留为 NaN。
     *
     * @param values 横截面因子值
     * @return 归一化后的 map(保持原插入顺序,key 集合不变)
     */
    public static Map<String, Double> rankNormalize(Map<String, Double> values) {
        Map<String, Double> out = new LinkedHashMap<>();

        // 收集非 NaN 的有效项
        List<Map.Entry<String, Double>> valid = new ArrayList<>();
        for (Map.Entry<String, Double> e : values.entrySet()) {
            Double v = e.getValue();
            if (v != null && !Double.isNaN(v)) {
                valid.add(e);
            }
        }
        int k = valid.size();

        // 计算每个有效 key 的平均名次(1..k)
        Map<String, Double> ranks = new LinkedHashMap<>();
        if (k > 0) {
            // 按值升序排序
            List<Map.Entry<String, Double>> sorted = new ArrayList<>(valid);
            sorted.sort(Comparator.comparingDouble(Map.Entry::getValue));
            int i = 0;
            while (i < k) {
                int j = i;
                // 找到与 sorted[i] 值相等的一段 [i, j]
                while (j + 1 < k && sorted.get(j + 1).getValue().doubleValue()
                        == sorted.get(i).getValue().doubleValue()) {
                    j++;
                }
                // 名次从 1 开始:位置 p(0-based)对应名次 p+1;平均名次
                double avgRank = ((i + 1) + (j + 1)) / 2.0;
                for (int p = i; p <= j; p++) {
                    ranks.put(sorted.get(p).getKey(), avgRank);
                }
                i = j + 1;
            }
        }

        for (Map.Entry<String, Double> e : values.entrySet()) {
            Double v = e.getValue();
            if (v == null || Double.isNaN(v)) {
                out.put(e.getKey(), Double.NaN);
            } else if (k <= 1) {
                out.put(e.getKey(), 0.0);
            } else {
                double rank = ranks.get(e.getKey());
                out.put(e.getKey(), (rank - 1.0) / (k - 1.0) - 0.5);
            }
        }
        return out;
    }

    /**
     * z-score 标准化:减去横截面均值,再除以<b>总体标准差</b>
     * (除以非 NaN 元素个数 k,ddof=0;存在 NaN 时 k 小于 map 大小)。
     *
     * <p>统计量只在非 NaN 元素上计算。当总体标准差为 0(所有有效值相等)时,
     * 只做去均值(此时各有效值结果均为 0.0,避免除零)。NaN 保留为 NaN。
     *
     * @param values 横截面因子值
     * @return 标准化后的 map(保持原插入顺序,key 集合不变)
     */
    public static Map<String, Double> zscore(Map<String, Double> values) {
        Map<String, Double> out = new LinkedHashMap<>();

        List<Double> valid = new ArrayList<>();
        for (Double v : values.values()) {
            if (v != null && !Double.isNaN(v)) {
                valid.add(v);
            }
        }
        int k = valid.size();

        double mean = 0.0;
        double std = 0.0;
        if (k > 0) {
            double sum = 0.0;
            for (double v : valid) {
                sum += v;
            }
            mean = sum / k;
            double sq = 0.0;
            for (double v : valid) {
                double d = v - mean;
                sq += d * d;
            }
            // 总体标准差:除以 k
            std = Math.sqrt(sq / k);
        }

        for (Map.Entry<String, Double> e : values.entrySet()) {
            Double v = e.getValue();
            if (v == null || Double.isNaN(v)) {
                out.put(e.getKey(), Double.NaN);
            } else if (std == 0.0) {
                out.put(e.getKey(), v - mean);
            } else {
                out.put(e.getKey(), (v - mean) / std);
            }
        }
        return out;
    }
}
