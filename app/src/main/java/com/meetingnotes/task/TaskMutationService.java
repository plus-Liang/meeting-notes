package com.meetingnotes.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.meetingnotes.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * T3.5 写端点服务层：改词回流 + 待办勾选/删除。纯 DB 操作（无 AI 依赖），事务内完成。
 *
 * <p>jsonb 铁律（契约定死）：todos/keywords 一律**读出 → Java 侧结构化改写 → 序列化 → 参数化写回**；
 * 禁止用 SQL {@code replace()} 改 jsonb（right_text 含引号/反斜杠时会产出非法 JSON 或改到键名）。
 * transcript/summary 虽是纯文本列，也统一走 Java 侧替换——替换计数（口径 e）与替换动作同源，不会数改不一致。
 *
 * <p>替换顺序（口径 a）：corrections 按 wrong_text 长度降序逐条应用（防"章总"/"章总经理"重叠二次替换），
 * 每条只扫一遍，不做循环替换。
 */
@Service
public class TaskMutationService {

    /** 文本长度上限（wrong_text/right_text 同列 VARCHAR(128)，留余量按 100 卡）。 */
    static final int MAX_TEXT_CHARS = 100;
    /** 单次改词条数上限。 */
    static final int MAX_CORRECTIONS = 50;

    private final JdbcTemplate jdbc;
    private final GlossaryRepository glossaryRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskMutationService(JdbcTemplate jdbc, GlossaryRepository glossaryRepository) {
        this.jdbc = jdbc;
        this.glossaryRepository = glossaryRepository;
    }

    /** 一条改词（wrong → right；均 trim 后非空、≤100 字、不相等，校验在 {@link #validateCorrections}）。 */
    public record Correction(String wrongText, String rightText) {
    }

    record TaskTexts(String transcript, String summary, String keywordsRaw, String todosRaw) {
    }

    /** 替换结果：四处新文本 + 分段计数（口径 e）。keywords/todos 为库内 null 时保持 null。 */
    record ReplaceOutcome(String transcript, String summary, String keywordsJson, String todosJson,
                          int transcriptHits, int summaryHits, int keywordElementHits, int todoFieldHits) {
    }

    public record CorrectionOutcome(long taskId, int replacedCount, List<GlossaryRepository.Entry> glossary) {
    }

    /** 勾选后的单条待办数据（task 层自有形状，不含 web 视图——分层：视图映射在 controller 做）。 */
    public record TodoData(String action, String owner, String deadline, boolean done) {
    }

    public record TodoUpdateResult(long taskId, int index, TodoData todoData) {
    }

    public record TodoDeleteResult(long taskId, int index, int removedCount, int remainingCount) {
    }

    /** 改词回流（事务）：替换本任务文本 → glossary upsert（每条都写，含 replaced=0 的条目——口径 c）。 */
    @Transactional
    public CorrectionOutcome applyCorrections(long taskId, long userId, List<Correction> corrections) {
        validateCorrections(corrections);
        requireTask(taskId, userId);
        TaskTexts texts = loadTexts(taskId);
        ReplaceOutcome outcome = applyAll(texts, corrections);
        jdbc.update("""
                UPDATE task_result SET transcript = ?, summary = ?, keywords = ?::jsonb, todos = ?::jsonb
                WHERE task_id = ?
                """, outcome.transcript(), outcome.summary(), outcome.keywordsJson(), outcome.todosJson(), taskId);
        List<GlossaryRepository.Entry> entries = new ArrayList<>();
        for (Correction correction : corrections) {
            entries.add(glossaryRepository.upsert(userId, correction.wrongText(), correction.rightText()));
        }
        int replacedCount = outcome.transcriptHits() + outcome.summaryHits()
                + outcome.keywordElementHits() + outcome.todoFieldHits();
        return new CorrectionOutcome(taskId, replacedCount, entries);
    }

    /** 勾选/取消（幂等：重复设同值仍 200），写库持久。 */
    @Transactional
    public TodoUpdateResult setTodoDone(long taskId, long userId, int index, boolean done) {
        requireTask(taskId, userId);
        JsonNode todos = loadTodosOr400(taskId);
        requireIndex(todos, index);
        JsonNode item = todos.get(index);
        if (item.isObject()) {
            ((ObjectNode) item).put("done", done);
        }
        jdbc.update("UPDATE task_result SET todos = ?::jsonb WHERE task_id = ?", todos.toString(), taskId);
        return new TodoUpdateResult(taskId, index, new TodoData(
                item.path("action").asText(null),
                item.path("owner").asText(null),
                item.path("deadline").asText(null),
                item.path("done").asBoolean(false)));
    }
    /** 删除单条（**非幂等**：index 会移位，重复删同一 index 第二次按越界 400——契约明示）。 */
    @Transactional
    public TodoDeleteResult deleteTodo(long taskId, long userId, int index) {
        requireTask(taskId, userId);
        JsonNode todos = loadTodosOr400(taskId);
        requireIndex(todos, index);
        int remaining = todos.size() - 1;
        ((ArrayNode) todos).remove(index);
        jdbc.update("UPDATE task_result SET todos = ?::jsonb WHERE task_id = ?", todos.toString(), taskId);
        return new TodoDeleteResult(taskId, index, 1, remaining);
    }

    // ---- 校验 ----

    private void validateCorrections(List<Correction> corrections) {
        if (corrections == null || corrections.isEmpty()) {
            throw new ApiException(400, "INVALID_PARAM", "corrections 不能为空");
        }
        if (corrections.size() > MAX_CORRECTIONS) {
            throw new ApiException(400, "INVALID_PARAM", "单次最多 " + MAX_CORRECTIONS + " 条改词");
        }
        for (Correction c : corrections) {
            String wrong = c.wrongText() == null ? "" : c.wrongText().trim();
            String right = c.rightText() == null ? "" : c.rightText().trim();
            if (wrong.isEmpty() || right.isEmpty()) {
                throw new ApiException(400, "INVALID_PARAM", "wrong_text/right_text 不能为空");
            }
            if (wrong.length() > MAX_TEXT_CHARS || right.length() > MAX_TEXT_CHARS) {
                throw new ApiException(400, "INVALID_PARAM", "wrong_text/right_text 不能超过 " + MAX_TEXT_CHARS + " 字");
            }
            if (wrong.equals(right)) {
                throw new ApiException(400, "INVALID_PARAM", "wrong_text 不能与 right_text 相同");
            }
        }
    }

    private void requireTask(long taskId, long userId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM task WHERE id = ? AND user_id = ?", Long.class, taskId, userId);
        if (ids.isEmpty()) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + taskId + " 不存在");
        }
    }

    private TaskTexts loadTexts(long taskId) {
        List<TaskTexts> rows = jdbc.query("""
                SELECT transcript, summary, keywords::text AS keywords, todos::text AS todos
                FROM task_result WHERE task_id = ?
                """, (rs, i) -> new TaskTexts(rs.getString("transcript"), rs.getString("summary"),
                rs.getString("keywords"), rs.getString("todos")), taskId);
        if (rows.isEmpty()) {
            throw new ApiException(400, "INVALID_PARAM", "任务没有可修改的结果数据");
        }
        TaskTexts texts = rows.get(0);
        if (texts.transcript() == null || texts.transcript().isBlank()) {
            throw new ApiException(400, "INVALID_PARAM", "任务没有可替换的转写文本");
        }
        return texts;
    }

    private JsonNode loadTodosOr400(long taskId) {
        List<String> rows = jdbc.query("SELECT todos::text AS todos FROM task_result WHERE task_id = ?",
                (rs, i) -> rs.getString("todos"), taskId);
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            throw new ApiException(400, "INVALID_PARAM", "该任务没有待办");
        }
        try {
            return mapper.readTree(rows.get(0));
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_PARAM", "该任务的待办数据不是合法 JSON");
        }
    }

    private static void requireIndex(JsonNode todos, int index) {
        if (!todos.isArray() || index < 0 || index >= todos.size()) {
            throw new ApiException(400, "INVALID_PARAM", "待办下标越界（注意：删除会使后续下标移位）");
        }
    }

    // ---- 纯替换逻辑（package-private 供单测；无 DB 依赖） ----

    static ReplaceOutcome applyAll(TaskTexts texts, List<Correction> corrections) {
        List<Correction> ordered = corrections.stream()
                .sorted(Comparator.comparingInt((Correction c) -> c.wrongText().length()).reversed())
                .toList();
        String transcript = texts.transcript();
        String summary = texts.summary();
        JsonNode keywords = parseOrNull(texts.keywordsRaw());
        JsonNode todos = parseOrNull(texts.todosRaw());
        int transcriptHits = 0;
        int summaryHits = 0;
        int keywordHits = 0;
        int todoHits = 0;
        for (Correction c : ordered) {
            String wrong = c.wrongText();
            String right = c.rightText();
            transcriptHits += countOccurrences(transcript, wrong);
            transcript = transcript.replace(wrong, right);
            if (summary != null) {
                summaryHits += countOccurrences(summary, wrong);
                summary = summary.replace(wrong, right);
            }
            if (keywords != null && keywords.isArray()) {
                ArrayNode array = (ArrayNode) keywords;
                for (int i = 0; i < array.size(); i++) {
                    JsonNode element = array.get(i);
                    if (element.isTextual() && element.asText().contains(wrong)) {
                        keywordHits++;
                        array.set(i, TextNode.valueOf(element.asText().replace(wrong, right)));
                    }
                }
            }
            if (todos != null && todos.isArray()) {
                for (JsonNode item : todos) {
                    if (!item.isObject()) {
                        continue;
                    }
                    for (String field : new String[]{"action", "owner", "deadline"}) {
                        JsonNode value = item.path(field);
                        if (value.isTextual() && value.asText().contains(wrong)) {
                            todoHits += countOccurrences(value.asText(), wrong);
                            ((ObjectNode) item).put(field, value.asText().replace(wrong, right));
                        }
                    }
                }
            }
        }
        return new ReplaceOutcome(transcript, summary,
                keywords == null ? null : keywords.toString(),
                todos == null ? null : todos.toString(),
                transcriptHits, summaryHits, keywordHits, todoHits);
    }

    private static JsonNode parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** 非重叠出现次数（与 String.replace 的单遍语义一致，计数口径 e）。 */
    static int countOccurrences(String text, String word) {
        if (text == null || word.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(word, index)) >= 0) {
            count++;
            index += word.length();
        }
        return count;
    }
}
