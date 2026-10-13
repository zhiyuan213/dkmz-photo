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
        when(users.verify(anyString(), nullable(String.class))).thenReturn("real-user");
        when(limiter.tryAcquire("real-user")).thenReturn("最近24小时已达30次");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new PhotoSegmentController(segment, processing, users, mock(PortraitCompositeService.class), limiter),
                new IdPhotoController(segment, processing, users, limiter)).build();
        String request = "{\"image\":\"photo\",\"loginCode\":\"code\"}";
        mvc.perform(post("/api/photo/segment").contentType("application/json").content(request))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mvc.perform(post("/idphoto/process").contentType("application/json").content(request))
                .andExpect(jsonPath("$.message").value("最近24小时已达30次"));
        verify(limiter, times(2)).tryAcquire("real-user");
        verifyNoInteractions(processing, segment);
    }
}
