package com.dkmz.photo.service;

import com.dkmz.photo.support.PushSignUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 内容安全检测（mediaCheckAsync）的离线验证：不依赖微信、不依赖云托管。
 * 用桩替换 HTTP 层（postJson），用线程模拟微信的结果推送，覆盖：
 * 提交 → 等推送 → 放行 / 拦截 / 超时 fail-closed / 上游报错 / 待检图出口。
 */
class WxSecCheckServiceTest {

    private static final byte[] IMG = new byte[] { 1, 2, 3 };
    private static final String TRACE = "TRACE-1";

    private WxSecCheckService service(boolean cloudCall, TokenManager tokens, long waitMs) {
        WxSecCheckService s = spy(new WxSecCheckService(tokens));
        ReflectionTestUtils.setField(s, "enabled", true);
        ReflectionTestUtils.setField(s, "cloudCall", cloudCall);
        ReflectionTestUtils.setField(s, "waitMs", waitMs);
        ReflectionTestUtils.setField(s, "publicBaseUrl", "https://demo.example.com");
        return s;
    }

    /** 桩掉提交调用，返回固定 trace_id（模拟微信受理） */
    private void stubSubmit(WxSecCheckService s) throws Exception {
        doReturn("{\"errcode\":0,\"errmsg\":\"ok\",\"trace_id\":\"" + TRACE + "\"}")
                .when(s).postJson(anyString(), anyString(), anyBoolean());
    }

    /** 模拟微信异步推送（延迟一点，走 awaitVerdict 的轮询窗口） */
    private static void pushNodeAsync(WxSecCheckService s, ObjectNode node, long delayMs) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            s.acceptPush(node);
        });
        t.setDaemon(true);
        t.start();
    }

    /** 模拟微信异步推送结果（顶层带 suggest 的简化形态） */
    private static void pushAsync(WxSecCheckService s, String traceId, String suggest, long delayMs) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("trace_id", traceId);
        node.put("suggest", suggest);
        pushNodeAsync(s, node, delayMs);
    }

    /** 微信真实推送报文（事件 wxa_media_check）——suggest 在 <detail> 里，不是顶层 */
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

    @Test
    void disabledServiceSkipsEverything() throws Exception {
        WxSecCheckService s = spy(new WxSecCheckService(mock(TokenManager.class)));
        ReflectionTestUtils.setField(s, "enabled", false);
        s.checkOrThrow(IMG, "u1");
        verify(s, never()).postJson(anyString(), anyString(), anyBoolean());
    }

    @Test
    void missingPublicBaseUrlIsRejected() {
        WxSecCheckService s = service(true, mock(TokenManager.class), 500);
        ReflectionTestUtils.setField(s, "publicBaseUrl", "");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.submit(IMG, "u1"));
        assertTrue(e.getMessage().contains("XPHOTO_PUBLIC_BASE_URL"), e.getMessage());
    }

    @Test
    void passResultAllowsThrough() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        pushAsync(s, TRACE, "pass", 100);
        s.checkOrThrow(IMG, "u1");   // 不抛即通过
        verify(s, times(1)).postJson(anyString(), anyString(), eq(true));
    }

    @Test
    void riskyResultIsBlocked() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        pushAsync(s, TRACE, "risky", 100);
        WxSecCheckService.RiskyContentException e =
                assertThrows(WxSecCheckService.RiskyContentException.class, () -> s.checkOrThrow(IMG, "u1"));
        assertEquals("risky", e.getSuggest());
    }

    @Test
    void reviewResultIsBlockedToo() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        pushAsync(s, TRACE, "review", 100);
        assertThrows(WxSecCheckService.RiskyContentException.class, () -> s.checkOrThrow(IMG, "u1"));
    }

    @Test
    void timeoutFailsClosed() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 400);   // 不等推送
        stubSubmit(s);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.checkOrThrow(IMG, "u1"));
        assertTrue(e.getMessage().contains("消息推送"), e.getMessage());
    }

    @Test
    void upstreamErrcodeSurfaces() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 500);
        doReturn("{\"errcode\":48001,\"errmsg\":\"api unauthorized\"}")
                .when(s).postJson(anyString(), anyString(), anyBoolean());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.submit(IMG, "u1"));
        assertTrue(e.getMessage().contains("48001"), e.getMessage());
    }

    @Test
    void cloudChannelFailureFallsBackToHttps() throws Exception {
        TokenManager tokens = mock(TokenManager.class);
        when(tokens.get()).thenReturn("TOKEN");
        WxSecCheckService s = service(true, tokens, 3000);
        doThrow(new IllegalStateException("HTTP 404"))
                .doReturn("{\"errcode\":0,\"trace_id\":\"" + TRACE + "\"}")
                .when(s).postJson(anyString(), anyString(), anyBoolean());
        pushAsync(s, TRACE, "pass", 100);
        s.checkOrThrow(IMG, "u1");
        verify(s, times(2)).postJson(anyString(), anyString(), anyBoolean());
        verify(s, times(1)).postJson(anyString(), startsWith("https://api.weixin.qq.com"), eq(false));
    }

    @Test
    void mediaEndpointServesUploadedBytesAndExpires() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 500);
        stubSubmit(s);
        s.submit(IMG, "u1");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(s).postJson(body.capture(), anyString(), anyBoolean());
        // 提交体里的 media_url 必须指向公网基址的取图端点
        String json = body.getValue();
        assertTrue(json.contains("https://demo.example.com/api/photo/sec-check/media/"), json);
        String token = json.replaceAll(".*/media/([0-9a-f]{32}).*", "$1");
        assertEquals(32, token.length(), token);

        WxSecCheckService.Media m = s.media(token);
        assertNotNull(m);
        assertArrayEquals(IMG, m.bytes);
        assertNull(s.media("not-a-token"));
    }

    @Test
    void acceptPushWithoutMatchingTraceReturnsFalse() {
        WxSecCheckService s = service(true, mock(TokenManager.class), 500);
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("trace_id", "unknown");
        node.put("suggest", "pass");
        assertFalse(s.acceptPush(node));
    }

    @Test
    void diagnoseReportsTraceAndResult() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        pushAsync(s, TRACE, "pass", 100);
        Map<String, Object> d = s.diagnose(IMG);
        assertEquals(Boolean.TRUE, d.get("ok"));
        assertEquals(TRACE, d.get("traceId"));
        assertEquals("pass", d.get("suggest"));
    }

    // ---------------------------------------------------- 推送报文解析（真实形态）

    /** 微信真实 XML 推送：suggest 嵌在 <detail> 里，必须经真实解析器也能取到 */
    @Test
    void xmlPushFromWeChatIsParsedThroughDetail() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        ObjectNode node = (ObjectNode) PushSignUtil.xmlToJson(new ObjectMapper(), xmlPush(TRACE, "pass"));
        pushNodeAsync(s, node, 100);
        // 解析不到 detail.suggest 的话，这里会等到超时抛 IllegalStateException
        s.checkOrThrow(IMG, "u1");
    }

    /** 同一份 XML 判为 risky —— 图片有问题，必须拦截 */
    @Test
    void xmlPushRiskyIsBlocked() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        ObjectNode node = (ObjectNode) PushSignUtil.xmlToJson(new ObjectMapper(), xmlPush(TRACE, "risky"));
        pushNodeAsync(s, node, 100);
        WxSecCheckService.RiskyContentException e =
                assertThrows(WxSecCheckService.RiskyContentException.class, () -> s.checkOrThrow(IMG, "u1"));
        assertEquals("risky", e.getSuggest());
    }

    // ---------------------------------------------------- 微信侧检测失败（不是"没结果"）

    /** 微信推送 errcode != 0（拉不到图）必须立刻报错，不能白等满 waitMs */
    @Test
    void upstreamCheckFailureFailsFastInsteadOfWaiting() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 5000);
        stubSubmit(s);
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("trace_id", TRACE);
        node.put("errcode", 40004);
        node.put("errmsg", "media download fail");
        long t0 = System.currentTimeMillis();
        pushNodeAsync(s, node, 100);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.checkOrThrow(IMG, "u1"));
        long cost = System.currentTimeMillis() - t0;
        assertTrue(e.getMessage().contains("微信侧检测失败"), e.getMessage());
        assertTrue(e.getMessage().contains("40004"), e.getMessage());
        assertTrue(cost < 2500, "应立刻失败而不是等满 5s，实际 " + cost + "ms");
    }

    /** 顶层 errcode=0 但 detail 报了策略错误、又没有 suggest —— 同样按失败处理 */
    @Test
    void detailErrcodeWithoutSuggestFails() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("trace_id", TRACE);
        node.put("errcode", 0);
        ObjectNode detail = node.putObject("detail");
        detail.put("strategy", "content_model");
        detail.put("errcode", 87014);
        pushNodeAsync(s, node, 100);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.checkOrThrow(IMG, "u1"));
        assertTrue(e.getMessage().contains("87014"), e.getMessage());
    }

    /** 报文里既无 suggest 也无错误码（异常推送）保持等待，最终按超时 fail-closed */
    @Test
    void pushWithoutVerdictKeepsWaiting() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 600);
        stubSubmit(s);
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("trace_id", TRACE);
        pushNodeAsync(s, node, 50);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.checkOrThrow(IMG, "u1"));
        assertTrue(e.getMessage().contains("消息推送"), e.getMessage());
    }

    /** 自检：微信侧检测失败要如实报出，不能报成"未收到推送" */
    @Test
    void diagnoseReportsUpstreamCheckFailure() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("trace_id", TRACE);
        node.put("errcode", 40004);
        node.put("errmsg", "media download fail");
        pushNodeAsync(s, node, 100);
        Map<String, Object> d = s.diagnose(IMG);
        assertEquals(Boolean.FALSE, d.get("ok"));
        assertEquals(Boolean.TRUE, d.get("received"));
        assertEquals(40004, d.get("errcode"));
        assertTrue(String.valueOf(d.get("msg")).contains("微信侧检测失败"), String.valueOf(d.get("msg")));
    }
}
