package com.dkmz.photo.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import com.dkmz.photo.support.SegmentRateLimiter;
import java.util.Map;

class PhotoConfigControllerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishesOnlyPublicReviewSetting(boolean reviewMode) throws Exception {
        SegmentRateLimiter limiter = mock(SegmentRateLimiter.class);
        when(limiter.rules()).thenReturn(Map.of("enabled", true, "perMinute", 10, "per24Hours", 30));
        MockMvcBuilders.standaloneSetup(new PhotoConfigController(reviewMode, limiter)).build()
                .perform(get("/api/photo/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.reviewMode").value(reviewMode))
                .andExpect(jsonPath("$.processingLimits.perMinute").value(10))
                .andExpect(jsonPath("$.processingLimits.per24Hours").value(30))
                .andExpect(jsonPath("$.length()").value(3));
    }
}
