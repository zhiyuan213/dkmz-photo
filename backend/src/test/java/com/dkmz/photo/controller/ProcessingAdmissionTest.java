package com.dkmz.photo.controller;

import com.dkmz.photo.service.*;
import com.dkmz.photo.support.SegmentRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
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
                .when(secCheck).checkOrThrow(any(byte[].class), any());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new PhotoSegmentController(segment, processing, users, mock(PortraitCompositeService.class), limiter, secCheck)).build();
        // image 是 base64（此处内容无所谓，检测已 mock 为抛违规）
        mvc.perform(post("/api/photo/segment").contentType("application/json")
                        .content("{\"image\":\"cGhvdG8=\",\"loginCode\":\"code\"}"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("RISKY_CONTENT"));
        verifyNoInteractions(processing);
    }
}
