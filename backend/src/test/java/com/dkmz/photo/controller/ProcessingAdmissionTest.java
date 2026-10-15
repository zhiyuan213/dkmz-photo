package com.dkmz.photo.controller;

import com.dkmz.photo.service.*;
import com.dkmz.photo.support.SegmentRateLimiter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProcessingAdmissionTest {
    @Test
    void bothEndpointsRejectLimitedUserBeforeAnyTencentProcessing() throws Exception {
        BdaSegmentService segment = mock(BdaSegmentService.class);
        PortraitProcessingService processing = mock(PortraitProcessingService.class);
        PhotoUserVerifier users = mock(PhotoUserVerifier.class);
        SegmentRateLimiter limiter = mock(SegmentRateLimiter.class);
        // 内容安全默认关闭，让本用例只聚焦限流闸门（默认 boolean 即 false）
        WxSecCheckService secCheck = mock(WxSecCheckService.class);
        when(users.verify(anyString(), nullable(String.class))).thenReturn("real-user");
        when(limiter.tryAcquire("real-user")).thenReturn("最近24小时已达30次");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new PhotoSegmentController(segment, processing, users, mock(PortraitCompositeService.class), limiter, secCheck),
                new IdPhotoController(segment, processing, users, limiter, secCheck)).build();
        String request = "{\"image\":\"photo\",\"loginCode\":\"code\"}";
        mvc.perform(post("/api/photo/segment").contentType("application/json").content(request))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mvc.perform(post("/idphoto/process").contentType("application/json").content(request))
                .andExpect(jsonPath("$.message").value("最近24小时已达30次"));
        verify(limiter, times(2)).tryAcquire("real-user");
        verifyNoInteractions(processing, segment);
    }

    /** 内容安全闸门：违规图必须在进入腾讯云处理之前被拒（审核整改项，不得回退出片） */
    @Test
    void segmentRejectsRiskyImageBeforeTencentProcessing() throws Exception {
        BdaSegmentService segment = mock(BdaSegmentService.class);
        PortraitProcessingService processing = mock(PortraitProcessingService.class);
        PhotoUserVerifier users = mock(PhotoUserVerifier.class);
        SegmentRateLimiter limiter = mock(SegmentRateLimiter.class);
        WxSecCheckService secCheck = mock(WxSecCheckService.class);
        when(users.verify(anyString(), nullable(String.class))).thenReturn("real-user");
        when(limiter.tryAcquire("real-user")).thenReturn(null);
        when(segment.isEnabled()).thenReturn(true);
        when(secCheck.isEnabled()).thenReturn(true);
        doThrow(new WxSecCheckService.RiskyContentException("risky"))
                .when(secCheck).checkOrThrow(any(byte[].class), any(), any());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new PhotoSegmentController(segment, processing, users, mock(PortraitCompositeService.class), limiter, secCheck)).build();
        // image 是 base64（此处内容无所谓，检测已 mock 为抛违规）
        mvc.perform(post("/api/photo/segment").contentType("application/json")
                        .content("{\"image\":\"cGhvdG8=\",\"loginCode\":\"code\"}"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("RISKY_CONTENT"));
        verifyNoInteractions(processing);
    }

    /**
     * 送检的必须是**完整原图**（checkImage），不是裁剪工作图。
     *
     * 证件照构图只保留头部与肩部：若拿 image（裁剪图）去送检，落在裁剪框外的
     * 违规内容会先被我们自己的裁剪"洗掉"，微信只能判 pass —— 一张裸照裁剪后
     * 就是这么过检的。这条用例锁死送检素材，防止以后再退化回去。
     */
    @Test
    void segmentSendsWholePictureToSecCheckInsteadOfCroppedOne() throws Exception {
        WxSecCheckService secCheck = mock(WxSecCheckService.class);
        MockMvc mvc = admissionMvc(secCheck);
        String cropped = Base64.getEncoder().encodeToString(new byte[] { 9, 9, 9 });
        String wholePicture = Base64.getEncoder().encodeToString(new byte[] { 7, 7, 7, 7 });

        mvc.perform(post("/api/photo/segment").contentType("application/json")
                .content("{\"image\":\"" + cropped + "\",\"checkImage\":\"" + wholePicture
                        + "\",\"loginCode\":\"code\"}"));

        ArgumentCaptor<byte[]> checked = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(secCheck).checkOrThrow(checked.capture(), any(), source.capture());
        assertArrayEquals(new byte[] { 7, 7, 7, 7 }, checked.getValue(), "送检必须是整张原图");
        assertEquals("完整原图", source.getValue());
    }

    /** 端上漏传 checkImage 时退化为裁剪图，但必须留痕（source 标注 + WARN），便于发现版本不同步 */
    @Test
    void segmentFallsBackToCroppedPictureButMarksItInAuditLog() throws Exception {
        WxSecCheckService secCheck = mock(WxSecCheckService.class);
        MockMvc mvc = admissionMvc(secCheck);
        String cropped = Base64.getEncoder().encodeToString(new byte[] { 9, 9, 9 });

        mvc.perform(post("/api/photo/segment").contentType("application/json")
                .content("{\"image\":\"" + cropped + "\",\"loginCode\":\"code\"}"));

        ArgumentCaptor<byte[]> checked = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(secCheck).checkOrThrow(checked.capture(), any(), source.capture());
        assertArrayEquals(new byte[] { 9, 9, 9 }, checked.getValue());
        assertTrue(source.getValue().contains("缺 checkImage"), source.getValue());
    }

    /** 放行路径的公共装配：身份/限流/BDA/检测全部就绪，只留检测闸门给用例断言 */
    private static MockMvc admissionMvc(WxSecCheckService secCheck) {
        BdaSegmentService segment = mock(BdaSegmentService.class);
        PhotoUserVerifier users = mock(PhotoUserVerifier.class);
        SegmentRateLimiter limiter = mock(SegmentRateLimiter.class);
        when(users.verify(anyString(), nullable(String.class))).thenReturn("real-user");
        when(limiter.tryAcquire("real-user")).thenReturn(null);
        when(segment.isEnabled()).thenReturn(true);
        when(secCheck.isEnabled()).thenReturn(true);
        return MockMvcBuilders.standaloneSetup(
                new PhotoSegmentController(segment, mock(PortraitProcessingService.class), users,
                        mock(PortraitCompositeService.class), limiter, secCheck)).build();
    }
}
