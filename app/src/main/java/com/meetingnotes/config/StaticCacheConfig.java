package com.meetingnotes.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * T4.69 C3：静态资源缓存策略分层（在 T4.68 的全局 no-cache 之上细分）：
 * <ul>
 *   <li>HTML（"/" 欢迎页经转发落到 index.html，同走这里）→ {@code no-store}：绝不进任何缓存——
 *       抽屉 iframe 与新标签复用 task.html 同一 URL，必须每次回源才能保证「部署后立即是当前构建」
 *       （K-137：no-cache 只约束之后的响应，不作废已存条目）；</li>
 *   <li>CSS/JS → 保持 {@code no-cache}：条件请求校验，未变 304、变了换新（T4.68 A1 口径不变）。</li>
 * </ul>
 * 显式 handler 按最具体模式优先匹配（AbstractUrlHandlerMapping），自动配置的 "/**" 兜底其余资源。
 */
@Configuration
public class StaticCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/*.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore());
        registry.addResourceHandler("/*.css", "/*.js")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}
