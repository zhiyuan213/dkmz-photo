package com.dkmz.photo.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PhotoConfigControllerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishesOnlyPublicReviewSetting(boolean reviewMode) throws Exception {
        MockMvcBuilders.standaloneSetup(new PhotoConfigController(reviewMode)).build()
                .perform(get("/api/photo/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.reviewMode").value(reviewMode))
                .andExpect(jsonPath("$.length()").value(2));
    }
}
