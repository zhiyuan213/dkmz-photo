package com.dkmz.photo.support;

/**
 * 日志安全工具：**凭据绝不落日志**（项目红线，见 MEMORY「凭据安全」）。
 *
 * 背景：外部接口的地址里常把凭据放在 query ——
 *   /sns/jscode2session?appid=&secret=&js_code=
 *   /cgi-bin/token?grant_type=client_credential&appid=&secret=
 *   /wxa/media_check_async?access_token=
 *   /xpay/query_order?access_token=&pay_sig=
 * 一旦把请求 URL 交给异常或日志，secret / access_token 就进了日志文件，
 * 而日志会被投递、检索、留存。所以凡是要记异常文本的地方，都先过一遍这里。
 */
public final class Logs {

    private Logs() {
    }

    /** 需要脱敏的字段名（不含 appid —— 它是公开信息，留着便于排查环境） */
    private static final String CREDENTIAL_KEYS =
            "access_token|secret|js_code|pay_sig|signature|signData|session_key";

    /**
     * 凭据模式：同时覆盖 URL query（{@code secret=xxx}）与 JSON（{@code "signData":"xxx"}）两种写法。
     * 值只吃到分隔符（& , 空白 引号 花括号）为止。
     */
    private static final String CREDENTIAL_PATTERN =
            "(?i)([\"']?(?:" + CREDENTIAL_KEYS + ")[\"']?\\s*[:=]\\s*[\"']?)([^&,\\s\"'}]+)";

    /**
     * 异常/消息文本脱敏：夹带的凭据一律替换成 ***。
     * 用法：{@code log.warn("... {}", Logs.safe(e))}
     */
    public static String safe(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).replaceAll(CREDENTIAL_PATTERN, "$1***");
    }
}
