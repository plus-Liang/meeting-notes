package com.meetingnotes.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/**
 * T4.69 C1：构建版本端点——{@code GET /api/v1/version}（免鉴权：ApiTokenFilter 精确豁免这一条 GET）。
 * 值来自 version.properties（Maven 资源过滤在构建时写入 version 与 build=&lt;短sha&gt;-&lt;yyyyMMdd-HHmmss&gt;），
 * build 随每次构建变化——抽屉深链 {@code task.html?id=<n>&v=<build>}（drawer.js）用它保证 iframe
 * 拿到当前构建的 task.html，而不是浏览器缓存里部署前的旧文档（K-137 后续，D-211）。
 * 只暴露 version/build 两个非敏感字段。
 */
@RestController
public class VersionController {

    private final String version;
    private final String build;

    public VersionController() throws IOException {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/version.properties")) {
            if (in != null) {
                p.load(in);
            }
        }
        this.version = blankToUnknown(p.getProperty("version"));
        this.build = blankToUnknown(p.getProperty("build"));
    }

    /** 构建过滤缺失/字段为空时回退 unknown，保证响应里 version/build 永远非空。 */
    private static String blankToUnknown(String v) {
        return v == null || v.isBlank() ? "unknown" : v.trim();
    }

    @GetMapping("/api/v1/version")
    public Map<String, String> version() {
        return Map.of("version", version, "build", build);
    }
}
