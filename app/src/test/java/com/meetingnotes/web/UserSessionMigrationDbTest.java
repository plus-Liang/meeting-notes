package com.meetingnotes.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.71 C1：V10 迁移的真实执行（mock jdbc 会假绿，K-129）——真实 H2（MODE=PostgreSQL）搭 pre-V10
 * 的 app_user（含一条在用令牌），然后**执行 V10 迁移文件本体**（从 classpath 读，防止测试副本漂移）：
 * ①表/索引建成；②存量非空 token_hash 搬进 user_session（升级后正在用的会话不掉线）；
 * ③占位管理员（token_hash NULL）不搬；④鉴权查询（filter 同款 join）能在新表上命中。
 */
class UserSessionMigrationDbTest {

    private static final String URL = "jdbc:h2:mem:usersession;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    private JdbcTemplate jdbc;

    @BeforeEach
    void applyV10OnPreV10Schema() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(URL));
        jdbc.execute("DROP TABLE IF EXISTS user_session");
        jdbc.execute("DROP TABLE IF EXISTS app_user");
        // pre-V10 的 app_user（V8 精简态）：1 号占位（token_hash NULL）+ alice 在用令牌
        // （H2 不认 TIMESTAMPTZ，测试库用 TIMESTAMP WITH TIME ZONE 等价物——真 PG 类型由隔离栈 A1 验）
        jdbc.execute("CREATE TABLE app_user (id BIGSERIAL PRIMARY KEY, username VARCHAR(32) NOT NULL, "
                + "password_hash VARCHAR(200) NOT NULL DEFAULT '!', role VARCHAR(16) NOT NULL DEFAULT 'USER', "
                + "token_hash CHAR(64), token_issued_at TIMESTAMP WITH TIME ZONE, "
                + "created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now())");
        jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (1, 'admin', '!', 'ADMIN')");
        // H2 的 IDENTITY 序列不随显式 id 前进（V8 用 setval）——夹具直接给 alice 显式 id=2
        jdbc.update("INSERT INTO app_user (id, username, password_hash, role, token_hash) VALUES (2, 'alice', 'h', 'USER', ?)",
                sha256Hex("alice-token"));

        for (String statement : readV10Statements()) {
            jdbc.execute(statement);
        }
    }

    /** 从 classpath 读 V10 迁移文件本体，剥注释行后按分号切语句（SQL 是唯一事实源，测试不复制）。 */
    private List<String> readV10Statements() throws Exception {
        java.io.InputStream stream = UserSessionMigrationDbTest.class
                .getResourceAsStream("/db/migration/V10__user_session.sql");
        if (stream == null) {
            throw new IllegalStateException("classpath 找不到 V10__user_session.sql");
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            List<String> out = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            for (String line; (line = r.readLine()) != null; ) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("--")) {
                    continue;
                }
                sb.append(line.replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE")).append('\n');
                if (t.endsWith(";")) {
                    out.add(sb.toString().trim());
                    sb.setLength(0);
                }
            }
            return out;
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void migrationMovesLiveTokenIntoUserSession() {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM user_session", Integer.class);
        assertEquals(1, rows, "只有 alice 的在用令牌被搬入；占位管理员（NULL）不搬");

        String moved = jdbc.queryForObject(
                "SELECT token_hash FROM user_session WHERE user_id = 2", String.class);
        assertEquals(sha256Hex("alice-token"), moved, "搬运的是哈希原文，不丢失");
    }

    @Test
    void filterStyleLookupHitsMigratedSessionAndOldColumnKept() {
        // ApiTokenFilter 同款鉴权查询在迁移后的库上命中（升级前签发的令牌继续可用）
        List<Long> ids = jdbc.query(
                "SELECT s.user_id FROM user_session s JOIN app_user u ON u.id = s.user_id WHERE s.token_hash = ?",
                (rs, i) -> rs.getLong(1), sha256Hex("alice-token"));
        assertEquals(1, ids.size());
        assertEquals(2L, ids.get(0));

        // 旧列保留且未被迁移清空（不再作为鉴权来源，但不 drop/不清）
        String kept = jdbc.queryForObject("SELECT token_hash FROM app_user WHERE id = 2", String.class);
        assertEquals(sha256Hex("alice-token"), kept, "app_user.token_hash 保留列不动");
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_name = 'USER_SESSION'", Integer.class) >= 1, "user_session 表已建成");
    }
}
