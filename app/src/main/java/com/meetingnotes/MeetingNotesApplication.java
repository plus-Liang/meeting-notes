package com.meetingnotes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MeetingNotes 应用入口。
 *
 * <p>定位：自托管「录音/网课 → 结构化纪要 + 待办」Web 应用。
 * <p>权威文档：仓库 docs/项目/录音纪要平台/（生成副本）；源文档在工作区 docs/ 目录（见 AGENT.md §1）
 * <p>硬约束：
 * <ul>
 *   <li>所有配置走环境变量，禁止硬编码路径/端口/key/并发数/模型名（spec N11 / D-021）</li>
 *   <li>不填任何 key 也要能完整跑通（spec N15 / D-020 三档 AI 降级）</li>
 *   <li>媒体处理全部在容器内，宿主机不装 ffmpeg（D-010）</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class MeetingNotesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeetingNotesApplication.class, args);
    }
}
