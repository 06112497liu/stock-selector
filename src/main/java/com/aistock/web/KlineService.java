package com.aistock.web;

import com.aistock.datasource.KlinePeriod;
import com.aistock.datasource.KlinePoint;
import com.aistock.datasource.KlineSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 个股 K 线门面:按 market 分流到对应 {@link KlineSource}(美股 Yahoo / A 股东财),
 * 拉取指定周期的 K 线供行情图展示。
 *
 * <p>不持有状态、不缓存(K 线按需拉,数据量小、周期可频繁切换);任何数据源
 * 失败已在 {@link KlineSource} 内降级为空 List,本门面同样<b>绝不抛</b>。
 */
@Service
public class KlineService {

    private final KlineSource usKlineSource;
    private final KlineSource cnKlineSource;
    private final WatchlistService watchlistService;

    public KlineService(@Qualifier("usKlineSource") KlineSource usKlineSource,
                        @Qualifier("cnKlineSource") KlineSource cnKlineSource,
                        WatchlistService watchlistService) {
        this.usKlineSource = usKlineSource;
        this.cnKlineSource = cnKlineSource;
        this.watchlistService = watchlistService;
    }

    public List<KlinePoint> kline(String market, String code, String periodCode) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        KlinePeriod period = KlinePeriod.fromCode(periodCode);
        String normalized = SignalService.normalizeMarket(market);
        String underlying = watchlistService == null ? normalized : watchlistService.underlyingMarket(normalized);
        KlineSource source = "cn".equals(underlying) ? cnKlineSource : usKlineSource;
        return source.fetchKline(code.trim(), period);
    }
}
