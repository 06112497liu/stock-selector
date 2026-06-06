package com.aistock.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link YahooClient#fetchName(String)} / {@link YahooClient#parseName}.
 *
 * <p>No network: the {@link OkHttpClient} is faked via an {@link Interceptor}
 * that short-circuits every call with a canned response (or an {@link IOException}
 * to simulate a network failure). This keeps the real request-building /
 * response-handling path exercised while never touching the wire.
 */
class YahooNameTest {

    private static final MediaType JSON = MediaType.get("application/json");

    /** Builds an OkHttpClient whose calls are answered locally with {@code body}. */
    private static OkHttpClient cannedClient(String body, int httpCode) {
        Interceptor interceptor = chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(httpCode)
                .message(httpCode == 200 ? "OK" : "ERR")
                .body(ResponseBody.create(body, JSON))
                .build();
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    /** Builds an OkHttpClient whose calls always fail (network down). */
    private static OkHttpClient failingClient() {
        Interceptor interceptor = chain -> {
            throw new IOException("simulated network failure");
        };
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    @Test
    void fetchNamePrefersLongName() {
        String json = """
                {"quoteResponse":{"result":[
                  {"symbol":"AAPL","longName":"Apple Inc.","shortName":"Apple"}
                ],"error":null}}
                """;
        YahooClient client = new YahooClient(cannedClient(json, 200), new ObjectMapper());
        assertEquals("Apple Inc.", client.fetchName("AAPL"));
    }

    @Test
    void fetchNameFallsBackToShortNameWhenLongMissing() {
        String json = """
                {"quoteResponse":{"result":[
                  {"symbol":"MSFT","shortName":"Microsoft Corp"}
                ],"error":null}}
                """;
        YahooClient client = new YahooClient(cannedClient(json, 200), new ObjectMapper());
        assertEquals("Microsoft Corp", client.fetchName("MSFT"));
    }

    @Test
    void fetchNameFallsBackToSymbolWhenBothMissing() {
        String json = """
                {"quoteResponse":{"result":[{"symbol":"NVDA"}],"error":null}}
                """;
        YahooClient client = new YahooClient(cannedClient(json, 200), new ObjectMapper());
        assertEquals("NVDA", client.fetchName("NVDA"));
    }

    @Test
    void fetchNameFallsBackToSymbolWhenResultEmpty() {
        String json = "{\"quoteResponse\":{\"result\":[],\"error\":null}}";
        YahooClient client = new YahooClient(cannedClient(json, 200), new ObjectMapper());
        assertEquals("BOGUS", client.fetchName("BOGUS"));
    }

    @Test
    void fetchNameFallsBackToSymbolOnHttpError() {
        YahooClient client = new YahooClient(cannedClient("{}", 429), new ObjectMapper());
        assertEquals("AAPL", client.fetchName("AAPL"));
    }

    @Test
    void fetchNameFallsBackToSymbolOnNetworkFailure() {
        YahooClient client = new YahooClient(failingClient(), new ObjectMapper());
        assertEquals("AAPL", client.fetchName("AAPL"));
    }

    @Test
    void parseNameFallsBackOnMalformedJson() {
        YahooClient client = new YahooClient();
        assertEquals("AAPL", client.parseName("not json", "AAPL"));
    }
}
