package com.meetingnotes.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4.69 C3：HTML 必须 no-store（抽屉 iframe 复用同一 URL，不进缓存）、CSS/JS 保持 no-cache（未变 304）。 */
class StaticCacheConfigTest {

    @Test
    void htmlIsNoStoreAndCssJsStayNoCache() {
        ResourceHandlerRegistry registry = mock(ResourceHandlerRegistry.class);
        ResourceHandlerRegistration reg = mock(ResourceHandlerRegistration.class);
        when(registry.addResourceHandler(org.mockito.ArgumentMatchers.any(String[].class))).thenReturn(reg);
        when(reg.addResourceLocations(any(String[].class))).thenReturn(reg);
        when(reg.setCacheControl(any())).thenReturn(reg);

        new StaticCacheConfig().addResourceHandlers(registry);

        verify(registry).addResourceHandler("/*.html");
        verify(registry).addResourceHandler("/*.css", "/*.js");
        ArgumentCaptor<CacheControl> cc = ArgumentCaptor.forClass(CacheControl.class);
        verify(reg, times(2)).setCacheControl(cc.capture());
        assertEquals("no-store", cc.getAllValues().get(0).getHeaderValue(), "HTML 不进任何缓存");
        assertEquals("no-cache", cc.getAllValues().get(1).getHeaderValue(), "CSS/JS 保持条件请求校验");
    }
}
