package com.feijimiao.xianyuassistant.service.thirdparty;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 卡速售平台接口凭证。
 */
@Data
@AllArgsConstructor
public class KasushouCredentials {
    /** 平台接口域名，如 https://demo.kasushou.com */
    private String baseUrl;
    /** 接口APPID（UserId） */
    private String userId;
    /** 接口密钥（apikey） */
    private String apiKey;
}
