package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自动发货配置响应DTO
 */
@Data
public class AutoDeliveryConfigRespDTO {
    
    /**
     * 配置ID
     */
    private Long id;
    
    /**
     * 闲鱼账号ID
     */
    private Long xianyuAccountId;
    
    /**
     * 本地闲鱼商品ID
     */
    private Long xianyuGoodsId;
    
    /**
     * 闲鱼的商品ID
     */
    private String xyGoodsId;
    
    /**
     * 发货模式：1-自动发货，2-卡密发货，3-自定义发货
     */
    private Integer deliveryMode;

    private String skuId;

    private String skuName;

    private String autoDeliveryContent;

    /**
     * 卡密发货：绑定的卡密配置ID列表（逗号分隔）
     */
    private String kamiConfigIds;

    /**
     * 卡密发货文案模板，使用{kmKey}占位符替换卡密内容
     */
    private String kamiDeliveryTemplate;

    /**
     * 自动发货图片URL
     */
    private String autoDeliveryImageUrl;
    
    /**
     * 自动确认发货开关：0-关闭，1-开启
     */
    private Integer autoConfirmShipment;

    /**
     * 三方平台发货：平台接口域名，如 https://demo.kasushou.com
     */
    private String thirdPartyBaseUrl;

    /**
     * 三方平台发货：接口APPID（UserId）
     */
    private String thirdPartyUserId;

    /**
     * 三方平台发货：接口密钥（apikey）
     */
    private String thirdPartyApiKey;

    /**
     * 三方平台发货：三方商品ID或规格编码
     */
    private String thirdPartyGoodsId;

    /**
     * 三方平台发货：安全价格（防止亏本，可空）
     */
    private String thirdPartySafePrice;

    /**
     * 三方平台发货：下单参数attach（JSON字符串，卡密商品可空）
     */
    private String thirdPartyAttach;

    /**
     * 三方平台发货：发货文案模板，使用{content}占位符替换取货内容
     */
    private String thirdPartyTemplate;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}