package com.aistock.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EastMoneyClient#fetchName(String)} /
 * {@link EastMoneyClient#parseName} and the secid conversion used to build the
 * K-line request. No network: the {@link OkHttpClient} is faked via an
 * {@link Interceptor}. The name is read from the K-line endpoint's
 * {@code data.name} (push2his), since the push2 quote snapshot is unreachable.
 */
class EastMoneyNameTest {

    private static final MediaType JSON = MediaType.get("application/json");

    /**
     * Builds an OkHttpClient answering locally with {@code body}; if
     * {@code capturedUrl} is non-null the intercepted request URL is recorded
     * into it (used to assert the secid sent upstream).
     */
    private static OkHttpClient cannedClient(String body, int httpCode,
                                             AtomicReference<HttpUrl> capturedUrl) {
        Interceptor interceptor = chain -> {
            if (capturedUrl != null) {
                capturedUrl.set(chain.request().url());
            }
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(httpCode)
                    .message(httpCode == 200 ? "OK" : "ERR")
                    .body(ResponseBody.create(body, JSON))
                    .build();
        };
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    private static OkHttpClient failingClient() {
        Interceptor interceptor = chain -> {
            throw new IOException("simulated network failure");
        };
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    @Test
    void fetchNameReadsDataName() {
        String json = """
                {"rc":0,"data":{"code":"600519","market":1,"name":"贵州茅台",\
                "decimal":2,"klines":["2024-01-02,1685.01,1715.00,1718.00,1680.00,30000,5000000000"]}}
                """;
        EastMoneyClient client =
                new EastMoneyClient(cannedClient(json, 200, null), new ObjectMapper());
        assertEquals("贵州茅台", client.fetchName("600519"));
    }

    @Test
    void fetchNameSendsCorrectSecidForShanghai() {
        AtomicReference<HttpUrl> url = new AtomicReference<>();
        String json = "{\"rc\":0,\"data\":{\"code\":\"600519\",\"name\":\"贵州茅台\"}}";
        EastMoneyClient client =
                new EastMoneyClient(cannedClient(json, 200, url), new ObjectMapper());

        client.fetchName("600519");

        assertEquals("1.600519", url.get().queryParameter("secid"));
        // Name now comes from the K-line endpoint (klt=101 daily, lmt=1).
        assertEquals("101", url.get().queryParameter("klt"));
        assertEquals("1", url.get().queryParameter("lmt"));
        assertTrue(url.get().encodedPath().endsWith("/kline/get"),
                "name must come from the kline endpoint, got: " + url.get().encodedPath());
    }

    @Test
    void fetchNameSendsCorrectSecidForShenzhen() {
        AtomicReference<HttpUrl> url = new AtomicReference<>();
        String json = "{\"rc\":0,\"data\":{\"code\":\"000001\",\"name\":\"平安银行\"}}";
        EastMoneyClient client =
                new EastMoneyClient(cannedClient(json, 200, url), new ObjectMapper());

        assertEquals("平安银行", client.fetchName("000001"));
        assertEquals("0.000001", url.get().queryParameter("secid"));
    }

    @Test
    void parseNameReadsDataNameDirectly() {
        EastMoneyClient client = new EastMoneyClient();
        String json = "{\"rc\":0,\"data\":{\"code\":\"600519\",\"name\":\"贵州茅台\"}}";
        assertEquals("贵州茅台", client.parseName(json, "600519"));
    }

    @Test
    void parseNameFallsBackWhenNameMissing() {
        EastMoneyClient client = new EastMoneyClient();
        // K-line payload with klines but no name field -> fall back to code.
        String json = "{\"rc\":0,\"data\":{\"code\":\"600519\",\"klines\":[]}}";
        assertEquals("600519", client.parseName(json, "600519"));
    }

    @Test
    void parseNameFallsBackWhenDataNull() {
        EastMoneyClient client = new EastMoneyClient();
        assertEquals("600519", client.parseName("{\"rc\":0,\"data\":null}", "600519"));
    }

    @Test
    void fetchNameFallsBackToCodeOnNullData() {
        // East Money returns data:null for an unknown secid.
        String json = "{\"rc\":0,\"data\":null}";
        EastMoneyClient client =
                new EastMoneyClient(cannedClient(json, 200, null), new ObjectMapper());
        assertEquals("600519", client.fetchName("600519"));
    }

    @Test
    void fetchNameFallsBackToCodeOnHttpError() {
        EastMoneyClient client =
                new EastMoneyClient(cannedClient("{}", 500, null), new ObjectMapper());
        assertEquals("600519", client.fetchName("600519"));
    }

    @Test
    void fetchNameFallsBackToCodeOnNetworkFailure() {
        EastMoneyClient client =
                new EastMoneyClient(failingClient(), new ObjectMapper());
        assertEquals("600519", client.fetchName("600519"));
    }

    @Test
    void fetchNameFallsBackToCodeForUnknownMarketWithoutTouchingNetwork() {
        // 9-prefixed code has no market mapping; toSecid throws -> must degrade
        // to the code itself, and the network must never be hit (failingClient
        // would throw if it were, but we still expect the code back).
        EastMoneyClient client =
                new EastMoneyClient(failingClient(), new ObjectMapper());
        assertEquals("900001", client.fetchName("900001"));
    }

    @Test
    void parseNameFallsBackOnMalformedJson() {
        EastMoneyClient client = new EastMoneyClient();
        assertEquals("600519", client.parseName("not json", "600519"));
    }
}
