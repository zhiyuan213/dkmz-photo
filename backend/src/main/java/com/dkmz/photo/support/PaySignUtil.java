package com.dkmz.photo.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 微信虚拟支付签名工具（官方规范：HMAC-SHA256 输出 64 位小写十六进制）。
 *
 * 两套签名：
 *   支付签名 paySig    = hmac_sha256(AppKey, uri + "&" + signData)，拉起支付时 uri 固定为 requestVirtualPayment
 *   用户态签名 signature = hmac_sha256(sessionKey, signData)，sessionKey 来自 auth.code2Session
 *
 * ⚠️ 参与签名的 body 必须与最终发送/下发的字符串逐字节一致（不能二次序列化、不能改变字段顺序），
 *    所以 signData 只序列化一次，原样返回给前端。
 */
public final class PaySignUtil {

    private PaySignUtil() {
    }

    /** HMAC-SHA256 -> 64 位小写 hex */
    public static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }
}
