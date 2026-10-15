package com.dkmz.photo.service;

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

    /** 模拟微信异步推送结果（延迟一点，走 await 的轮询窗口） */
    private static void pushAsync(WxSecCheckService s, String traceId, String suggest, long delayMs) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            ObjectNode node = new ObjectMapper().createObjectNode();
            node.put("trace_id", traceId);
            node.put("suggest", suggest);
            s.acceptPush(node);
        });
        t.setDaemon(true);
        t.start();
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
}
