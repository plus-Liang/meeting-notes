package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.1 批量重试：只处理 DEAD（复活 SQL 与 D-049 重传复活逐字同口径 + 清死信 + 重入队，不消费配额）；
 * 非 DEAD / 不存在逐条 skipped 不整体失败；非法 body 400 INVALID_PARAM；入参去重。
 * T4.52 B2：入队失败必须 503 且该 id 回滚为 DEAD + 补回死信（不许假成功、也不许半复活卡死）。
 */
@ExtendWith(MockitoExtension.class)
class TaskRetryServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private TaskRequeueSupport taskRequeueSupport;

    private TaskRetryService newService() {
        return new TaskRetryService(jdbc, taskRequeueSupport);
    }

    private void stubStatus(long id, String status) {
        when(jdbc.query(contains("SELECT status FROM task"),
                ArgumentMatchers.<ResultSetExtractor<String>>any(), eq(id), eq(1L))).thenReturn(status);
    }


    @org.junit.jupiter.api.BeforeEach
    void setUpUserContextT437a() {
        com.meetingnotes.common.UserContext.set(1L);
    }

    @org.junit.jupiter.api.AfterEach
    void clearUserContextT437a() {
        com.meetingnotes.common.UserContext.clear();
    }

    @Test
    void revivesOnlyDeadAndSkipsOthersWithoutFailing() {
        stubStatus(7L, "DEAD");
        stubStatus(8L, "DONE");
        stubStatus(9L, null);

        TaskRetryService.RetryOutcome outcome = newService().retry(List.of(7L, 8L, 9L));

        assertEquals(List.of(7L), outcome.retried(), "只有 DEAD 被复活");
        assertEquals(2, outcome.skipped().size());
        assertEquals(8L, outcome.skipped().get(0).id());
        assertEquals("NOT_DEAD", outcome.skipped().get(0).reason());
        assertEquals(9L, outcome.skipped().get(1).id());
        assertEquals("NOT_FOUND", outcome.skipped().get(1).reason());
        verify(taskRequeueSupport).reviveDeadThenPublishOrRollback(7L);
        verify(jdbc, never()).update(contains("attempts = 0, status = 'PENDING'"), eq(8L));
        verify(taskRequeueSupport, never()).reviveDeadThenPublishOrRollback(8L);
    }

    @Test
    void duplicateIdsAreDeduplicated() {
        stubStatus(7L, "DEAD");

        TaskRetryService.RetryOutcome outcome = newService().retry(List.of(7L, 7L));

        assertEquals(List.of(7L), outcome.retried(), "同一 id 去重只重试一次");
        verify(taskRequeueSupport, times(1)).reviveDeadThenPublishOrRollback(7L);
    }

    @Test
    void invalidBodyRejectedWith400InvalidParam() {
        TaskRetryService service = newService();
        assertEquals(400, assertThrows(ApiException.class, () -> service.retry(null)).getStatus());
        assertEquals(400, assertThrows(ApiException.class,
                () -> service.retry(Collections.emptyList())).getStatus());
        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= TaskRetryService.MAX_IDS + 1; i++) {
            tooMany.add(i);
        }
        ApiException over = assertThrows(ApiException.class, () -> service.retry(tooMany));
        assertEquals(400, over.getStatus());
        assertTrue(over.getMessage().contains("ids"), "错误信息说明 ids 约束：" + over.getMessage());
        verify(jdbc, never()).update(ArgumentMatchers.anyString(), ArgumentMatchers.anyLong());
    }

    /** T4.52 B2 / T4.60 B2：入队失败必须 503（回滚 SQL 已收敛到 TaskRequeueSupportTest 钉住）。 */
    @Test
    void enqueueFailureRollsBackToDeadAndThrows503T452() {
        stubStatus(7L, "DEAD");
        doThrow(new ApiException(503, "QUEUE_UNAVAILABLE", "队列暂不可用，请稍后重试"))
                .when(taskRequeueSupport).reviveDeadThenPublishOrRollback(7L);

        ApiException ex = assertThrows(ApiException.class, () -> newService().retry(List.of(7L)));

        assertEquals(503, ex.getStatus(), "入队失败必须 503，不得假成功（T4.52）");
        assertEquals("QUEUE_UNAVAILABLE", ex.getCode());
        verify(jdbc, never()).update(contains("status = 'DEAD'"), eq(7L));
    }
}
