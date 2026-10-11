package com.dkmz.photo.support;

/**
 * openid 解析工具。
 *
 * 线上主路径：云托管 callContainer 注入 X-WX-OPENID / X-WX-FROM-OPENID 请求头；
 * 本地/兜底：请求体里的 openid 字段（仅在云托管通道拿不到时使用，禁止前端伪造线上身份）。
 */
public final class OpenidSupport {

    private OpenidSupport() {
    }

    public static String firstNotBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return (b != null && !b.isBlank()) ? b.trim() : null;
    }

    /** 依次尝试 fromOpenid、cloudOpenid、bodyOpenid */
    public static String resolve(String fromOpenid, String cloudOpenid, String bodyOpenid) {
        String openid = firstNotBlank(fromOpenid, cloudOpenid);
        return openid != null ? openid : firstNotBlank(bodyOpenid, null);
    }
}
