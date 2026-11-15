/* 录音纪要平台 · 共享前端逻辑（T1.6）
 * 纪律：只用相对路径（禁硬编码 host:port）；不引前端框架；不留 console.log；
 * 轮询 3 秒（D-024）；错误码只读不动（D-041/D-047：task_id/content_hash/duration_sec/size_bytes/reused/transcript/error_code）。
 */

/* 任务状态 → 列表页徽标文案 */
var STATUS_BADGE = {
  PENDING: '排队中',
  RUNNING: '转写中',
  DONE: '已完成',
  RETRYING: '重试中',
  DEAD: '失败'
};

/* 稳定错误码 → 详情页可读文案（D-047；新错误码必须同步这里，只增不改） */
var ERROR_TEXT = {
  ASR_ERROR: '语音识别服务异常或暂时不可用',
  STORAGE_ERROR: '文件存取异常',
  FFMPEG_ERROR: '音频处理异常',
  UNKNOWN_ERROR: '处理出现未知异常'
};

/* localStorage 任务索引：{ id, name, createdAt }。列表页只展示本浏览器提交过的任务。 */
function loadTaskIndex() {
  try {
    var raw = localStorage.getItem('mn_task_index');
    var list = raw ? JSON.parse(raw) : [];
    return Array.isArray(list) ? list : [];
  } catch (e) {
    return [];
  }
}

function saveTaskIndex(list) {
  localStorage.setItem('mn_task_index', JSON.stringify(list));
}

function rememberTask(id, name) {
  var list = loadTaskIndex().filter(function (item) { return item.id !== id; });
  list.unshift({ id: id, name: name || ('任务 ' + id), createdAt: new Date().toISOString() });
  saveTaskIndex(list.slice(0, 200));
}

/* GET 一个 JSON（T4.6 起统一走 apiFetch 自动带 token）；404 返回 { __httpStatus: 404, code, message }，网络错误返回 { __httpStatus: 0 } */
function getJson(url) {
  return apiFetch(url).then(function (resp) {
    return resp.json().then(function (data) {
      data.__httpStatus = resp.status;
      return data;
    });
  }, function () {
    return { __httpStatus: 0 };
  });
}

/* POST 一个 JSON（T4.1 批量重试等；T4.6 起统一走 apiFetch 自动带 token）；响应带 __httpStatus，网络错误 { __httpStatus: 0 } */
function postJson(url, body) {
  return apiFetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }).then(function (resp) {
    return resp.json().then(function (data) {
      data.__httpStatus = resp.status;
      return data;
    });
  }, function () {
    return { __httpStatus: 0 };
  });
}

function fmtBytes(n) {
  if (n === null || n === undefined) return '—';
  if (n >= 1048576) return (n / 1048576).toFixed(1) + ' MB';
  if (n >= 1024) return (n / 1024).toFixed(1) + ' KB';
  return n + ' B';
}

function fmtDuration(sec) {
  if (sec === null || sec === undefined) return '—';
  var m = Math.floor(sec / 60);
  var s = sec % 60;
  return m > 0 ? (m + ' 分 ' + s + ' 秒') : (s + ' 秒');
}

/* ISO 时间 → 本地可读（详情页 created_at，T4.14 渲染新增；字段口径不动） */
function fmtDate(iso) {
  if (!iso) return '—';
  try {
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso);
    var p = function (n) { return n < 10 ? '0' + n : String(n); };
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
      ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  } catch (e) {
    return String(iso);
  }
}

function statusText(status) {
  return STATUS_BADGE[status] || String(status);
}

/* 每 3 秒轮询一次（D-024：不用 WebSocket）。onUpdate 返回 true 或 isDone(data) 为真即停止。 */
function pollUntil(url, onUpdate, isDone) {
  var timer = null;
  var stopped = false;
  function tick() {
    if (stopped) return;
    getJson(url).then(function (data) {
      if (stopped) return;
      var stopNow = onUpdate(data) === true || isDone(data) === true;
      if (stopNow) return;
      timer = setTimeout(tick, 3000);
    });
  }
  tick();
  return { stop: function () { stopped = true; if (timer) clearTimeout(timer); } };
}

/* ===== T4.15 新增共享助手（只增不改既有函数；API 调用方式与字段口径不变） ===== */

/* 通用 JSON 请求（PATCH/DELETE 用；与 getJson/postJson 同一套 apiFetch/错误口径） */
function sendJson(url, method, body) {
  var opts = { method: method, headers: { 'Content-Type': 'application/json' } };
  if (body !== undefined && body !== null) opts.body = JSON.stringify(body);
  return apiFetch(url, opts).then(function (resp) {
    return resp.json().then(function (data) {
      data.__httpStatus = resp.status;
      return data;
    });
  }, function () {
    return { __httpStatus: 0 };
  });
}

/* 复制文本：navigator.clipboard 优先，textarea 兜底（file:// 或旧浏览器） */
function mnCopyText(text, onOk, onFail) {
  function fallback() {
    try {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      var ok = document.execCommand('copy');
      ta.remove();
      if (ok) { onOk && onOk(); } else { onFail && onFail(); }
    } catch (e) {
      onFail && onFail();
    }
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(function () { onOk && onOk(); }, fallback);
  } else {
    fallback();
  }
}

/* ===== 待办依据定位（与 ai/TodoGrounding.java 同口径，浏览器内重算，只读不改存储） =====
 * 归一化：去空白/标点（PUNCTUATION 同表）+ 小写；二元组 = 归一化后去重保序字符对；
 * 命中率 ≥ 0.5 视为有依据（<2 字符退化为整串包含）。文案固定「在原文中匹配到的句子」，
 * 匹配不到必须写「未找到直接依据」，绝不声称是模型给出的依据（D-104）。 */
var MN_PUNCTUATION = '，。！？；：、,.!?;:"\'“”‘’（）()《》〈〉【】[]…—-~· \t\r\n';

function mnNormalize(text) {
  if (text === null || text === undefined) return '';
  var out = '';
  for (var i = 0; i < text.length; i++) {
    var c = text.charAt(i);
    if (MN_PUNCTUATION.indexOf(c) >= 0) continue;
    out += c.toLowerCase();
  }
  return out;
}

function mnBigrams(normalized) {
  var seen = {}, list = [];
  for (var i = 0; i + 1 < normalized.length; i++) {
    var bg = normalized.substring(i, i + 2);
    if (!seen[bg]) { seen[bg] = true; list.push(bg); }
  }
  return list;
}

/* 逐字稿切句（保留标点在句尾）；空句丢弃 */
function mnSplitSentences(transcript) {
  if (!transcript) return [];
  var parts = transcript.split(/(?<=[。！？!?；;\n])/);
  var sents = [];
  for (var i = 0; i < parts.length; i++) {
    var s = parts[i].trim();
    if (s) sents.push(s);
  }
  return sents;
}

/* 找与 action 最匹配的句子：二元组命中率最高且 ≥0.5；返回 {index, sentence, rate} 或 null */
function mnGroundSentence(action, transcript) {
  var normAction = mnNormalize(action);
  if (!normAction) return null;
  var bigrams = mnBigrams(normAction);
  var sents = mnSplitSentences(transcript);
  var best = null;
  for (var i = 0; i < sents.length; i++) {
    var normSent = mnNormalize(sents[i]);
    var rate;
    if (!bigrams.length) {
      rate = normSent.indexOf(normAction) >= 0 ? 1 : 0;
    } else {
      var hits = 0;
      for (var j = 0; j < bigrams.length; j++) {
        if (normSent.indexOf(bigrams[j]) >= 0) hits++;
      }
      rate = hits / bigrams.length;
    }
    if (!best || rate > best.rate) best = { index: i, sentence: sents[i], rate: rate };
  }
  if (best && best.rate >= 0.5) return best;
  return null;
}

/* 关键词定位：归一化子串命中即算（关键词短，二元组口径会误伤） */
function mnGroundKeyword(keyword, transcript) {
  var norm = mnNormalize(keyword);
  if (!norm) return null;
  var sents = mnSplitSentences(transcript);
  for (var i = 0; i < sents.length; i++) {
    if (mnNormalize(sents[i]).indexOf(norm) >= 0) return { index: i, sentence: sents[i] };
  }
  return null;
}
