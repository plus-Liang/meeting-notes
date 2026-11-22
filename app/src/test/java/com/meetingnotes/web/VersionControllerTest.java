package com.meetingnotes.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T4.69 C1：version 端点字段契约——只两个键、非空、build 形如 &lt;短sha|dev&gt;-&lt;yyyyMMdd-HHmmss&gt;（随构建变化）。 */
class VersionControllerTest {

    @Test
    void versionAndBuildAreNonEmptyAndBuildIsPerBuild() throws Exception {
        Map<String, String> body = new VersionController().version();

        assertEquals(2, body.size(), "只暴露 version/build 两个键（非敏感信息）");
        assertTrue(body.get("version") != null && !body.get("version").isBlank(), "version 非空");
        assertTrue(body.get("version").matches("[0-9A-Za-z.@_-]+"),
                "version 形如 0.1.0-SNAPSHOT（资源过滤产物）: " + body.get("version"));
        assertTrue(body.get("build").matches("[0-9A-Za-z._@-]+-\\d{8}-\\d{6}"),
                "build 形如 <短sha|dev>-<yyyyMMdd-HHmmss>（随构建变化，禁运行期 now()）: " + body.get("build"));
    }
}
