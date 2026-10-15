package com.dkmz.photo.service;

import com.dkmz.photo.support.PushSignUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        // 带扩展名的形态（微信实际来拉的地址）也要能命中
        assertNotNull(s.media(token + ".jpg"));
        assertNull(s.media("not-a-token"));
    }

    /** media_url 必须带图片扩展名（微信下载器对无扩展名地址处置不一） */
    @Test
    void mediaUrlCarriesImageExtension() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 500);
        stubSubmit(s);
        s.submit(IMG, "u1");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(s).postJson(body.capture(), anyString(), anyBoolean());
        assertTrue(body.getValue().matches(".*/api/photo/sec-check/media/[0-9a-f]{32}\\.jpg.*"),
                body.getValue());
    }

    /**
     * 提交体必须是官方 v2 语义：media_type=2 表示图片（v1 相反，曾按老文档写成 1），
     * 且 openid 必填。
     */
    @Test
    void submitBodyUsesV2Semantics() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 500);
        stubSubmit(s);
        s.submit(IMG, "oUser123");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(s).postJson(body.capture(), anyString(), anyBoolean());
        String json = body.getValue();
        assertTrue(json.contains("\"media_type\":2"), json);
        assertTrue(json.contains("\"version\":2"), json);
        assertTrue(json.contains("\"scene\":1"), json);
        assertTrue(json.contains("\"openid\":\"oUser123\""), json);
    }

    /** openid 必填：缺了要提前报清楚，而不是让微信回一个难懂的 40003 */
    @Test
    void missingOpenidIsRejectedBeforeSubmit() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 500);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.submit(IMG, null));
        assertTrue(e.getMessage().contains("openid"), e.getMessage());
        IllegalStateException e2 = assertThrows(IllegalStateException.class, () -> s.submit(IMG, "  "));
        assertTrue(e2.getMessage().contains("openid"), e2.getMessage());
        verify(s, never()).postJson(anyString(), anyString(), anyBoolean());
    }

    /** 官方 JSON 形态推送：综合结果在 result 里（detail 可能没有） */
    @Test
    void jsonPushWithResultObjectIsParsed() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("trace_id", TRACE);
        node.put("errcode", 0);
        ObjectNode result = node.putObject("result");
        result.put("suggest", "risky");
        result.put("label", 20002);
        pushNodeAsync(s, node, 100);
        WxSecCheckService.RiskyContentException e =
                assertThrows(WxSecCheckService.RiskyContentException.class, () -> s.checkOrThrow(IMG, "u1"));
        assertEquals("risky", e.getSuggest());
    }

    /** -1008（微信拉不到 media_url）时，错误提示要直接指向公网地址问题 */
    @Test
    void downloadErrorCodeGetsActionableHint() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("trace_id", TRACE);
        node.put("errcode", -1008);
        pushNodeAsync(s, node, 100);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.checkOrThrow(IMG, "u1"));
        assertTrue(e.getMessage().contains("XPHOTO_PUBLIC_BASE_URL"), e.getMessage());
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
        Map<String, Object> d = s.diagnose(IMG, "oUser");
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
        Map<String, Object> d = s.diagnose(IMG, "oUser");
        assertEquals(Boolean.FALSE, d.get("ok"));
        assertEquals(Boolean.TRUE, d.get("received"));
        assertEquals(40004, d.get("errcode"));
        assertTrue(String.valueOf(d.get("msg")).contains("微信侧检测失败"), String.valueOf(d.get("msg")));
    }

    // ------------------------------------------------------------ 单行汇总日志

    /** 抓取 WxSecCheckService 本次调用期间输出的 INFO 行 */
    private static List<ILoggingEvent> infoLines(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(WxSecCheckService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .collect(Collectors.toList());
    }

    /**
     * 需求（用户）：安全检测要「接口和结果打在同一行」。
     * 每次检测只输出一行 INFO，且必须同时含接口路径、trace_id、结果与判定。
     */
    @Test
    void emitsExactlyOneSummaryLineWithInterfaceAndResult() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        ObjectNode pass = new ObjectMapper().createObjectNode();
        pass.put("trace_id", TRACE);
        pass.put("suggest", "pass");
        pass.put("label", "100");
        pushNodeAsync(s, pass, 100);

        List<ILoggingEvent> lines = infoLines(() -> {
            try {
                s.checkOrThrow(IMG, "oUser123456");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(1, lines.size(), "每次检测只应有一行 INFO："
                + lines.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList()));
        String line = lines.get(0).getFormattedMessage();
        assertTrue(line.contains("接口=POST /wxa/media_check_async"), line);
        assertTrue(line.contains("trace_id=" + TRACE), line);
        assertTrue(line.contains("user=oUser123…"), line);
        assertTrue(line.contains("suggest=pass"), line);
        assertTrue(line.contains("label=100(正常)"), line);
        assertTrue(line.contains("耗时="), line);
        assertTrue(line.contains("判定=放行"), line);
    }

    /** 违规图同样只一行，且判定为「拦截」并带上标签中文名 */
    @Test
    void summaryLineForRiskyImageSaysBlockedWithLabel() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("trace_id", TRACE);
        node.put("suggest", "risky");
        node.put("label", "20002");
        pushNodeAsync(s, node, 100);

        List<ILoggingEvent> lines = infoLines(() -> {
            try {
                s.checkOrThrow(IMG, "oUser123456");
                throw new IllegalStateException("违规图必须被拦截");
            } catch (WxSecCheckService.RiskyContentException expected) {
                // 预期
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(1, lines.size(), "违规检测同样只应有一行 INFO");
        String line = lines.get(0).getFormattedMessage();
        assertTrue(line.contains("suggest=risky"), line);
        assertTrue(line.contains("label=20002(色情)"), line);
        assertTrue(line.contains("判定=拦截"), line);
    }

    /** 提交阶段就失败（如白名单未登记）也要有一行汇总，不能只剩调用方异常栈 */
    @Test
    void summaryLineAlsoEmittedWhenSubmitFails() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        doThrow(new IllegalStateException("HTTP 502 85107 URL不在白名单内"))
                .when(s).postJson(anyString(), anyString(), anyBoolean());

        List<ILoggingEvent> lines = infoLines(() -> {
            try {
                s.checkOrThrow(IMG, "oUser123456");
                throw new IllegalStateException("提交失败必须上抛");
            } catch (IllegalStateException expected) {
                // 预期
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(1, lines.size(), "提交失败也要留一行汇总");
        String line = lines.get(0).getFormattedMessage();
        assertTrue(line.contains("接口=POST /wxa/media_check_async"), line);
        assertTrue(line.contains("提交失败"), line);
        assertTrue(line.contains("85107"), line);
        assertTrue(line.contains("判定=拦截 fail-closed"), line);
    }

    /**
     * 汇总行必须带送检图来源与尺寸 —— 这是「送检的到底是不是完整原图」的审计证据：
     * 一旦误送裁剪工作图，尺寸会变成 960x1280 一类的长边，与手机原图（3000+）一眼可分，
     * 不必等审核驳回才发现（实测漏检就是这么发生的）。
     */
    @Test
    void summaryLineCarriesCheckedImageProvenance() throws Exception {
        WxSecCheckService s = service(true, mock(TokenManager.class), 3000);
        stubSubmit(s);
        pushAsync(s, TRACE, "pass", 100);

        List<ILoggingEvent> lines = infoLines(() -> {
            try {
                s.checkOrThrow(minimalJpeg(3024, 4032), "oUser123456", "完整原图");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(1, lines.size());
        String line = lines.get(0).getFormattedMessage();
        assertTrue(line.contains("图片=完整原图"), line);
        assertTrue(line.contains("3024x4032"), line);
        assertTrue(line.contains("KB"), line);
    }

    /** 只含 SOI + APP0 + SOF0 + EOI 的最小 JPEG：ImageProbe 只解析文件头 */
    private static byte[] minimalJpeg(int w, int h) {
        return new byte[] {
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00,
                0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xC0, 0x00, 0x11, 0x08,
                (byte) (h >> 8), (byte) h,
                (byte) (w >> 8), (byte) w,
                0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
                (byte) 0xFF, (byte) 0xD9,
        };
    }
}
