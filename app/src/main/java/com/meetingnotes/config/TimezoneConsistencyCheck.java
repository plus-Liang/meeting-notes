package com.meetingnotes.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * F-23（T4.12 / D-097 / K-51：配置是否生效要验证在消费点）：启动完成后核对
 * <b>DB 实际时区</b>（{@code SHOW timezone}，即配额日切 {@code CURRENT_DATE} 的真实口径）
 * 与配置的 {@code TZ} 是否一致；不一致打 WARN 并给出修法。
 *
 * <p>背景：{@code TZ} 环境变量只对 initdb 之后的<b>新数据卷</b>生效；老卷的
 * {@code postgresql.conf} 可能仍写死 UTC——容器 {@code date} 显示 CST 但 DB 按 UTC 计日，
 * 配额实际在北京时间 08:00 重置，且 00:00–08:00 窗口里 {@code Retry-After}（JVM 本地日）
 * 与计费日打架。判据必须看 {@code SHOW timezone}，不能看容器 {@code date}。
 *
 * <p>选 WARN 不选 fail-fast 的理由：时区错位不损坏数据（时间戳列是 timestamptz，存绝对时间），
 * 只影响"日切时刻"，且可在线修复（{@code ALTER DATABASE ... SET timezone TO ...} 后重启 app）；
 * 为一个可自愈的运维漂移把整个应用挡在门外，代价大于收益，也违背"零成本可跑"的宽容默认（D-035）。
 * 海外自托管者也可能<b>有意</b>让 DB 用 UTC——那是他们的选择，不该被拦。
 */
@Component
public class TimezoneConsistencyCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TimezoneConsistencyCheck.class);

    private final JdbcTemplate jdbc;
    private final String configuredTz;

    public TimezoneConsistencyCheck(JdbcTemplate jdbc,
                                    @Value("${TZ:Asia/Shanghai}") String configuredTz) {
        this.jdbc = jdbc;
        this.configuredTz = configuredTz == null ? "" : configuredTz.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String dbTz = jdbc.queryForObject("SHOW timezone", String.class);
            if (TimezoneConsistencyCheck.isConsistent(dbTz, configuredTz)) {
                log.info("时区一致性预检通过：DB timezone={} 与配置 TZ={} 一致（K-51：在消费点验证）",
                        dbTz, configuredTz);
            } else {
                log.warn("时区一致性预检：DB timezone={} 与配置 TZ={} 不一致 → 配额日切（CURRENT_DATE）"
                                + "与 Retry-After 可能错位（F-23/D-097）。修复：ALTER DATABASE <库名> "
                                + "SET timezone TO '{}' 后重启 app；新数据卷请确认 compose 的 TZ 在首次 initdb 前已设置",
                        dbTz, configuredTz, configuredTz);
            }
        } catch (RuntimeException e) {
            // H2 测试上下文（NoKeyStartupTest 不认 SHOW timezone）/ DB 未就绪：跳过，不阻塞启动
            log.debug("时区一致性预检跳过：{}", e.getMessage());
        }
    }

    /** 归一化比较：trim + 小写 + 去 {@code Etc/} 前缀（{@code Etc/UTC} 与 {@code UTC} 等价）。 */
    static boolean isConsistent(String dbTimezone, String configuredTz) {
        return normalize(dbTimezone).equals(normalize(configuredTz));
    }

    private static String normalize(String tz) {
        if (tz == null) {
            return "";
        }
        String t = tz.trim().toLowerCase();
        return t.startsWith("etc/") ? t.substring(4) : t;
    }
}
