package com.aistock.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server酱(ServerChan)微信推送客户端,对标 Python 版 {@code ServerChanNotifier}。
 *
 * <p>把一条 markdown 文本通过 Server酱 turbo 接口推到用户微信:
 * {@code POST https://sctapi.ftqq.com/{sendKey}.send},表单字段 {@code title}、{@code desp}。
 *
 * <p>设计为「绝不抛」:任一失败(sendKey 缺失 / 网络异常 / 非 2xx / 响应 code != 0)
 * 都只记录日志并返回 {@code false},不打断上层 Web 请求。仅在 HTTP 2xx 且响应 JSON
 * {@code code == 0} 时返回 {@code true}。
 *
 * <p>OkHttpClient 可注入,便于测试用 Interceptor 喂 canned response 不触网。
 */
public class ServerChanNotifier {

    private static final Logger log = LoggerFactory.getLogger(ServerChanNotifier.class);
    private static final String BASE_URL = "https://sctapi.ftqq.com/";

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String sendKey;

    /** 用默认 OkHttpClient/ObjectMapper 构造。 */
    public ServerChanNotifier(String sendKey) {
        this(new OkHttpClient(), new ObjectMapper(), sendKey);
    }

    public ServerChanNotifier(OkHttpClient http, ObjectMapper mapper, String sendKey) {
        this.http = http;
        this.mapper = mapper;
        this.sendKey = sendKey;
    }

    /** sendKey 是否已配置(用于页面提示「未配置」)。 */
    public boolean isConfigured() {
        return sendKey != null && !sendKey.isBlank();
    }

    /**
     * 发送一条微信推送。
     *
     * @param title        推送标题(微信消息标题)
     * @param markdownBody markdown 正文(对应 Server酱 desp 字段)
     * @return 成功(HTTP 2xx 且响应 code == 0)返回 {@code true};任一失败返回 {@code false}(不抛)
     */
    public boolean send(String title, String markdownBody) {
        if (!isConfigured()) {
            log.warn("ServerChan sendKey 未配置,跳过推送");
            return false;
        }

        RequestBody form = new FormBody.Builder()
                .add("title", title == null ? "" : title)
                .add("desp", markdownBody == null ? "" : markdownBody)
                .build();

        Request request = new Request.Builder()
                .url(BASE_URL + sendKey + ".send")
                .post(form)
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("ServerChan 推送失败:HTTP {}", response.code());
                return false;
            }
            String body = response.body().string();
            JsonNode root = mapper.readTree(body);
            int code = root.path("code").asInt(-1);
            if (code == 0) {
                return true;
            }
            log.warn("ServerChan 推送被拒:code={} body={}", code, body);
            return false;
        } catch (Exception e) {
            log.warn("ServerChan 推送异常:{}", e.toString());
            return false;
        }
    }
}
