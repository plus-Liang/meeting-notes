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
