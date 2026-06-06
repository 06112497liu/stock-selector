package com.aistock.feature;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossSectionTest {

    private static final double DELTA = 1e-9;

    @Test
    void rankNormalizeEndpointsAndMidpoint() {
        Map<String, Double> in = new LinkedHashMap<>();
        in.put("a", 5.0);
        in.put("b", 1.0);
        in.put("c", 3.0);

        Map<String, Double> out = CrossSection.rankNormalize(in);
        // b 最小 -> -0.5, a 最大 -> 0.5, c 中间 -> 0.0
        assertEquals(0.5, out.get("a"), DELTA);
        assertEquals(-0.5, out.get("b"), DELTA);
        assertEquals(0.0, out.get("c"), DELTA);
    }

    @Test
    void rankNormalizePreservesOrder() {
        Map<String, Double> in = new LinkedHashMap<>();
        in.put("a", 5.0);
        in.put("b", 1.0);
        in.put("c", 3.0);
        in.put("d", 9.0);

        Map<String, Double> out = CrossSection.rankNormalize(in);
        // 原序 b < c < a < d,归一化后保序
        assertTrue(out.get("b") < out.get("c"));
        assertTrue(out.get("c") < out.get("a"));
        assertTrue(out.get("a") < out.get("d"));
        // 端点恰好 ±0.5
        assertEquals(-0.5, out.get("b"), DELTA);
        assertEquals(0.5, out.get("d"), DELTA);
    }

    @Test
    void rankNormalizeKeepsNaN() {
        Map<String, Double> in = new LinkedHashMap<>();
        in.put("a", 5.0);
        in.put("b", Double.NaN);
        in.put("c", 3.0);

        Map<String, Double> out = CrossSection.rankNormalize(in);
        assertTrue(Double.isNaN(out.get("b")), "NaN 键应保留 NaN");
        // 剩余 2 个有效:c 最小 -> -0.5, a 最大 -> 0.5
        assertEquals(0.5, out.get("a"), DELTA);
        assertEquals(-0.5, out.get("c"), DELTA);
    }

    @Test
    void rankNormalizeSingleValidReturnsZero() {
        Map<String, Double> in = new LinkedHashMap<>();
        in.put("a", 7.0);
        in.put("b", Double.NaN);

        Map<String, Double> out = CrossSection.rankNormalize(in);
        // k=1 时该值返回 0.0
        assertEquals(0.0, out.get("a"), DELTA);
        assertTrue(Double.isNaN(out.get("b")));
    }

    @Test
    void rankNormalizeTiesUseAverageRank() {
        Map<String, Double> in = new LinkedHashMap<>();
        in.put("a", 2.0);
        in.put("b", 2.0);
        in.put("c", 5.0);
        in.put("d", 1.0);

        Map<String, Double> out = CrossSection.rankNormalize(in);
        // 升序:d(1), a(2), b(2), c(5);k=4
        // d 名次1 -> (1-1)/3-0.5 = -0.5
        // a,b 并列名次(2+3)/2=2.5 -> (2.5-1)/3-0.5 = 0.0
        // c 名次4 -> (4-1)/3-0.5 = 0.5
        assertEquals(-0.5, out.get("d"), DELTA);
        assertEquals(0.0, out.get("a"), DELTA);
        assertEquals(0.0, out.get("b"), DELTA);
        assertEquals(0.5, out.get("c"), DELTA);
    }

    @Test
    void zscoreMeanZeroStdOne() {
        Map<String, Double> in = new LinkedHashMap<>();
        in.put("a", 1.0);
        in.put("b", 2.0);
        in.put("c", 3.0);
        in.put("d", 4.0);

        Map<String, Double> out = CrossSection.zscore(in);

        // 均值 ≈ 0
        double sum = 0.0;
        for (double v : out.values()) {
            sum += v;
        }
        double mean = sum / out.size();
        assertEquals(0.0, mean, DELTA);

        // 总体标准差 ≈ 1
        double sq = 0.0;
        for (double v : out.values()) {
            sq += (v - mean) * (v - mean);
        }
        double std = Math.sqrt(sq / out.size());
        assertEquals(1.0, std, DELTA);
    }

    @Test
    void zscoreKeepsNaN() {
        Map<String, Double> in = new LinkedHashMap<>();
        in.put("a", 1.0);
        in.put("b", Double.NaN);
        in.put("c", 3.0);

        Map<String, Double> out = CrossSection.zscore(in);
        assertTrue(Double.isNaN(out.get("b")), "NaN 键应保留 NaN");

        // 有效值 [1,3] 均值2,总体std=1 -> a=-1, c=1
        assertEquals(-1.0, out.get("a"), DELTA);
        assertEquals(1.0, out.get("c"), DELTA);
    }

    @Test
    void zscoreZeroStdReturnsDemeaned() {
        Map<String, Double> in = new LinkedHashMap<>();
        in.put("a", 4.0);
        in.put("b", 4.0);
        in.put("c", 4.0);

        Map<String, Double> out = CrossSection.zscore(in);
        // std=0 -> 去均值,各值 = 0.0
        for (double v : out.values()) {
            assertEquals(0.0, v, DELTA);
        }
    }

    @Test
    void rankNormalizeAllNaNKeepsNaN() {
        // k=0:每个键都保留 NaN
        Map<String, Double> in = new LinkedHashMap<>();
        in.put("a", Double.NaN);
        in.put("b", Double.NaN);

        Map<String, Double> out = CrossSection.rankNormalize(in);
        assertEquals(in.keySet(), out.keySet());
        assertTrue(Double.isNaN(out.get("a")));
        assertTrue(Double.isNaN(out.get("b")));
    }

    @Test
    void rankNormalizeEmptyMapReturnsEmpty() {
        Map<String, Double> out = CrossSection.rankNormalize(new LinkedHashMap<>());
        assertTrue(out.isEmpty());
    }

    @Test
    void zscoreAllNaNKeepsNaN() {
        // k=0:每个键都保留 NaN
        Map<String, Double> in = new LinkedHashMap<>();
        in.put("a", Double.NaN);
        in.put("b", Double.NaN);

        Map<String, Double> out = CrossSection.zscore(in);
        assertEquals(in.keySet(), out.keySet());
        assertTrue(Double.isNaN(out.get("a")));
        assertTrue(Double.isNaN(out.get("b")));
    }

    @Test
    void zscoreEmptyMapReturnsEmpty() {
        Map<String, Double> out = CrossSection.zscore(new LinkedHashMap<>());
        assertTrue(out.isEmpty());
    }
}
