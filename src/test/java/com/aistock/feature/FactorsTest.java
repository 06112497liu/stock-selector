package com.aistock.feature;

import com.aistock.datasource.Bar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactorsTest {

    private static final double DELTA = 1e-9;

    /** 用一串 close 价构造等长的 bar 列表(日期从 2020-01-01 起递增)。 */
    private static List<Bar> barsOf(double... closes) {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2020, 1, 1);
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            bars.add(new Bar(d.plusDays(i), c, c, c, c, 1000L));
        }
        return bars;
    }

    @Test
    void momentumHandComputed() {
        // close=[10,11,12], window=2
        List<Bar> bars = barsOf(10, 11, 12);
        double[] mom = Factors.momentum(bars, 2);

        assertEquals(3, mom.length);
        assertTrue(Double.isNaN(mom[0]), "index0 应为 NaN");
        assertTrue(Double.isNaN(mom[1]), "index1 应为 NaN");
        // index2 = 12/10 - 1 = 0.2
        assertFalse(Double.isNaN(mom[2]));
        assertEquals(0.2, mom[2], DELTA);
    }

    @Test
    void reversalIsNegatedMomentum() {
        List<Bar> bars = barsOf(10, 11, 12, 9, 15);
        int window = 2;
        double[] mom = Factors.momentum(bars, window);
        double[] rev = Factors.reversal(bars, window);

        assertEquals(mom.length, rev.length);
        for (int i = 0; i < mom.length; i++) {
            if (Double.isNaN(mom[i])) {
                assertTrue(Double.isNaN(rev[i]), "NaN 位置反转仍应为 NaN, i=" + i);
            } else {
                assertEquals(-mom[i], rev[i], DELTA, "i=" + i);
            }
        }
    }

    @Test
    void volatilityNonNegativeAndNaNWhenInsufficient() {
        // 8 个 close,波动率窗口=3(需要 3 个收益率 => 至少 4 个 close)
        List<Bar> bars = barsOf(10, 11, 10.5, 12, 11.8, 13, 12.5, 14);
        int window = 3;
        double[] vol = Factors.volatility(bars, window);

        assertEquals(bars.size(), vol.length);
        // 前 window 个位置(index 0..2)数据不足 => NaN
        for (int i = 0; i < window; i++) {
            assertTrue(Double.isNaN(vol[i]), "数据不足处应为 NaN, i=" + i);
        }
        // 之后应为非负实数
        for (int i = window; i < vol.length; i++) {
            assertFalse(Double.isNaN(vol[i]), "i=" + i + " 不应为 NaN");
            assertTrue(vol[i] >= 0.0, "波动率应非负, i=" + i + " 值=" + vol[i]);
        }
    }

    @Test
    void volatilityHandComputedSimpleCase() {
        // close=[10, 11, 12], window=2 => returns = [NaN, 0.1, 12/11-1]
        // 唯一有效窗口在 index2: returns[1]=0.1, returns[2]=0.0909090909...
        List<Bar> bars = barsOf(10, 11, 12);
        double[] vol = Factors.volatility(bars, 2);
        assertTrue(Double.isNaN(vol[0]));
        assertTrue(Double.isNaN(vol[1]));
        double r1 = 0.1;
        double r2 = 12.0 / 11.0 - 1.0;
        double mean = (r1 + r2) / 2.0;
        double sample = Math.sqrt(((r1 - mean) * (r1 - mean) + (r2 - mean) * (r2 - mean)) / (2 - 1));
        assertEquals(sample, vol[2], DELTA);
    }

    @Test
    void momentumEmptyListReturnsEmptyArray() {
        double[] mom = Factors.momentum(new ArrayList<>(), 2);
        assertEquals(0, mom.length);
    }

    @Test
    void volatilityEmptyListReturnsEmptyArray() {
        double[] vol = Factors.volatility(new ArrayList<>(), 3);
        assertEquals(0, vol.length);
    }

    @Test
    void momentumNonPositiveWindowThrows() {
        List<Bar> bars = barsOf(10, 11, 12);
        assertThrows(IllegalArgumentException.class, () -> Factors.momentum(bars, 0));
        assertThrows(IllegalArgumentException.class, () -> Factors.momentum(bars, -1));
    }

    @Test
    void volatilityWindowBelowTwoThrows() {
        List<Bar> bars = barsOf(10, 11, 12);
        assertThrows(IllegalArgumentException.class, () -> Factors.volatility(bars, 1));
        assertThrows(IllegalArgumentException.class, () -> Factors.volatility(bars, 0));
    }

    @Test
    void volatilityAllNaNWhenWindowAtLeastSize() {
        // size=4,window=4(>=size):任一位置都凑不齐 window 个有效收益率 => 全 NaN
        List<Bar> bars = barsOf(10, 11, 12, 13);
        double[] vol = Factors.volatility(bars, 4);
        assertEquals(bars.size(), vol.length);
        for (int i = 0; i < vol.length; i++) {
            assertTrue(Double.isNaN(vol[i]), "window>=size 时应全部为 NaN, i=" + i);
        }
        // window=5(>size)同样全 NaN
        double[] vol2 = Factors.volatility(bars, 5);
        for (int i = 0; i < vol2.length; i++) {
            assertTrue(Double.isNaN(vol2[i]), "window>size 时应全部为 NaN, i=" + i);
        }
    }

    @Test
    void volatilityHandComputedRollingPointwise() {
        // close 长度 6,window=3。收益率 r[i]=close[i]/close[i-1]-1,r[0]=NaN。
        double[] closes = {10, 11, 10.5, 12, 11.8, 13};
        List<Bar> bars = barsOf(closes);
        int window = 3;
        double[] vol = Factors.volatility(bars, window);
        assertEquals(closes.length, vol.length);

        // index 0..2 数据不足(凑不齐 3 个有效收益率)=> NaN
        assertTrue(Double.isNaN(vol[0]));
        assertTrue(Double.isNaN(vol[1]));
        assertTrue(Double.isNaN(vol[2]));

        // 逐点手算:对每个有效位置 i,窗口为 returns[i-2..i]
        double[] r = new double[closes.length];
        r[0] = Double.NaN;
        for (int i = 1; i < closes.length; i++) {
            r[i] = closes[i] / closes[i - 1] - 1.0;
        }
        for (int i = window; i < closes.length; i++) {
            double r1 = r[i - 2];
            double r2 = r[i - 1];
            double r3 = r[i];
            double mean = (r1 + r2 + r3) / 3.0;
            // 样本标准差 ddof=1:除以 window-1 = 2
            double sample = Math.sqrt(
                    ((r1 - mean) * (r1 - mean)
                            + (r2 - mean) * (r2 - mean)
                            + (r3 - mean) * (r3 - mean)) / (window - 1));
            assertEquals(sample, vol[i], DELTA, "逐点滚动样本std不符, i=" + i);
        }
    }
}
