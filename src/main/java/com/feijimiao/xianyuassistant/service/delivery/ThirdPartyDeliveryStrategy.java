package com.feijimiao.xianyuassistant.service.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig;
import com.feijimiao.xianyuassistant.service.thirdparty.KasushouApiClient;
import com.feijimiao.xianyuassistant.service.thirdparty.KasushouCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 三方平台发货策略（deliveryMode=4）。
 *
 * <p>买家付款后，调用卡速售（kasushou）开放平台下单，轮询订单详情获取卡密/取货内容，
 * 用模板 {content} 占位符包装后作为发货内容返回。</p>
 *
 * <h3>流程：</h3>
 * <ol>
 *   <li>读取商品配置中的三方平台凭证与商品ID、attach参数</li>
 *   <li>以 闲鱼订单号 + 随机串 作为 external_orderno 调用下单接口</li>
 *   <li>循环（1-3秒间隔）调用订单详情接口直至订单成功/失败</li>
 *   <li>订单成功：提取 card_list / recharge_info 拼接为取货内容</li>
 *   <li>用 {content} 模板包装后返回；下单或取货失败返回 null（触发发货失败）</li>
 * </ol>
 */
@Slf4j
@Component
public class ThirdPartyDeliveryStrategy implements DeliveryContentStrategy {

    /** 轮询订单详情最大次数 */
    private static final int MAX_POLL_TIMES = 30;
    /** 轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 2000L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private KasushouApiClient kasushouApiClient;

    @Override
    public boolean supports(int deliveryMode) {
        return deliveryMode == 4;
    }

    @Override
    public String resolve(DeliveryContext context) {
        XianyuGoodsAutoDeliveryConfig config = context.getDeliveryConfig();
        Long accountId = context.getAccountId();

        if (config.getThirdPartyGoodsId() == null || config.getThirdPartyGoodsId().trim().isEmpty()) {
            log.warn("【账号{}】三方平台发货未配置三方商品ID: xyGoodsId={}", accountId, context.getXyGoodsId());
            return null;
        }

        KasushouCredentials creds = new KasushouCredentials(
                config.getThirdPartyBaseUrl(),
                config.getThirdPartyUserId(),
                config.getThirdPartyApiKey()
        );

        try {
            // external_orderno 必须唯一，使用闲鱼订单号 + 短随机串
            String externalOrderNo = buildExternalOrderNo(context.getOrderId());
            Map<String, Object> attach = parseAttach(config.getThirdPartyAttach(), accountId);

            // 1. 下单
            JsonNode buyResp = kasushouApiClient.buyOrder(
                    creds,
                    config.getThirdPartyGoodsId().trim(),
                    externalOrderNo,
                    1,
                    config.getThirdPartySafePrice(),
                    attach
            );
            if (buyResp.path("code").asInt() != 200) {
                log.warn("【账号{}】三方平台下单失败: msg={}, externalOrderNo={}",
                        accountId, buyResp.path("msg").asText(), externalOrderNo);
                return null;
            }
            String ordersn = buyResp.path("data").path("ordersn").asText(null);
            if (ordersn == null || ordersn.isEmpty()) {
                log.warn("【账号{}】三方平台下单返回缺少ordersn: {}", accountId, buyResp);
                return null;
            }
            log.info("【账号{}】三方平台下单成功: ordersn={}, externalOrderNo={}", accountId, ordersn, externalOrderNo);

            // 2. 轮询订单详情获取取货内容
            String fetched = pollOrderResult(creds, ordersn, accountId);
            if (fetched == null) {
                return null;
            }

            // 3. 模板包装
            String template = config.getThirdPartyTemplate();
            if (template != null && !template.trim().isEmpty()) {
                return template.replace("{content}", fetched);
            }
            return fetched;
        } catch (Exception e) {
            log.error("【账号{}】三方平台发货异常: xyGoodsId={}", accountId, context.getXyGoodsId(), e);
            return null;
        }
    }

    /**
     * 轮询订单详情接口直至出结果。
     *
     * @return 取货内容，失败/超时返回 null
     */
    private String pollOrderResult(KasushouCredentials creds, String ordersn, Long accountId) {
        for (int i = 0; i < MAX_POLL_TIMES; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            try {
                JsonNode infoResp = kasushouApiClient.queryOrderInfo(creds, ordersn);
                if (infoResp.path("code").asInt() != 200) {
                    log.warn("【账号{}】三方平台订单详情查询失败: msg={}", accountId, infoResp.path("msg").asText());
                    continue;
                }
                JsonNode data = infoResp.path("data");
                if (!data.isArray() || data.isEmpty()) {
                    continue;
                }
                JsonNode order = data.get(0);
                int status = order.path("status").asInt();

                // 1=等待处理,2=正在处理,3=交易成功,4=取消交易,5=已退款,-1=未支付
                if (status == 3) {
                    String content = extractContent(order);
                    if (content == null || content.isEmpty()) {
                        log.warn("【账号{}】三方平台订单成功但取货内容为空: ordersn={}", accountId, ordersn);
                        return null;
                    }
                    log.info("【账号{}】三方平台取货成功: ordersn={}", accountId, ordersn);
                    return content;
                } else if (status == 4 || status == 5 || status == -1) {
                    log.warn("【账号{}】三方平台订单失败: ordersn={}, status={}, hints={}",
                            accountId, ordersn, status, order.path("recharge_hints").asText());
                    return null;
                }
                // status=1/2 继续轮询
            } catch (Exception e) {
                log.warn("【账号{}】三方平台订单详情查询异常(第{}次): {}", accountId, i + 1, e.getMessage());
            }
        }
        log.warn("【账号{}】三方平台订单轮询超时: ordersn={}", accountId, ordersn);
        return null;
    }

    /**
     * 从订单详情中提取取货内容：优先卡密(card_list)，其次充值参数(recharge_info)。
     */
    private String extractContent(JsonNode order) {
        StringBuilder sb = new StringBuilder();

        JsonNode cardList = order.path("card_list");
        if (cardList.isArray() && !cardList.isEmpty()) {
            for (JsonNode card : cardList) {
                String cardNo = card.path("card_no").asText("");
                String cardPwd = card.path("card_password").asText("");
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                if (!cardNo.isEmpty()) {
                    sb.append("卡号：").append(cardNo).append("  ");
                }
                sb.append("卡密：").append(cardPwd);
            }
            return sb.toString();
        }

        JsonNode rechargeInfo = order.path("recharge_info");
        if (rechargeInfo.isArray() && !rechargeInfo.isEmpty()) {
            for (JsonNode item : rechargeInfo) {
                String name = item.path("n").asText("");
                String value = item.path("v").asText("");
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(name).append("：").append(value);
            }
            return sb.toString();
        }

        // 卡密/参数都为空时，回退到订单返回信息
        String hints = order.path("recharge_hints").asText("");
        return hints.isEmpty() ? null : hints;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAttach(String attachJson, Long accountId) {
        if (attachJson == null || attachJson.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(attachJson);
            if (!node.isObject()) {
                log.warn("【账号{}】三方平台attach配置不是JSON对象，忽略: {}", accountId, attachJson);
                return null;
            }
            Map<String, Object> attach = kasushouApiClient.newAttachMap();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                attach.put(entry.getKey(), entry.getValue().asText());
            }
            return attach;
        } catch (Exception e) {
            log.warn("【账号{}】三方平台attach配置解析失败，忽略: {}", accountId, attachJson, e);
            return null;
        }
    }

    private String buildExternalOrderNo(String orderId) {
        String shortRand = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        if (orderId == null || orderId.isEmpty()) {
            return "XY" + shortRand;
        }
        return orderId + "-" + shortRand;
    }
}
