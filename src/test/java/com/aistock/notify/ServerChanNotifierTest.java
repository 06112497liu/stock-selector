package com.aistock.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ServerChanNotifier} 单测:不触网,用 OkHttp Interceptor 喂 canned response。
 *
 * <p>同时通过 Interceptor 捕获实际发出的 {@link Request},断言 URL 与 form 字段,
 * 确认请求确实 POST 到 {@code sctapi.ftqq.com/{key}.send} 且携带 title/desp。
 */
class ServerChanNotifierTest {

    private static final MediaType JSON = MediaType.get("application/json");

    /** 喂固定响应,同时把捕获到的 Request 写入 captured。 */
    private static OkHttpClient capturingClient(String body, int httpCode, AtomicReference<Request> captured) {
        Interceptor interceptor = chain -> {
            captured.set(chain.request());
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

    /** 总是抛 IOException,模拟网络故障。 */
    private static OkHttpClient failingClient(AtomicReference<Request> captured) {
        Interceptor interceptor = chain -> {
            captured.set(chain.request());
            throw new IOException("simulated network failure");
        };
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    /** 读取 OkHttp FormBody 编码后的内容,便于断言含 title/desp。 */
    private static String formBody(Request req) throws IOException {
        okio.Buffer buffer = new okio.Buffer();
        assertNotNull(req.body());
        req.body().writeTo(buffer);
        return buffer.readUtf8();
    }

    @Test
    void successWhenCode0() throws IOException {
        AtomicReference<Request> captured = new AtomicReference<>();
        ServerChanNotifier n = new ServerChanNotifier(
                capturingClient("{\"code\":0,\"message\":\"OK\"}", 200, captured),
                new ObjectMapper(), "KEY123");

        assertTrue(n.send("标题", "正文 body"));

        Request req = captured.get();
        assertEquals("https://sctapi.ftqq.com/KEY123.send", req.url().toString());
        assertEquals("POST", req.method());
        String form = formBody(req);
        assertTrue(form.contains("title="), "form 应含 title 字段");
        assertTrue(form.contains("desp="), "form 应含 desp 字段");
    }

    @Test
    void failsWhenCodeNotZero() {
        AtomicReference<Request> captured = new AtomicReference<>();
        ServerChanNotifier n = new ServerChanNotifier(
                capturingClient("{\"code\":40001,\"message\":\"bad key\"}", 200, captured),
                new ObjectMapper(), "KEY123");
        assertFalse(n.send("t", "b"));
    }

    @Test
    void failsOnHttpError() {
        AtomicReference<Request> captured = new AtomicReference<>();
        ServerChanNotifier n = new ServerChanNotifier(
                capturingClient("{}", 500, captured),
                new ObjectMapper(), "KEY123");
        assertFalse(n.send("t", "b"));
    }

    @Test
    void failsOnNetworkException() {
        AtomicReference<Request> captured = new AtomicReference<>();
        ServerChanNotifier n = new ServerChanNotifier(
                failingClient(captured), new ObjectMapper(), "KEY123");
        assertFalse(n.send("t", "b"));
    }

    @Test
    void emptyKeyReturnsFalseWithoutSending() {
        AtomicReference<Request> captured = new AtomicReference<>();
        ServerChanNotifier n = new ServerChanNotifier(
                capturingClient("{\"code\":0}", 200, captured),
                new ObjectMapper(), "");
        assertFalse(n.send("t", "b"));
        assertNull(captured.get(), "空 key 不应发出任何请求");
        assertFalse(n.isConfigured());
    }

    @Test
    void nullKeyReturnsFalseWithoutSending() {
        AtomicReference<Request> captured = new AtomicReference<>();
        ServerChanNotifier n = new ServerChanNotifier(
                capturingClient("{\"code\":0}", 200, captured),
                new ObjectMapper(), null);
        assertFalse(n.send("t", "b"));
        assertNull(captured.get(), "null key 不应发出任何请求");
    }
}
