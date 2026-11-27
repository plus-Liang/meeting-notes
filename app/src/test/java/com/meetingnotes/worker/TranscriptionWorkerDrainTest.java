package com.meetingnotes.worker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.74/P-19：worker 优雅停机排空三态（纯单元，不起 Spring、不连 Redis）。
 * 覆盖：①空闲立即中断（&lt;2s）②在跑任务排空（等待到 inflight 归 0）③超时中断并交启动恢复。
 * 行为依据：stop() 以 {@code inflightTaskId}（0=空闲）为排空依据，轮询 50ms，上限 drain-timeout-sec。
 */
@ExtendWith(MockitoExtension.class)
class TranscriptionWorkerDrainTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private S3Client s3;
    @Mock
    private com.meetingnotes.asr.AsrClient asrClient;
    @Mock
    private com.meetingnotes.ai.StructuringService structuringService;
    @Mock
    private com.meetingnotes.ai.TranscriptPunctuator punctuator;
    @Mock
    private PasteTextReader pasteTextReader;
    @Mock
    private MonoWavExtractor monoWavExtractor;
    @Mock
    private com.meetingnotes.webhook.WebhookDeliveryService webhookService;
    @Mock
    private com.meetingnotes.task.QueuePublisher queuePublisher;
    @Mock
    private com.meetingnotes.task.DeadTaskRecorder deadTaskRecorder;
    @Mock
    private TaskHeartbeat taskHeartbeat;
    @Mock
    private TaskAttempts taskAttempts; // T4.94：构造器连带（本类不触发 markFailure）

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(TranscriptionWorker.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private List<String> loggedTexts() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    private TranscriptionWorker newWorker() {
        // T4.77：构造器（TaskHeartbeat 为 mock，本类不触发 claim）；drain 用例用 ReflectionTestUtils.setField；T4.91：+reporter 共 17 参
        return new TranscriptionWorker(redis, jdbc, s3, asrClient,
                structuringService, punctuator, webhookService, pasteTextReader, monoWavExtractor,
                queuePublisher, deadTaskRecorder, taskHeartbeat, "meetingnotes:task-queue", 10, "meetingnotes", 3,
                new RedisUnavailableReporter(60, 2, java.time.Clock.systemUTC()), taskAttempts,
                new com.meetingnotes.worker.TaskTranscriber(asrClient)); /* T4.99：构造器连带 */
    }

    private long stopAndMeasure(TranscriptionWorker worker) {
        long start = System.currentTimeMillis();
        worker.stop();
        return System.currentTimeMillis() - start;
    }

    /** ①空闲：inflight=0 → 立即中断，stop() 必须 < 2000ms（空闲停机必须快）。 */
    @Test
    void idleStopReturnsImmediately() {
        TranscriptionWorker worker = newWorker();
        long elapsed = stopAndMeasure(worker);
        assertTrue(elapsed < 2000, "空闲停机耗时 " + elapsed + "ms，必须 < 2000ms");
        assertTrue(loggedTexts().stream().anyMatch(t -> t.contains("无在跑任务")),
                "空闲分支必须有一行可 grep 的日志：" + loggedTexts());
    }

    /** ②排空成功：inflight=7，800ms 后归 0 → stop() 等到归 0（≥700ms 且 <3000ms），日志含「等待任务 7」。 */
    @Test
    void drainWaitsForInflightTaskToFinish() throws Exception {
        TranscriptionWorker worker = newWorker();
        ReflectionTestUtils.setField(worker, "inflightTaskId", 7L);
        Thread finisher = new Thread(() -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ReflectionTestUtils.setField(worker, "inflightTaskId", 0L);
        });
        finisher.setDaemon(true);
        finisher.start();
        long elapsed = stopAndMeasure(worker);
        assertTrue(elapsed >= 700 && elapsed < 3000, "排空耗时 " + elapsed + "ms，应在 700~3000ms");
        assertTrue(loggedTexts().stream().anyMatch(t -> t.contains("等待任务 7")),
                "排空分支必须打「等待任务 7」：" + loggedTexts());
        assertFalse(loggedTexts().stream().anyMatch(t -> t.contains("超过")),
                "排空成功不应出现超时告警");
    }

    /** ③超时：drain=1s、inflight 保持 7 → 约 1s 后中断 workerThread 并打「超过 1s 未完成」。 */
    @Test
    void drainTimeoutInterruptsWorkerThread() throws Exception {
        TranscriptionWorker worker = newWorker();
        ReflectionTestUtils.setField(worker, "drainTimeoutSec", 1L); // T4.74：drain=1s（字段注入，纯 new 下等价构造传参）
        ReflectionTestUtils.setField(worker, "inflightTaskId", 7L);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread fakeWorker = new Thread(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        fakeWorker.setDaemon(true);
        fakeWorker.start();
        ReflectionTestUtils.setField(worker, "workerThread", fakeWorker);
        long elapsed = stopAndMeasure(worker);
        assertTrue(elapsed >= 900 && elapsed < 3000, "超时路径耗时 " + elapsed + "ms，应在 900~3000ms");
        assertTrue(loggedTexts().stream().anyMatch(t -> t.contains("超过 1s 未完成")),
                "超时分支必须打「超过 1s 未完成」：" + loggedTexts());
        assertTrue(interrupted.get(), "超时后 workerThread 必须被 interrupt");
    }
}
