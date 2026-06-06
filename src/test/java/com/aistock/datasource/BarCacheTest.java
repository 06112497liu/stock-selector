package com.aistock.datasource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarCacheTest {

    @TempDir
    Path tmp;

    private BarCache newCache() {
        return new BarCache(tmp.resolve("bars.db").toString());
    }

    @Test
    void appendThenLoadRoundTripsInDateOrder() {
        BarCache cache = newCache();

        Bar b1 = new Bar(LocalDate.of(2020, 1, 2), 10, 11, 9, 10.5, 1000);
        Bar b2 = new Bar(LocalDate.of(2020, 1, 3), 10.5, 12, 10, 11.5, 2000);
        // Append out of order to verify ordering on load.
        cache.append("AAPL", List.of(b2, b1));

        List<Bar> loaded = cache.load("AAPL");

        assertEquals(2, loaded.size());
        assertEquals(b1, loaded.get(0));
        assertEquals(b2, loaded.get(1));
    }

    @Test
    void appendUpsertsOnSameCodeAndDate() {
        BarCache cache = newCache();
        LocalDate day = LocalDate.of(2020, 1, 2);

        // Intraday snapshot.
        cache.append("AAPL", List.of(new Bar(day, 10, 11, 9, 10.2, 500)));
        // Settled bar for the same day must overwrite.
        Bar settled = new Bar(day, 10, 11.5, 8.9, 10.8, 1500);
        cache.append("AAPL", List.of(settled));

        List<Bar> loaded = cache.load("AAPL");

        assertEquals(1, loaded.size());
        assertEquals(settled, loaded.get(0));
    }

    @Test
    void lastDateReflectsMaxOrEmpty() {
        BarCache cache = newCache();

        assertEquals(Optional.empty(), cache.lastDate("AAPL"));

        cache.append("AAPL", List.of(
                new Bar(LocalDate.of(2020, 1, 2), 1, 1, 1, 1, 1),
                new Bar(LocalDate.of(2020, 1, 6), 1, 1, 1, 1, 1),
                new Bar(LocalDate.of(2020, 1, 3), 1, 1, 1, 1, 1)
        ));

        Optional<LocalDate> last = cache.lastDate("AAPL");
        assertTrue(last.isPresent());
        assertEquals(LocalDate.of(2020, 1, 6), last.get());
    }

    @Test
    void loadEmptyForUnknownCode() {
        BarCache cache = newCache();
        assertTrue(cache.load("NOPE").isEmpty());
    }

    /**
     * 多线程同时 append 不同 code 到同一 db 文件:验证每操作独立连接 +
     * busy_timeout 下并发写不抛异常、数据完整。
     */
    @Test
    void concurrentAppendsToSameFileAreSafe() throws InterruptedException {
        BarCache cache = newCache();
        int codeCount = 16;
        int barsPerCode = 30;

        List<Throwable> errors = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(codeCount);

        for (int c = 0; c < codeCount; c++) {
            final String code = "C" + c;
            pool.submit(() -> {
                try {
                    start.await(); // 尽量同时起跑,放大竞争
                    List<Bar> bars = new ArrayList<>(barsPerCode);
                    LocalDate d = LocalDate.of(2021, 1, 1);
                    for (int i = 0; i < barsPerCode; i++) {
                        double v = i + 1;
                        bars.add(new Bar(d.plusDays(i), v, v + 1, v - 1, v, 100 + i));
                    }
                    cache.append(code, bars);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发 append 超时");
        pool.shutdown();

        assertTrue(errors.isEmpty(), "并发 append 出现异常: " + errors);
        // 每个 code 的所有 bar 都完整写入。
        for (int c = 0; c < codeCount; c++) {
            assertEquals(barsPerCode, cache.load("C" + c).size(),
                    "C" + c + " 写入不完整");
        }
    }
}
