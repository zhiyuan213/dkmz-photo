package com.dkmz.photo.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PhotoUserVerifierTest {
    @Test
    void spoofedOpenidWithoutLoginCodeIsRejected() {
        PhotoUserVerifier verifier = new PhotoUserVerifier("appid", "secret", false);
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(null, "forged-user"));
    }

    @Test
    void verifiedIdentityMustMatchPlatformHeader() throws Exception {
        PhotoUserVerifier verifier = spy(new PhotoUserVerifier("appid", "secret", false));
        doReturn("{\"openid\":\"real-user\",\"session_key\":\"session\"}").when(verifier).get(anyString());
        assertEquals("real-user", verifier.verify("one-time-code", "real-user"));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify("one-time-code", "forged-user"));
    }

    @Test
    void businessErrorsAndTransportFailuresDoNotAuthorizeProcessing() throws Exception {
        PhotoUserVerifier verifier = spy(new PhotoUserVerifier("appid", "secret", false));
        doReturn("{\"errcode\":40029}").when(verifier).get(anyString());
        assertThrows(IllegalArgumentException.class, () -> verifier.verify("code", null));
        doThrow(new IllegalStateException("url-with-secret-must-not-leak")).when(verifier).get(anyString());
        Exception exception = assertThrows(IllegalStateException.class, () -> verifier.verify("code", null));
        assertFalse(exception.getMessage().contains("secret"));
    }
}
