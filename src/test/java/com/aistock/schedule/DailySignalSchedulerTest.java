package com.aistock.schedule;

import com.aistock.web.PanelCache;
import com.aistock.web.SignalService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link DailySignalScheduler} 单测:不联网、不真推送。用手写 stub 替身记录调用,
 * 直接调 {@link DailySignalScheduler#runOnce()} 断言遍历、容错、摘要收集。
 */
class DailySignalSchedulerTest {

    /** 记录 invalidate 调用顺序的 PanelCache 替身(用可注入时钟的测试构造,传 null source 不触网)。 */
    static final class StubPanelCache extends PanelCache {
        final List<String> invalidated = new ArrayList<>();

        StubPanelCache() {
            super(null, null, null, 1800, () -> 0L);
        }

        @Override
        public void invalidate(String market) {
            invalidated.add(market);
        }
    }

    /** 记录 push 调用、可对指定 market 抛异常或返回指定结果的 SignalService 替身。 */
    static final class StubSignalService extends SignalService {
        final List<String> pushed = new ArrayList<>();
        final String throwForMarket;
        final SignalService.PushResult result;

        StubSignalService(SignalService.PushResult result, String throwForMarket) {
            super(null, null, null, null, null, null, null);
            this.result = result;
            this.throwForMarket = throwForMarket;
        }

        @Override
        public PushResult push(String market) {
            pushed.add(market);
            if (market.equals(throwForMarket)) {
                throw new RuntimeException("模拟拉取/推送失败 for " + market);
            }
            return result;
        }
    }

    private DailySignalScheduler scheduler(StubSignalService signal, StubPanelCache cache, String marketsCsv) {
        return new DailySignalScheduler(signal, cache, marketsCsv);
    }

    @Test
    void runOnce_invalidatesAndPushes_eachMarket() {
        StubSignalService signal = new StubSignalService(SignalService.PushResult.SUCCESS, null);
        StubPanelCache cache = new StubPanelCache();
        DailySignalScheduler sched = scheduler(signal, cache, "us,cn");

        Map<String, String> summary = sched.runOnce();

        assertEquals(List.of("us", "cn"), cache.invalidated, "每个 market 都应先 invalidate");
        assertEquals(List.of("us", "cn"), signal.pushed, "每个 market 都应 push");
        assertEquals(2, summary.size());
        assertEquals(SignalService.PushResult.SUCCESS.message(), summary.get("us"));
        assertEquals(SignalService.PushResult.SUCCESS.message(), summary.get("cn"),
                "push 返回结果应被收集进摘要");
    }

    @Test
    void runOnce_isFaultTolerant_oneMarketFailureDoesNotStopOthers() {
        // us 的 push 抛异常,cn 应仍被处理,runOnce 不抛,摘要含两者。
        StubSignalService signal = new StubSignalService(SignalService.PushResult.SUCCESS, "us");
        StubPanelCache cache = new StubPanelCache();
        DailySignalScheduler sched = scheduler(signal, cache, "us,cn");

        Map<String, String> summary = assertDoesNotThrow(sched::runOnce,
                "单个 market 失败不应让 runOnce 抛异常");

        assertEquals(List.of("us", "cn"), signal.pushed, "us 失败后 cn 仍应被执行");
        assertEquals(2, summary.size(), "摘要应含两个 market");
        assertTrue(summary.get("us").startsWith("失败:"), "us 应标记为失败");
        assertEquals(SignalService.PushResult.SUCCESS.message(), summary.get("cn"),
                "cn 应标记为成功");
    }

    @Test
    void runOnce_notConfigured_recordedNotErrored() {
        // Server酱未配 sendKey → push 返回 NOT_CONFIGURED,按结果记录,不当失败。
        StubSignalService signal = new StubSignalService(SignalService.PushResult.NOT_CONFIGURED, null);
        StubPanelCache cache = new StubPanelCache();
        DailySignalScheduler sched = scheduler(signal, cache, "us");

        Map<String, String> summary = sched.runOnce();

        assertEquals(SignalService.PushResult.NOT_CONFIGURED.message(), summary.get("us"));
        assertFalse(summary.get("us").startsWith("失败:"), "未配置应是正常结果,不是异常失败");
    }

    @Test
    void parseMarkets_normalizesDedupesAndSkipsBlanks() {
        StubSignalService signal = new StubSignalService(SignalService.PushResult.SUCCESS, null);
        StubPanelCache cache = new StubPanelCache();
        DailySignalScheduler sched = scheduler(signal, cache, " us , cn , us , ");

        assertEquals(List.of("us", "cn"), sched.markets(), "应规整、去重、去空白项");
    }
}
