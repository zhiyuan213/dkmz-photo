package com.dkmz.photo.service;

import com.dkmz.photo.controller.PhotoPayController;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 「内容安全检测」端到端：不依赖微信、不依赖云托管，用本地线程把链路串起来。
 *
 * 覆盖审核整改最关键的一条链路（也是真机上"看不到检测结果"的根因所在）：
 *   ① 业务线程调 checkOrThrow → 提交异步检测（HTTP 换桩）
 *   ② 微信把结果推给 POST /api/photo/pay/notify（真实 XML + 真实签名校验）
 *   ③ notify 路由到 acceptPush → 落到对应 trace_id
 *   ④ 原地的 checkOrThrow 拿到结论 → 违规图抛 RiskyContentException（拦截，不抠图）
 *
 * 放在 service 包内是因为要桩掉包级可见的 WxSecCheckService#postJson。
 */
class SecCheckPushEndToEndTest {

    private static final String PUSH_TOKEN = "pushtoken-demo";
    private static final String TRACE = "TRACE-E2E";
    private static final byte[] IMG = new byte[] { 9, 8, 7 };

    /** 按 PushSignUtil 的规则独立算一遍签名（顺带验证该实现没写错） */
    private static String sign(String token, String timestamp, String nonce) throws Exception {
        String[] arr = { token, timestamp, nonce };
        Arrays.sort(arr);
        byte[] d = MessageDigest.getInstance("SHA-1")
                .digest((arr[0] + arr[1] + arr[2]).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 微信真实推送报文：suggest 在 <detail> 内，不是顶层 */
    private static String xmlPush(String traceId, String suggest) {
        return "<xml><ToUserName><![CDATA[gh_abc]]></ToUserName>"
                + "<FromUserName><![CDATA[oUser]]></FromUserName><CreateTime>1596003230</CreateTime>"
                + "<MsgType><![CDATA[event]]></MsgType><Event><![CDATA[wxa_media_check]]></Event>"
                + "<appid><![CDATA[wx1234567890]]></appid>"
                + "<trace_id><![CDATA[" + traceId + "]]></trace_id><version>2</version>"
                + "<detail><strategy>content_model</strategy><errcode>0</errcode>"
                + "<suggest>" + suggest + "</suggest><label>100</label><prob>90</prob></detail>"
                + "<errcode>0</errcode><errmsg><![CDATA[ok]]></errmsg></xml>";
    }

    /** 建一个"只差 HTTP"的真实检测服务 */
    private WxSecCheckService realService() throws Exception {
        WxSecCheckService sec = spy(new WxSecCheckService(mock(TokenManager.class)));
        ReflectionTestUtils.setField(sec, "enabled", true);
        ReflectionTestUtils.setField(sec, "cloudCall", true);
        ReflectionTestUtils.setField(sec, "waitMs", 5000L);
        ReflectionTestUtils.setField(sec, "publicBaseUrl", "https://demo.example.com");
        // 提交接口换桩：微信受理，返回 trace_id（真实链路里这步会去调微信）
        doReturn("{\"errcode\":0,\"errmsg\":\"ok\",\"trace_id\":\"" + TRACE + "\"}")
                .when(sec).postJson(anyString(), anyString(), anyBoolean());
        return sec;
    }

    /** 等检测提交落地（拿到真实生成的 trace_id），避免"推送早于提交"的竞态 */
    @SuppressWarnings("unchecked")
    private static String awaitTraceId(WxSecCheckService sec) throws Exception {
        Map<String, ?> pendings = (Map<String, ?>) ReflectionTestUtils.getField(sec, "pendings");
        assertNotNull(pendings, "取不到 pendings");
        long deadline = System.currentTimeMillis() + 3000;
        while (pendings.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(pendings.isEmpty(), "检测未在 3s 内完成提交");
        return pendings.keySet().iterator().next();
    }

    @Test
    void riskyImageIsBlockedThroughRealPushEndpoint() throws Exception {
        WxSecCheckService sec = realService();
        PhotoPayController controller = new PhotoPayController(
                mock(PhotoPayService.class), mock(EntitlementService.class), sec);
        ReflectionTestUtils.setField(controller, "pushToken", PUSH_TOKEN);

        // 业务线程：进处理前先做内容安全检测
        AtomicReference<String> conclusion = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                sec.checkOrThrow(IMG, "oUser");
                conclusion.set("passed");
            } catch (WxSecCheckService.RiskyContentException e) {
                conclusion.set("risky:" + e.getSuggest());
            } catch (Throwable t) {
                unexpected.set(t);
            }
        });
        worker.setDaemon(true);
        worker.start();

        String traceId = awaitTraceId(sec);
        assertEquals(TRACE, traceId);

        // 微信回推结果：真实 XML + 真实签名
        String ts = "1596003230";
        String nonce = "n1";
        ResponseEntity<String> resp =
                controller.notify(xmlPush(traceId, "risky"), null, sign(PUSH_TOKEN, ts, nonce), ts, nonce);
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(String.valueOf(resp.getBody()).contains("success"), String.valueOf(resp.getBody()));

        worker.join(3000);
        assertFalse(worker.isAlive(), "检测线程应已结束");
        assertNull(unexpected.get(), "不应出现其他异常：" + unexpected.get());
        assertEquals("risky:risky", conclusion.get(), "违规图必须被拦截，且不得继续抠图处理");
    }

    @Test
    void passingImageGoesThrough() throws Exception {
        WxSecCheckService sec = realService();
        PhotoPayController controller = new PhotoPayController(
                mock(PhotoPayService.class), mock(EntitlementService.class), sec);
        ReflectionTestUtils.setField(controller, "pushToken", PUSH_TOKEN);

        AtomicReference<String> conclusion = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                sec.checkOrThrow(IMG, "oUser");
                conclusion.set("passed");
            } catch (Throwable t) {
                unexpected.set(t);
            }
        });
        worker.setDaemon(true);
        worker.start();

        String traceId = awaitTraceId(sec);
        String ts = "1596003230";
        String nonce = "n2";
        controller.notify(xmlPush(traceId, "pass"), null, sign(PUSH_TOKEN, ts, nonce), ts, nonce);

        worker.join(3000);
        assertNull(unexpected.get(), "不应有异常：" + unexpected.get());
        assertEquals("passed", conclusion.get());
    }

    /** 签名不对的推送必须被拒绝，不能让伪造报文左右检测结论 */
    @Test
    void pushWithBadSignatureIsRejectedAndCheckStillTimesOut() throws Exception {
        WxSecCheckService sec = realService();
        ReflectionTestUtils.setField(sec, "waitMs", 600L);   // 缩短超时，别让用例等 5s
        PhotoPayController controller = new PhotoPayController(
                mock(PhotoPayService.class), mock(EntitlementService.class), sec);
        ReflectionTestUtils.setField(controller, "pushToken", PUSH_TOKEN);

        AtomicReference<String> conclusion = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                sec.checkOrThrow(IMG, "oUser");
                conclusion.set("passed");
            } catch (WxSecCheckService.RiskyContentException e) {
                conclusion.set("risky");
            } catch (Throwable t) {
                conclusion.set("error:" + t.getClass().getSimpleName());
            }
        });
        worker.setDaemon(true);
        worker.start();

        String traceId = awaitTraceId(sec);
        String ts = "1596003230";
        String nonce = "n3";
        ResponseEntity<String> resp = controller.notify(
                xmlPush(traceId, "pass"), null, "deadbeef", ts, nonce);
        assertTrue(String.valueOf(resp.getBody()).contains("signature check failed"),
                String.valueOf(resp.getBody()));

        worker.join(3000);
        assertEquals("error:IllegalStateException", conclusion.get(), "伪签名不能让检测放行");
    }
}
