package com.meetingnotes.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * T4.73（审计 P-32）：启动期弱口令守卫——{@code API_TOKEN} 未设置 / 仍是默认值 / 短于 32 位
 * 一律拒绝启动。
 *
 * <p>背景：{@code ApiTokenFilter}（T4.37a 双凭据）把 Bearer 命中 {@code app.security.api-token}
 * 的请求直接当作 1 号管理员（管理员座位，能删所有人任务、能建号），而 {@code .env.example}
 * 里的默认值 {@code change-me-api-token} 是公开已知的——不改即上线等于把管理员权限开放给
 * 任何能访问 Web 端口的人。此前只有文档层「部署后请修改」，本守卫把它升级为启动期硬闸。
 *
 * <p>形态选择 {@link ApplicationRunner} 而不是 {@code ApiTokenFilter} 构造器校验：构造器加
 * 长度规则会把用 14 字符 {@code test-token-123} 直接构造过滤器的单测打断（ApiTokenFilterTest），
 * 也会把「认证实现」与「部署安全策略」两个关注点搅在一起。Runner 在 context 就绪后执行，
 * 抛出异常使启动失败、进程退出码非 0（Spring Boot 对 Runner 异常统一走启动失败路径）。
 *
 * <p>日志纪律：失败信息只允许出现「未设置 / 是否默认值 / 长度」三类事实，
 * <b>绝不打印 token 明文</b>（含 DEBUG 级）——弱口令之所以要换，就是因为它是公开的，
 * 日志再印一遍等于白换。
 *
 * <p>本类刻意<b>不在 application.yml 声明 app.security.allow-weak-api-token</b>：该键只用于测试
 * 上下文（NoKeyStartupTest 用 properties 覆盖）与极端运维应急（--app.security.allow-weak-api-token=true）；
 * 若写进 yml，门禁 M2 会要求它同时进 .env.example，等于把「跳过守卫」的开关放进复制即用的生产模板
 * （T4.73-fix）。
 */
@Component
public class WeakApiTokenGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WeakApiTokenGuard.class);

    /** .env.example 中的公开默认值（命中即拒绝）。 */
    static final String DEFAULT_TOKEN = "change-me-api-token";
    private static final int MIN_LENGTH = 32;

    private final String apiToken;
    private final boolean allowWeak;

    public WeakApiTokenGuard(@Value("${app.security.api-token:}") String apiToken,
                             @Value("${app.security.allow-weak-api-token:false}") boolean allowWeak) {
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.allowWeak = allowWeak;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (allowWeak) {
            log.warn("已通过 app.security.allow-weak-api-token 跳过弱口令校验（仅限测试）");
            return;
        }
        if (apiToken.isEmpty()) {
            throw new IllegalStateException("检测到 API_TOKEN 未设置；请设置至少 32 位随机字符串"
                    + "（生成示例：openssl rand -hex 32），然后重启。");
        }
        if (DEFAULT_TOKEN.equals(apiToken)) {
            throw new IllegalStateException("检测到 API_TOKEN 仍是 .env.example 的默认值；"
                    + "该值是公开的，等于把管理员权限开放给任何人。"
                    + "请改成至少 32 位随机字符串（openssl rand -hex 32）。");
        }
        if (apiToken.length() < MIN_LENGTH) {
            throw new IllegalStateException("检测到 API_TOKEN 过短（当前 " + apiToken.length()
                    + " 位，要求 ≥" + MIN_LENGTH + " 位）；请用 openssl rand -hex 32 重新生成。");
        }
        log.info("API_TOKEN 弱口令校验通过（长度 {} 位）", apiToken.length());
    }
}
