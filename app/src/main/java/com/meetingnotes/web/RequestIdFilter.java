package com.meetingnotes.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求标识（T4.86/P-26）：每次 HTTP 请求拿一个 X-Request-Id，写进 MDC（JSON 日志顶层字段
 * requestId）、回写响应头，让「入口 → 日志 → 任务行 → worker → webhook」用一条 grep 串起来。
 *
 * <p>取值规则（卡面 C1 逐字）：请求头 X-Request-Id 匹配 {@code ^[A-Za-z0-9._-]{8,64}$} → 原样用；
 * 缺失或不合法 → 服务端生成 32 位小写 hex（UUID 去 连字符）。**不回显非法值**（防头注入/日志注入），
 * 不写请求体/查询串进日志。
 *
 * <p>副作用三件缺一不可：MDC.put（LOG_JSON 下自动成为 JSON 顶层字段，同 taskId 的既有机制 T2.7）、
 * 响应头在 chain 之前设（保证 401/429 等错误响应也带 id）、finally 里 MDC.remove（Tomcat 线程池
 * 复用，漏了就是「A 的请求 id 出现在 B 的日志里」——本卡 C6 并发对照专门抓这个）。
 *
 * <p>@Order(HIGHEST_PRECEDENCE + 10)：必须早于 {@link ApiTokenFilter}，401/429 的日志也带 id。
 * 不吞异常、不改响应体、不特判静态资源（一律统一，简单可靠）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    /** 合法形态：字母/数字/点/下划线/连字符，8–64 位（客户端可自定义、也可完全不传）。 */
    private static final Pattern LEGAL = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String rid = (incoming != null && LEGAL.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, rid);
        response.setHeader(HEADER, rid); // chain 之前设：错误响应（401/429/500）也带 id
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY); // 线程池复用：不清就是跨请求串号（卡面硬要求）
        }
    }
}
