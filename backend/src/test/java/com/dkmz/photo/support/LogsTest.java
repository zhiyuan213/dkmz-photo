package com.dkmz.photo.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭据脱敏守门测试：外部接口地址进了异常/日志时，secret、access_token 等一律变 ***。
 * 这是项目红线（密钥绝不落日志），所以要有测试盯着，不能只靠人记得。
 */
class LogsTest {

    @Test
    void stripsSecretAndJsCodeFromLoginUrl() {
        String raw = "https://api.weixin.qq.com/sns/jscode2session"
                + "?appid=wxa1b2c3&secret=abcdef0123456789&js_code=081AbC&grant_type=authorization_code";
        String safe = Logs.safe(raw);
        assertFalse(safe.contains("abcdef0123456789"), safe);
        assertFalse(safe.contains("081AbC"), safe);
        assertTrue(safe.contains("secret=***"), safe);
        assertTrue(safe.contains("js_code=***"), safe);
        // appid 是公开信息，保留下来便于排查环境
        assertTrue(safe.contains("appid=wxa1b2c3"), safe);
        assertEquals(raw.replace("abcdef0123456789", "***").replace("081AbC", "***"), safe);
    }

    @Test
    void stripsAccessTokenPaySigAndSignature() {
        String safe = Logs.safe("POST https://api.weixin.qq.com/xpay/query_order"
                + "?access_token=TOKEN-123&pay_sig=deadbeef");
        assertFalse(safe.contains("TOKEN-123"), safe);
        assertFalse(safe.contains("deadbeef"), safe);
        assertTrue(safe.contains("access_token=***"), safe);
        assertTrue(safe.contains("pay_sig=***"), safe);

        // JSON 写法（日志里打印报文时会遇到）同样要脱敏
        String sign = Logs.safe("{\"signData\":\"eyJvcGVuaWQiOiJvMTIzIn0=\",\"signature\":\"YWJj\"}");
        assertFalse(sign.contains("eyJvcGVuaWQiOiJvMTIzIn0="), sign);
        assertFalse(sign.contains("YWJj"), sign);
        assertTrue(sign.contains("\"signData\":\"***\""), sign);
        assertTrue(sign.contains("\"signature\":\"***\""), sign);
    }

    @Test
    void leavesOrdinaryMessagesIntact() {
        assertEquals("连接超时 request timed out", Logs.safe("连接超时 request timed out"));
        assertEquals("HTTP 502 85107 URL不在白名单内", Logs.safe("HTTP 502 85107 URL不在白名单内"));
    }

    @Test
    void handlesNullSafely() {
        assertEquals("", Logs.safe(null));
    }
}
