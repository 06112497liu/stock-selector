package com.aistock.web.config;

import com.aistock.datasource.EastMoneyClient;
import com.aistock.datasource.YahooClient;
import com.aistock.notify.ServerChanNotifier;
import com.aistock.storage.*;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;

@Configuration
public class HealthConfig {

    @Bean
    public HealthIndicator sqliteHealthIndicator(MarketProperties props) {
        return () -> {
            try {
                String[] dbFiles = {
                        "us.sqlite", "cn.sqlite",
                        "us_ledger.sqlite", "cn_ledger.sqlite",
                        "us_params.sqlite", "cn_params.sqlite",
                        "watchlists.sqlite", "trading_journal.sqlite"
                };
                StringBuilder details = new StringBuilder();
                boolean allHealthy = true;
                java.nio.file.Path cacheDir = java.nio.file.Path.of(props.getCacheDir());

                for (String dbFile : dbFiles) {
                    String dbPath = cacheDir.resolve(dbFile).toString();
                    String url = "jdbc:sqlite:" + dbPath;
                    try (Connection conn = DriverManager.getConnection(url);
                         Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery("SELECT 1")) {
                        if (rs.next() && rs.getInt(1) == 1) {
                            details.append(dbFile).append("=OK; ");
                        } else {
                            details.append(dbFile).append("=NO_RESULT; ");
                            allHealthy = false;
                        }
                    } catch (Exception e) {
                        details.append(dbFile).append("=ERROR(").append(e.getMessage()).append("); ");
                        allHealthy = false;
                    }
                }

                if (allHealthy) {
                    return Health.up()
                            .withDetail("databases", details.toString().trim())
                            .withDetail("cacheDir", props.getCacheDir())
                            .build();
                } else {
                    return Health.down()
                            .withDetail("databases", details.toString().trim())
                            .withDetail("cacheDir", props.getCacheDir())
                            .build();
                }
            } catch (Exception e) {
                return Health.down(e).build();
            }
        };
    }

    @Bean
    public HealthIndicator yahooFinanceHealthIndicator(YahooClient yahooClient) {
        return () -> {
            try {
                List<com.aistock.datasource.Bar> bars = yahooClient.fetchDaily(
                        "AAPL",
                        LocalDate.now().minusDays(10),
                        LocalDate.now()
                );
                OptionalDouble marketCap = yahooClient.fetchMarketCap("AAPL");

                Health.Builder builder = Health.up()
                        .withDetail("dataPointsFetched", bars.size())
                        .withDetail("marketCapAvailable", marketCap.isPresent());

                if (marketCap.isPresent()) {
                    builder.withDetail("sampleMarketCapUSD", marketCap.getAsDouble());
                }
                if (!bars.isEmpty()) {
                    builder.withDetail("latestDate", bars.get(bars.size() - 1).date().toString());
                }

                if (bars.isEmpty()) {
                    builder.status(new Status("UNKNOWN", "No data returned - may be outside trading hours"));
                }

                return builder.build();
            } catch (Exception e) {
                return Health.down()
                        .withDetail("error", e.getMessage())
                        .withException(e)
                        .build();
            }
        };
    }

    @Bean
    public HealthIndicator eastMoneyHealthIndicator(EastMoneyClient eastMoneyClient) {
        return () -> {
            try {
                List<com.aistock.datasource.Bar> bars = eastMoneyClient.fetchDaily(
                        "600519",
                        LocalDate.now().minusDays(10),
                        LocalDate.now()
                );
                OptionalDouble marketCap = eastMoneyClient.fetchMarketCap("600519");

                Health.Builder builder = Health.up()
                        .withDetail("dataPointsFetched", bars.size())
                        .withDetail("marketCapAvailable", marketCap.isPresent());

                if (marketCap.isPresent()) {
                    builder.withDetail("sampleMarketCapCNY", marketCap.getAsDouble());
                }
                if (!bars.isEmpty()) {
                    builder.withDetail("latestDate", bars.get(bars.size() - 1).date().toString());
                }

                if (bars.isEmpty()) {
                    builder.status(new Status("UNKNOWN", "No data returned - may be outside trading hours"));
                }

                return builder.build();
            } catch (Exception e) {
                return Health.down()
                        .withDetail("error", e.getMessage())
                        .withException(e)
                        .build();
            }
        };
    }

    @Bean
    public HealthIndicator serverChanHealthIndicator(ServerChanNotifier notifier) {
        return () -> {
            if (!notifier.isConfigured()) {
                return Health.unknown()
                        .withDetail("status", "sendKey not configured - notification disabled")
                        .build();
            }
            return Health.up()
                    .withDetail("status", "sendKey configured")
                    .withDetail("configured", true)
                    .build();
        };
    }
}
