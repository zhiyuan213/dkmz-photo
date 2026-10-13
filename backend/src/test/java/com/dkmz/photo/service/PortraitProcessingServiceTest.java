package com.dkmz.photo.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;

class PortraitProcessingServiceTest {
    private final BdaSegmentService segment = mock(BdaSegmentService.class);
    private final CustomSegmentService masks = mock(CustomSegmentService.class);
    private final SkinBeautyService beauty = mock(SkinBeautyService.class);
    private PortraitProcessingService service(long ttl, long max) {
        return new PortraitProcessingService(segment, masks, beauty, true, "normal", ttl, max);
    }

    @Test
    void repeatsReuseResultButUsersAndBeautyModesAreIsolated() {
        when(segment.segment(anyString())).thenReturn("png");
        when(masks.getFaceSkinMask(anyString())).thenReturn("mask");
        when(beauty.beautify(anyString(), anyString(), any(IdPhotoBeautyService.Level.class))).thenReturn("beautified");
        PortraitProcessingService service = service(600, 100000);
        service.process("u", "image", false, null);
        service.process("u", "data:image/jpeg;base64,image", false, null);
        verify(segment, times(1)).segment("image");
        service.process("other", "image", false, null);
        service.process("u", "image", true, null);
        verify(segment, times(2)).segment("image");
        verify(segment).segment("beautified");
    }

    @Test
    void failedBeautyIsNotCached() {
        when(segment.segment(anyString())).thenReturn("png");
        when(masks.getFaceSkinMask(anyString())).thenThrow(new IllegalStateException("temporary"));
        PortraitProcessingService service = service(600, 100000);
        assertFalse(service.process("u", "image", true, null).beautyApplied());
        service.process("u", "image", true, null);
        verify(masks, times(2)).getFaceSkinMask("image");
    }

    @Test
    void cacheCanBeDisabledAndLargeResultsAreNotRetained() {
        when(segment.segment(anyString())).thenReturn("png");
        PortraitProcessingService service = service(0, 100000);
        service.process("u", "image", false, null);
        service.process("u", "image", false, null);
        verify(segment, times(2)).segment("image");
        PortraitProcessingService tiny = service(600, 1);
        tiny.process("u", "image", false, null);
        tiny.process("u", "image", false, null);
        verify(segment, times(4)).segment("image");
    }

    @Test
    void expiredPhotoResultsAreRemovedAndReprocessed() throws Exception {
        when(segment.segment(anyString())).thenReturn("png");
        PortraitProcessingService service = service(1, 100000);
        service.process("u", "image", false, null);
        Thread.sleep(1100);
        service.clearExpired();
        service.process("u", "image", false, null);
        verify(segment, times(2)).segment("image");
    }

    @Test
    void simultaneousIdenticalRequestsCallTencentOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(segment.segment(anyString())).thenAnswer(invocation -> {
            started.countDown(); release.await(5, TimeUnit.SECONDS); return "png";
        });
        PortraitProcessingService service = service(600, 100000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PortraitProcessingService.Result> first = pool.submit(() -> service.process("u", "image", false, null));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Future<PortraitProcessingService.Result> second = pool.submit(() -> service.process("u", "image", false, null));
            release.countDown();
            assertEquals(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
            verify(segment, times(1)).segment("image");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
