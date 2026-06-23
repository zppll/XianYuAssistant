package com.feijimiao.xianyuassistant.service.thirdparty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 卡速售（kasushou）开放平台 API 客户端。
 *
 * <p>实现签名规范、订单提交、订单详情查询。文档：https://doc.kasushou.com/api/v2api/34</p>
 *
 * <h3>签名规范：</h3>
 * <ul>
 *   <li>Header：Sign / Timestamp / UserId</li>
 *   <li>Timestamp = 10位秒级时间戳 + 3位随机数，共13位</li>
 *   <li>Sign = sha1(Timestamp + body(JSON, 顶层键升序, 不转义斜杠和中文) + apikey)</li>
 *   <li>body为空时使用 {} 参与签名</li>
 * </ul>
 */
@Slf4j
@Component
public class KasushouApiClient {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    /**
     * 提交订单（异步下单接口）。下单成功不代表充值成功，需调用 {@link #queryOrderInfo} 查询结果。
     *
     * @param creds           平台凭证（baseUrl/userId/apiKey）
     * @param goodsId         三方商品ID
     * @param externalOrderNo 外部订单号（唯一，防重复）
     * @param quantity        下单数量
     * @param safePrice       安全价格，可空
     * @param attach          下单参数（卡密商品可空）
     * @return 接口返回JsonNode（包含 code/msg/data），失败抛异常
     */
    public JsonNode buyOrder(KasushouCredentials creds, String goodsId, String externalOrderNo,
                             int quantity, String safePrice, Map<String, Object> attach) throws Exception {
        // 顶层参数使用 TreeMap 保证键升序，与服务端 ksort 一致
        Map<String, Object> body = new TreeMap<>();
        body.put("id", parseGoodsId(goodsId));
        body.put("external_orderno", externalOrderNo == null ? "" : externalOrderNo);
        body.put("quantity", quantity);
        if (safePrice != null && !safePrice.trim().isEmpty()) {
            body.put("safe_price", safePrice.trim());
        }
        if (attach != null && !attach.isEmpty()) {
            body.put("attach", attach);
        }
        return post(creds, "/api/v1/order/buy", body);
    }

    /**
     * 订单详情查询接口（用于轮询充值结果，获取卡密/取货信息）。
     *
     * @param creds   平台凭证
     * @param ordersn 本地订单号（平台返回的 ordersn）
     * @return 接口返回JsonNode
     */
    public JsonNode queryOrderInfo(KasushouCredentials creds, String ordersn) throws Exception {
        Map<String, Object> body = new TreeMap<>();
        body.put("ordersn", ordersn == null ? "" : ordersn);
        body.put("external_orderno", "");
        body.put("day", "0");
        return post(creds, "/api/v1/order/info", body);
    }

    /**
     * 用户余额查询，可用于配置时校验凭证是否正确。
     */
    public JsonNode queryUserInfo(KasushouCredentials creds) throws Exception {
        return post(creds, "/api/v1/user/info", new TreeMap<>());
    }

    private JsonNode post(KasushouCredentials creds, String path, Map<String, Object> body) throws Exception {
        if (creds == null || creds.getBaseUrl() == null || creds.getBaseUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("三方平台接口域名(baseUrl)未配置");
        }
        if (creds.getUserId() == null || creds.getUserId().trim().isEmpty()
                || creds.getApiKey() == null || creds.getApiKey().trim().isEmpty()) {
            throw new IllegalArgumentException("三方平台 UserId 或 apikey 未配置");
        }

        String bodyJson = (body == null || body.isEmpty()) ? "{}" : objectMapper.writeValueAsString(body);
        String timestamp = generateTimestamp();
        String sign = sha1(timestamp + bodyJson + creds.getApiKey());

        String url = trimTrailingSlash(creds.getBaseUrl().trim()) + path;
        Request request = new Request.Builder()
                .url(url)
                .header("Sign", sign)
                .header("Timestamp", timestamp)
                .header("UserId", creds.getUserId().trim())
                .post(RequestBody.create(bodyJson, JSON_MEDIA_TYPE))
                .build();

        log.debug("调用卡速售接口 {} body={}", path, bodyJson);
        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            log.debug("卡速售接口 {} 返回: {}", path, respBody);
            if (respBody.isEmpty()) {
                throw new RuntimeException("三方平台接口返回为空, HTTP " + response.code());
            }
            return objectMapper.readTree(respBody);
        }
    }

    /**
     * attach 内部保持插入顺序（服务端 ksort 只排序顶层，嵌套对象按原顺序），故使用 LinkedHashMap。
     */
    public Map<String, Object> newAttachMap() {
        return new LinkedHashMap<>();
    }

    private Object parseGoodsId(String goodsId) {
        if (goodsId == null) {
            return 0;
        }
        try {
            return Integer.parseInt(goodsId.trim());
        } catch (NumberFormatException e) {
            // 规格编码可能为非数字，原样传递
            return goodsId.trim();
        }
    }

    private String generateTimestamp() {
        long seconds = System.currentTimeMillis() / 1000;
        int rand = ThreadLocalRandom.current().nextInt(100, 1000);
        return seconds + String.valueOf(rand);
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String sha1(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
