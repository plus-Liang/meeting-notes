/* ===== 页头档案行 / 刊头 meta / 右索引栏（T4.41-fix；只读端点，失败该模块显示"—"，不编数） ===== */
(function () {
  function fill(id, text) {
    var el = document.getElementById(id);
    if (el) el.textContent = text;
  }
  /* 占位符统一用 en dash（em dash 会被检测器计入正文密度；失败态的"—"数量受控） */
  var p2 = function (n) { return n < 10 ? '0' + n : String(n); };

  /* 音频总时长：≥1 小时显示 小时+分，否则沿用 分+秒 */
  function fmtTotal(sec) {
    if (sec === null || sec === undefined) return '–';
    if (sec >= 3600) {
      var h = Math.floor(sec / 3600);
      var m = Math.floor((sec % 3600) / 60);
      return m ? (h + ' 小时 ' + m + ' 分') : (h + ' 小时');
    }
    return fmtDuration(sec);
  }
  /* 模块失败：只把数值位写成"—"（不编数；控制整栏 em dash 密度不触发检测器） */
  function failBlock(ids) { ids.forEach(function (id) { fill(id, '—'); }); }

  /* 今日额度 + AI 摘要档位（GET /api/v1/settings）；T4.98：包装成命名函数，粘贴提交成功后复用刷新 */
  function quotaFail() { failBlock(['railQuotaBig', 'railQuotaLeft', 'railTier']); }
  function loadRailQuota() {
    getJson('api/v1/settings').then(function (data) {
    if (data.__httpStatus !== 200 || !data.quota) { quotaFail(); return; }
    var q = data.quota;
    fill('railQuotaBig', String(q.used_tasks));
    fill('railQuotaSmall', '/ ' + q.tasks_limit + ' 次');
    fill('railQuotaLeft', Math.max(0, q.tasks_limit - q.used_tasks) + ' 次');
    var bar = document.getElementById('railQuotaBar');
    if (bar) bar.style.width = (q.tasks_limit > 0 ? Math.min(100, Math.round(q.used_tasks * 100 / q.tasks_limit)) : 0) + '%';
    var tier = data.ai_tier && data.ai_tier.preferred;
    fill('railTier', tier === 'CLOUD' ? '云端' : (tier === 'LOCAL' ? '本地' : (tier === 'EXTRACTIVE' ? '提取式' : '—')));
  }, quotaFail);
  }
  loadRailQuota();
  window.loadRailQuota = loadRailQuota; /* T4.98：IIFE 内私有 → 挂全局供粘贴提交后复用刷新 */

  /* 任务状态分布（GET /api/v1/tasks/counts） */
  function countsFail() { failBlock(['railDone', 'railActive', 'railDead']); }
  getJson('api/v1/tasks/counts').then(function (data) {
    if (data.__httpStatus !== 200) { countsFail(); return; }
    fill('railDone', String(data.done));
    fill('railActive', String(data.active));
    fill('railDead', String(data.dead));
    var total = data.total || 0;
    function seg(id, n) {
      var el = document.getElementById(id);
      if (el) el.style.width = (total > 0 ? Math.round(n * 100 / total) : 0) + '%';
    }
    seg('railSbDone', data.done);
    seg('railSbGo', data.active);
    seg('railSbBad', data.dead);
  }, countsFail);

  /* 归档/音频总时长/成功率/平均处理（GET /api/v1/stats） */
  function statsFail() { failBlock(['mastTotal', 'mastDuration', 'railSuccess', 'railRatio']); }
  getJson('api/v1/stats').then(function (data) {
    if (data.__httpStatus !== 200) { statsFail(); return; }
    var total = (data.tasks && data.tasks.total) || 0;
    fill('mastTotal', total + ' 条');
    fill('mastDuration', fmtTotal(data.duration_sec_total));
    fill('railSuccess', (data.success_rate === null || data.success_rate === undefined)
      ? '—' : Math.round(data.success_rate * 1000) / 10 + '%');
    fill('railRatio', (data.avg_processing_ratio === null || data.avg_processing_ratio === undefined)
      ? '—' : data.avg_processing_ratio.toFixed(2) + '×');
  }, statsFail);
})();

(function () {
  var MAX_UPLOAD_BYTES = 200 * 1024 * 1024; /* 与服务端 TASK_MAX_SIZE_BYTES 默认一致，仅前端提示，服务端仍是权威 */
  var MAX_BATCH_FILES = 20; /* 与服务端 BATCH_MAX_FILES 默认一致，仅前端提示，服务端仍是权威 */
  var btn = document.getElementById('submit');
  var fileInput = document.getElementById('file');
  var templateSelect = document.getElementById('templateSelect'); /* T4.22（D-119）："" = 自动识别，不传 template 字段 */
  var dropZone = document.getElementById('dropZone');
  var dropTitle = document.getElementById('dropTitle');
  var dropHint = document.getElementById('dropHint');
  var fileList = document.getElementById('fileList');
  var DROP_TITLE = dropTitle.textContent;
  var DROP_HINT = dropHint.textContent;
  var msg = document.getElementById('msg');
  var result = document.getElementById('result');
  var batchResult = document.getElementById('batch-result');
  /* T4.20：累积选择：元素 {file, key}，key = name|size|lastModified。
     分批次选择/拖拽一律**追加**（按 key 去重，重复静默跳过），不再覆盖前一次选择；
     提交以 selected 为准（不依赖 input.files 赋值，老浏览器兼容） */
  var selected = [];

  function show(kind, text) {
    msg.hidden = !text;
    msg.className = kind === 'error' ? 'error-text' : 'hint';
    msg.textContent = text;
  }

  function fileKey(f) {
    return f.name + '|' + f.size + '|' + (f.lastModified || 0);
  }

  /* T4.20：追加文件（选择器 change 与拖拽 drop 共用）。
     >200MB → 提示并跳过；累计超 20 → 丢弃并提示"一次最多 20 个，已忽略 N 个"；重复 → 静默跳过 */
  function addFiles(list) {
    var oversize = [];
    var ignored = 0;
    Array.prototype.slice.call(list || []).forEach(function (f) {
      if (f.size > MAX_UPLOAD_BYTES) {
        oversize.push(f.name);
        return;
      }
      if (selected.length >= MAX_BATCH_FILES) {
        ignored++;
        return;
      }
      var key = fileKey(f);
      for (var i = 0; i < selected.length; i++) {
        if (selected[i].key === key) {
          return; /* 重复静默跳过 */
        }
      }
      selected.push({ file: f, key: key });
    });
    if (oversize.length) {
      show('error', '文件 ' + oversize.join('、') + ' 超过 200 MB 上限，已跳过');
    } else if (ignored) {
      show('error', '一次最多 ' + MAX_BATCH_FILES + ' 个，已忽略 ' + ignored + ' 个');
    } else {
      show('info', '');
    }
    renderFileList();
  }

  function pickedFiles() {
    return selected.map(function (item) { return item.file; });
  }

  function renderFileList() {
    fileList.textContent = '';
    if (!selected.length) {
      fileList.hidden = true;
      dropZone.classList.remove('has-files');
      dropTitle.textContent = DROP_TITLE;
      dropHint.hidden = false;
      return;
    }
    selected.forEach(function (item, index) {
      var li = document.createElement('li');
      var name = document.createElement('span');
      name.className = 'file-name';
      name.textContent = item.file.name;
      name.title = item.file.name;
      var size = document.createElement('span');
      size.textContent = fmtBytes(item.file.size);
      size.className = 'num';
      var remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'file-remove';
      remove.textContent = '移除';
      remove.setAttribute('aria-label', '移除 ' + item.file.name);
      remove.addEventListener('click', function (e) {
        e.stopPropagation(); /* T4.20：DOM 移除后事件仍沿旧路径冒泡，截断防穿透 dropZone 的 guard */
        selected.splice(index, 1);
        renderFileList();
      });
      li.appendChild(name);
      li.appendChild(size);
      li.appendChild(remove);
      fileList.appendChild(li);
    });
    fileList.hidden = false;
    dropZone.classList.add('has-files');
    dropTitle.textContent = '已选 ' + selected.length + ' 个文件，点击或拖拽可继续添加';
    dropHint.hidden = true;
  }

  function clearSelection() {
    selected = [];
    renderFileList();
  }

  dropZone.addEventListener('click', function (e) {
    /* T4.20：框内列表（移除按钮等）的点击不触发选择器；contains 兜住"按钮已从 DOM 移除后
       事件沿旧路径冒泡"的 detached target（此时 closest('#fileList') 会返回 null） */
    if (!dropZone.contains(e.target) || e.target.closest('#fileList')) return;
    fileInput.click();
  });
  dropZone.addEventListener('keydown', function (e) {
    if (e.target.closest('#fileList')) return; /* T4.20：框内控件（移除按钮）自己的键盘行为优先，不打开选择器 */
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); }
  });
  ['dragover', 'dragenter'].forEach(function (ev) {
    dropZone.addEventListener(ev, function (e) { e.preventDefault(); dropZone.classList.add('dragover'); });
  });
  ['dragleave', 'dragend'].forEach(function (ev) {
    dropZone.addEventListener(ev, function () { dropZone.classList.remove('dragover'); });
  });
  dropZone.addEventListener('drop', function (e) {
    e.preventDefault();
    dropZone.classList.remove('dragover');
    if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length) {
      addFiles(e.dataTransfer.files); /* T4.20：拖拽同样追加，不替换 */
    }
  });
  fileInput.addEventListener('change', function () {
    addFiles(this.files);
    this.value = ''; /* 重置以便再次选择同一文件（选择已累积在 selected，不依赖 input.files） */
  });

  /* ===== T4.15：最近 3 条任务（台账行；失败/空 → 保留空态提示，不弹错） ===== */
  function fmtClockShort(iso) {
    if (!iso) return '—';
    try {
      var d = new Date(iso);
      if (isNaN(d.getTime())) return '—';
      var p2 = function (n) { return n < 10 ? '0' + n : String(n); };
      return p2(d.getMonth() + 1) + '-' + p2(d.getDate()) + ' ' + p2(d.getHours()) + ':' + p2(d.getMinutes());
    } catch (e) { return '—'; }
  }
  function stDotClass(status) {
    return status === 'DONE' ? 'dot-ok' : (status === 'DEAD' ? 'dot-bad' : (status === 'RETRYING' ? 'dot-bad' : (status === 'PENDING' ? 'dot-wait' : 'dot-go')));
  }
  /* escapeHtmlIndex 已收敛到共享 escape.js（T4.59）——属性上下文用 escapeAttr（含引号转义，比原行内补丁更严），
     文本上下文用 escapeHtml（与原 escapeHtmlIndex 逐字等价） */
  /* T4.98：包装成命名函数，粘贴提交成功后复用刷新（行为不变） */
  function loadRecentList() {
  getJson('api/v1/tasks?limit=3').then(function (data) {
    var empty = document.getElementById('recentEmpty');
    if (data.__httpStatus !== 200 || !Array.isArray(data.items) || !data.items.length) return; /* 保留空态提示 */
    var list = document.getElementById('recentList');
    list.innerHTML = data.items.map(function (item) {
      var name = item.original_name || ('任务 ' + item.id);
      return '<div class="task-row">' +
        '<span class="task-no">#' + item.id + '</span>' +
        '<span class="task-name"><span class="task-title"><a href="task.html?id=' + item.id +
        '" class="task-detail-link" data-task-id="' + item.id +
        '" data-task-name="' + escapeAttr(name) + '">' + escapeHtml(name) + '</a></span></span>' +
        '<span class="cell-num cell-dur">' + fmtDuration(item.duration_sec) + '</span>' +
        '<span class="cell-num cell-time">' + fmtClockShort(item.created_at) + '</span>' +
        '<span class="st"><i class="st-dot ' + stDotClass(item.status) + '"></i>' + statusText(item.status) + '</span>' +
        '<span class="task-side"><a href="task.html?id=' + item.id + '" class="task-detail-link" data-task-id="' + item.id +
        '" data-task-name="' + escapeAttr(name) + '" aria-label="查看任务 #' + item.id + ' 详情">详情</a></span>' +
        '</div>';
    }).join('');
    if (empty) empty.hidden = true;
    /* 刊头 meta：最近一条 */
    var first = data.items[0];
    if (first && first.created_at) {
      var el = document.getElementById('mastRecent');
      if (el) el.textContent = fmtClockShort(first.created_at);
    }
  }, function () { /* 静默：保留空态提示 */ });
  }
  loadRecentList();
  /* T4.38：详情站内抽屉（列表 URL 不变、不新开标签） */
  mnBindDrawerLinks(document.getElementById('recentList'));

  /* ===== T4.15：示例文本入口（仅 remaining_tasks ≥ 1 可用；0 任务额度时禁用+提示） =====
     T4.98/C2：主按钮从「复制到剪贴板」改为「填入粘贴框」（页面上从此真的有粘贴处）；
     T4.98/C1：同一份 settings 数据同时锁粘贴按钮（不新发请求）。 */
  var SAMPLE_TEXT = '本周例会纪要：一、发布计划：新版定于下周四发布，发布前完成回归测试。二、问题跟进：客服反馈的导出乱码与列表加载慢两个问题，由李四在本周五前给出修复方案。三、分工：张三负责回归测试清单，本周五前输出初稿；王五负责发布说明文档。请各位在本周三前确认自己的分工。';
  var pasteText = document.getElementById('pasteText');
  var pasteHint = document.getElementById('pasteHint');
  var pasteSubmitBtn = document.getElementById('pasteSubmitBtn');
  function setPasteQuotaLocked() { /* T4.98/C1：配额为 0 → 粘贴按钮禁用 + 提示 */
    pasteSubmitBtn.disabled = true;
    pasteHint.textContent = '今日任务额度已用完，明天再试。';
  }
  getJson('api/v1/settings').then(function (data) {
    var btn2 = document.getElementById('copySampleBtn');
    var hint = document.getElementById('copySampleHint');
    var remain = (data && data.__httpStatus === 200 && data.quota) ? data.quota.remaining_tasks : null;
    if (remain !== null && remain < 1) {
      btn2.disabled = true;
      hint.textContent = '今日任务额度已用尽，明天日切后再试。';
      setPasteQuotaLocked();
    }
  });
  document.getElementById('copySampleBtn').addEventListener('click', function () {
    var hint = document.getElementById('copySampleHint');
    var box = document.querySelector('details.paste-box');
    if (box) box.open = true; /* T4.98/C2：填入并展开，用户直接看得见 */
    pasteText.value = SAMPLE_TEXT;
    pasteText.focus();
    hint.textContent = '已填入粘贴框，点「提交为文本任务」即提交（消耗 1 个任务额度）。';
  });

  /* ===== T4.98/C1：粘贴文本任务提交（前端校验 → sendJson → 复用既有刷新，不新增轮询） ===== */
  pasteSubmitBtn.addEventListener('click', function () {
    if (pasteSubmitBtn.disabled) return;
    var value = pasteText.value.trim();
    if (!value) { pasteHint.textContent = '请先粘贴一段文字'; return; }
    if (value.length > 100000) { pasteHint.textContent = '最多 100000 字符'; return; }
    pasteSubmitBtn.disabled = true;
    pasteHint.textContent = '';
    sendJson('api/v1/tasks', 'POST', { text: value }).then(function (data) {
      pasteSubmitBtn.disabled = false;
      if (data.__httpStatus === 201) {
        pasteText.value = '';
        pasteHint.textContent = '已提交，任务 #' + data.task_id + '（消耗 1 个任务额度）';
        rememberTask(data.task_id, '粘贴文本');
        loadRecentList(); /* T4.98：复用既有刷新（不新增轮询） */
        loadRailQuota();
      } else {
        pasteHint.textContent = data.message || ('提交失败（HTTP ' + data.__httpStatus + '）');
      }
    }, function () {
      pasteSubmitBtn.disabled = false;
      pasteHint.textContent = '服务暂时不可用，请稍后重试';
    });
  });

  function renderBatch(data) {
    var summary = data.summary || {};
    var items = data.items || [];
    document.getElementById('batch-summary-line').textContent =
      '批量结果：受理 ' + (summary.accepted || 0) + '（其中复用 ' + (summary.reused || 0) +
      '）、失败 ' + (summary.failed || 0) +
      '；总时长 ' + fmtDuration(summary.total_duration_sec) +
      '，预计 ' + fmtDuration(summary.estimated_sec) + '（仅预估，不承诺）';
    var list = document.getElementById('batch-items');
    list.textContent = '';
    items.forEach(function (item) {
      var li = document.createElement('li');
      if (item.task_id) {
        li.textContent = '✓ ' + item.file_name + ' → 任务 #' + item.task_id +
          '（' + statusText(item.status) + (item.reused ? '，命中已有任务' : '') + '）';
        rememberTask(item.task_id, item.file_name);
      } else {
        li.textContent = '✗ ' + item.file_name + '：[' + (item.code || 'UNKNOWN') + '] ' + (item.message || '');
      }
      list.appendChild(li);
    });
    batchResult.hidden = false;
  }

  btn.addEventListener('click', function () {
    var files = pickedFiles();
    if (!files.length) { show('error', '请先选择一个或多个音/视频文件'); return; }
    for (var i = 0; i < files.length; i++) {
      if (files[i].size > MAX_UPLOAD_BYTES) {
        show('error', '文件 ' + files[i].name + '（' + fmtBytes(files[i].size) + '）超过 200 MB 上限，请切分后再试');
        return;
      }
    }
    btn.disabled = true;
    result.hidden = true;
    batchResult.hidden = true;

    /* 单文件走原 /api/v1/tasks（旧行为一字不改）；多个文件走批量端点 */
    if (files.length === 1) {
      var form = new FormData();
      form.append('file', files[0]);
      if (templateSelect.value) form.append('template', templateSelect.value); /* T4.22：显式选了模板才传 */
      apiFetch('api/v1/tasks', { method: 'POST', body: form }).then(function (resp) {
        return resp.json().then(function (data) { data.__httpStatus = resp.status; return data; });
      }).then(function (data) {
        btn.disabled = false;
        if (data.__httpStatus === 201) {
          rememberTask(data.task_id, files[0].name);
          document.getElementById('ok-line').textContent =
            '已受理：任务 #' + data.task_id + '（' + statusText(data.status) +
            '，时长 ' + fmtDuration(data.duration_sec) + '）' +
            (data.reused ? ' · 命中已有任务，未重复处理' : '');
          document.getElementById('detail-link').href = 'task.html?id=' + data.task_id;
          result.hidden = false;
          clearSelection(); /* T4.20：提交成功清空选择并恢复初始文案 */
          show('info', '');
        } else {
          show('error', '提交失败（' + (data.code || data.__httpStatus) + '）：' + (data.message || '未知错误'));
        }
      }, function () {
        btn.disabled = false;
        show('error', '服务暂时不可用，请稍后重试');
      });
      return;
    }

    if (files.length > MAX_BATCH_FILES) {
      btn.disabled = false;
      show('error', '一次最多提交 ' + MAX_BATCH_FILES + ' 个文件（当前选了 ' + files.length + ' 个），请分批提交');
      return;
    }
    show('info', '批量上传中…（共 ' + files.length + ' 个文件，大文件可能需要一些时间）');
    var batchForm = new FormData();
    files.forEach(function (f) { batchForm.append('files', f); });
    if (templateSelect.value) batchForm.append('template', templateSelect.value); /* T4.22：显式选了模板才传 */
    apiFetch('api/v1/tasks/batch', { method: 'POST', body: batchForm }).then(function (resp) {
      return resp.json().then(function (data) { data.__httpStatus = resp.status; return data; });
    }).then(function (data) {
      btn.disabled = false;
      if (data.__httpStatus === 200) {
        renderBatch(data);
        clearSelection(); /* T4.20：提交成功清空选择并恢复初始文案 */
        show('info', '');
      } else {
        show('error', '批量提交失败（' + (data.code || data.__httpStatus) + '）：' + (data.message || '未知错误'));
      }
    }, function () {
      btn.disabled = false;
      show('error', '服务暂时不可用，请稍后重试');
    });
  });
})();

/* ===== T4.30：浏览器内录音（MediaRecorder → 现有上传链路；后端契约不动） =====
 * 只复用现有 POST /api/v1/tasks（multipart：file + template，与上方文件上传同字段同写法）；
 * 1s timeslice 分片收集（切后台/长录音不丢数据）；60 分钟 / 200 MB 到点自动停止
 * （与服务端单任务默认上限一致，仅前端提示，服务端仍是权威）；
 * 权限拒绝等一律页面可读提示，不抛未捕获异常；提交失败保留 Blob 可就地重试，不许丢录音。
 * T4.103：新增「录制标签页声音」（getDisplayMedia 只取音轨，画面轨立刻停掉绝不外传）；
 * 麦克风/标签页两路共用同一录制核心与收尾，命名按 captureMode 区分（「浏览器录音」拆为「麦克风录音」「标签页录音」）。 */
(function () {
  var MAX_REC_SEC = 60 * 60;             /* 与服务端 60 分钟上限一致 */
  var MAX_REC_BYTES = 200 * 1024 * 1024; /* 与服务端 200 MB 上限一致 */
  var MIMES = [
    { type: 'audio/webm;codecs=opus', ext: 'webm' },
    { type: 'audio/webm', ext: 'webm' },
    { type: 'audio/ogg;codecs=opus', ext: 'ogg' },
    { type: 'audio/mp4', ext: 'm4a' }
  ];

  var recBtn = document.getElementById('recBtn');
  var recLive = document.getElementById('recLive');
  var recTimer = document.getElementById('recTimer');
  var recMsg = document.getElementById('recMsg');
  var recResult = document.getElementById('recResult');
  var recName = document.getElementById('recName');
  var recSubmit = document.getElementById('recSubmit');
  var recDiscard = document.getElementById('recDiscard');
  var recIntro = document.getElementById('recIntro');
  var templateSelect = document.getElementById('templateSelect');
  var recTabBtn = document.getElementById('recTabBtn');
  var recTabHint = document.getElementById('recTabHint');

  /* T4.103（B2）：标签页音频能力探测——没有 getDisplayMedia 就隐藏按钮并换提示，不禁用整页 */
  if (!navigator.mediaDevices || typeof navigator.mediaDevices.getDisplayMedia !== 'function') {
    recTabBtn.hidden = true;
    recTabHint.textContent = '当前浏览器不支持录制标签页声音（需要 Chrome / Edge）。可以改用「文件」上传，或按 README 用系统工具录。';
  }

  var REC_MIME = null;
  if (window.MediaRecorder && navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
    for (var i = 0; i < MIMES.length; i++) {
      if (MediaRecorder.isTypeSupported(MIMES[i].type)) { REC_MIME = MIMES[i]; break; }
    }
  }
  if (!REC_MIME) {
    recBtn.disabled = true;
    recIntro.textContent = '当前浏览器不支持浏览器内录音（需支持 MediaRecorder 的现代浏览器）。仍可使用「文件」入口上传。';
    return;
  }

  var state = 'idle'; /* idle | recording | ready | uploading */
  var recorder = null;
  var stream = null;
  var chunks = [];
  var recBytes = 0;
  var startTime = 0;
  var timerId = null;
  var blob = null;
  var recExt = REC_MIME.ext;
  var pendingReason = null;
  var captureMode = 'mic'; /* T4.103：本段录音来源（mic=麦克风 / tab=标签页），决定命名 */

  function showRec(kind, text) {
    recMsg.hidden = !text;
    recMsg.className = kind === 'error' ? 'error-text' : 'hint';
    recMsg.textContent = text;
  }

  function fmtClock(sec) {
    var h = Math.floor(sec / 3600);
    var m = Math.floor((sec % 3600) / 60);
    var s = sec % 60;
    var p = function (n) { return n < 10 ? '0' + n : String(n); };
    return h > 0 ? (h + ':' + p(m) + ':' + p(s)) : (p(m) + ':' + p(s));
  }

  function releaseStream() {
    if (stream) {
      try { stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) { /* 释放失败不影响主流程 */ }
      stream = null;
    }
  }

  function clearTimer() {
    if (timerId) { clearInterval(timerId); timerId = null; }
  }

  function stopRecorder() {
    try { if (recorder && recorder.state !== 'inactive') recorder.stop(); } catch (e) { /* 已停止则忽略 */ }
    releaseStream();
    clearTimer();
  }

  /* 到点/超限自动停；ondataavailable 内也判一次（后台标签页定时器被节流时它仍在持续触发） */
  function enforceLimits() {
    if (state !== 'recording') return;
    var sec = Math.floor((Date.now() - startTime) / 1000);
    if (sec >= MAX_REC_SEC) { pendingReason = '已达 60 分钟上限，自动停止。'; stopRecorder(); return; }
    if (recBytes >= MAX_REC_BYTES) { pendingReason = '录音体积已达 200 MB 上限，自动停止。'; stopRecorder(); }
  }

  /* T4.103：麦克风 / 标签页两路共用的「开始录制」核心
   * （同一 REC_MIME、同一 1s 分片、同一 enforceLimits/chunks/recBytes、同一 500ms 计时器、同一 stopRecorder 收尾） */
  function startRecorderOn(s) {
    stream = s;
    chunks = [];
    recBytes = 0;
    pendingReason = null;
    try {
      recorder = new MediaRecorder(s, { mimeType: REC_MIME.type });
    } catch (e) {
      releaseStream();
      showRec('error', '无法开始录音（' + REC_MIME.type + ' 不受当前环境支持）。请换用 Chrome / Edge 等现代浏览器。');
      return false;
    }
    recorder.ondataavailable = function (e) {
      if (!e.data || !e.data.size) return;
      chunks.push(e.data);
      recBytes += e.data.size;
      enforceLimits();
    };
    recorder.onstop = onStopped;
    recorder.onerror = function () {
      if (state === 'recording') { pendingReason = '录音过程出错，'; stopRecorder(); }
    };
    recorder.start(1000); /* 1s 分片：切后台不丢、超限可及时停 */
    startTime = Date.now();
    state = 'recording';
    recBtn.textContent = '停止录制';
    recLive.hidden = false;
    recResult.hidden = true;
    recTimer.textContent = '00:00';
    recTabBtn.disabled = true; /* 互斥：同一时刻只允许一路录音 */
    timerId = setInterval(function () {
      var sec = Math.floor((Date.now() - startTime) / 1000);
      recTimer.textContent = fmtClock(sec);
      enforceLimits();
    }, 500);
    return true;
  }

  recBtn.addEventListener('click', function () {
    if (state === 'recording') {
      pendingReason = null;
      stopRecorder();
      recLive.hidden = true;
      showRec('info', '正在停止…');
      return;
    }
    if (state !== 'idle') return;
    navigator.mediaDevices.getUserMedia({ audio: true }).then(function (s) {
      captureMode = 'mic';
      if (!startRecorderOn(s)) return;
      showRec('info', '录音中…说完点「停止录制」。中途切到别的标签页不受影响。');
      s.getAudioTracks().forEach(function (t) {
        t.onended = function () {
          if (state === 'recording') { pendingReason = '麦克风被占用或已断开，录音自动停止。'; stopRecorder(); }
        };
      });
    }, function (err) {
      var name = err && err.name;
      if (name === 'NotAllowedError' || name === 'PermissionDeniedError' || name === 'SecurityError') {
        showRec('error', '麦克风权限被拒绝，无法录音。请在浏览器地址栏的权限图标里允许「麦克风」后，重新点「开始录制」。');
      } else if (name === 'NotFoundError' || name === 'DevicesNotFoundError' || name === 'OverconstrainedError') {
        showRec('error', '没有找到可用的麦克风设备。请检查麦克风连接后重试，或改用「文件」入口上传。');
      } else {
        showRec('error', '无法开始录音（' + (name || '未知原因') + '）。请重试，或改用「文件」入口上传。');
      }
    });
  });

  /* T4.103（B2）：录制标签页声音——getDisplayMedia 只取音轨，画面轨立刻停掉（画面不保留/不上传）；
   * 未勾「分享标签页音频」/ 用户取消一律不发请求；点浏览器的「停止共享」与手动停止走同一条收尾，已录 Blob 保留。 */
  async function startTabCapture() {
    if (state !== 'idle') return;
    var s;
    try {
      s = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true });
    } catch (e) {
      if (e && e.name === 'NotAllowedError') {
        showRec('error', '已取消或未授权录制（不会创建任务）。');
      } else {
        showRec('error', '无法开始录制标签页声音：' + (e && e.name ? e.name : '未知原因') + '。可改用「文件」上传。');
      }
      return;
    }
    s.getVideoTracks().forEach(function (t) { t.stop(); }); /* 只留音频：绝不把画面交出去 */
    var audios = s.getAudioTracks();
    if (audios.length === 0) {
      try { s.getTracks().forEach(function (t) { t.stop(); }); } catch (e2) { /* 已停则忽略 */ }
      showRec('error', '没有选择「分享标签页音频」，已取消（不会创建任务）。');
      return;
    }
    captureMode = 'tab';
    if (!startRecorderOn(s)) return;
    audios.forEach(function (t) {
      t.onended = function () {
        if (state === 'recording') stopRecorder(); /* 「停止共享」：同一收尾，已录 Blob 保留 */
      };
    });
    showRec('info', '正在录制标签页声音…点「停止录制」结束；不要关闭被录的标签页。');
  }

  recTabBtn.addEventListener('click', function () { startTabCapture(); });

  function onStopped() {
    state = 'ready';
    recBtn.hidden = true; /* 防误点重录覆盖已有录音；丢弃后恢复 */
    recTabBtn.disabled = false; /* T4.103：收尾恢复标签页按钮（录制期间被互斥禁用） */
    recLive.hidden = true;
    blob = new Blob(chunks, { type: REC_MIME.type });
    chunks = [];
    recExt = REC_MIME.ext;
    recName.value = (captureMode === 'tab' ? '标签页录音 ' : '麦克风录音 ') + fmtDate(new Date().toISOString());
    recResult.hidden = false;
    var sec = Math.round((Date.now() - startTime) / 1000);
    showRec('info', (pendingReason || '') + '录音完成：' + fmtDuration(sec) + ' / ' + fmtBytes(blob.size) + '。填好名称后点「提交转写」，或「丢弃录音」重来。');
  }

  recDiscard.addEventListener('click', function () {
    blob = null;
    recResult.hidden = true;
    recBtn.hidden = false;
    recBtn.textContent = '开始录制';
    state = 'idle';
    showRec('info', '');
  });

  recSubmit.addEventListener('click', function () {
    if (!blob || state === 'uploading') return;
    var name = recName.value.trim();
    if (!name) { showRec('error', '请先填写任务名称。'); return; }
    state = 'uploading';
    recSubmit.disabled = true;
    showRec('info', '上传中…（' + fmtBytes(blob.size) + '，大录音可能需要一些时间）');
    var form = new FormData();
    form.append('file', new File([blob], name + '.' + recExt, { type: REC_MIME.type }));
    if (templateSelect.value) form.append('template', templateSelect.value); /* 与文件上传同一字段 */
    apiFetch('api/v1/tasks', { method: 'POST', body: form }).then(function (resp) {
      return resp.json().then(function (data) { data.__httpStatus = resp.status; return data; });
    }).then(function (data) {
      recSubmit.disabled = false;
      if (data.__httpStatus === 201) {
        rememberTask(data.task_id, name + '.' + recExt);
        showRec('info', '已受理：任务 #' + data.task_id + '（' + statusText(data.status) + '），正在打开详情页…');
        setTimeout(function () { window.location.href = 'task.html?id=' + data.task_id; }, 600);
      } else {
        state = 'ready'; /* 失败保留录音，就地重试 */
        showRec('error', '提交失败（' + (data.code || data.__httpStatus) + '）：' + (data.message || '未知错误') + '。录音已保留，可直接再点「提交转写」重试。');
      }
    }, function () {
      recSubmit.disabled = false;
      state = 'ready';
      showRec('error', '服务暂时不可用。录音已保留，请稍后再点「提交转写」重试。');
    });
  });
})();

/* ===== T4.32b：从链接导入（先预览确认，再建任务；后端契约见接入指南 §3.9） =====
 * POST /api/v1/link/preview → 确认卡（标题/封面/时长/来源）；direct → POST /tasks/link；
 * page + byof_available → use_byof:true；page 无 BYOF → 灰按钮 + 3 步指引。
 * 写操作遵守 K-80：点按钮 → 读 API/跳详情回读；预览/提交期间禁用防重复提交。 */
(function () {
  var input = document.getElementById('linkUrl');
  var previewBtn = document.getElementById('linkPreviewBtn');
  var box = document.getElementById('linkPreviewBox');
  var titleEl = document.getElementById('linkTitle');
  var metaEl = document.getElementById('linkMeta');
  var submitBtn = document.getElementById('linkSubmitBtn');
  var byofBtn = document.getElementById('linkByofBtn');
  var byofDisabled = document.getElementById('linkByofDisabled');
  var guide = document.getElementById('linkGuide');
  var msgEl = document.getElementById('linkMsg');

  var currentUrl = null;
  var currentKind = null;
  var busy = false;

  function showMsg(kind, text) {
    /* T4.38：占位固定不跳动：#linkMsg 常驻（.link-msg 固定最小高度），出错只换文案不挪布局 */
    msgEl.className = 'link-msg' + (kind === 'error' ? ' error-text' : (text ? ' hint' : ''));
    msgEl.textContent = text || '';
  }

  function setBusy(b) {
    busy = b;
    previewBtn.disabled = b;
    submitBtn.disabled = b;
    if (!byofBtn.hidden) byofBtn.disabled = b;
  }

  function resetPreview() {
    box.hidden = true;
    guide.hidden = true;
    byofBtn.hidden = true;
    byofDisabled.hidden = true;
    submitBtn.hidden = false;
    var cover = document.getElementById('linkCover');
    if (cover) cover.remove(); /* 上一次预览的封面不残留 */
    currentUrl = null;
    currentKind = null;
  }

  function fmtHost(url) {
    try { return new URL(url).host; } catch (e) { return ''; }
  }

  previewBtn.addEventListener('click', function () {
    if (busy) return;
    var url = input.value.trim();
    if (!url) { showMsg('error', '请先粘贴一个链接。'); return; }
    setBusy(true);
    showMsg('info', '预览中…');
    resetPreview();
    postJson('api/v1/link/preview', { url: url }).then(function (data) {
      setBusy(false);
      if (data.__httpStatus !== 200) {
        showMsg('error', '预览失败（' + (data.code || data.__httpStatus) + '）：' + (data.message || '未知错误'));
        return;
      }
      showMsg('info', '');
      currentUrl = data.final_url || url;
      currentKind = data.kind;
      titleEl.textContent = data.title || '（页面未提供标题）';
      var meta = '来源：' + fmtHost(currentUrl);
      if (data.duration_sec) meta += ' · 时长约 ' + fmtDuration(data.duration_sec);
      if (data.content_type) meta += ' · ' + data.content_type.split(';')[0];
      metaEl.textContent = meta;
      if (data.image) {
        /* 封面以预览卡左列 img 呈现（T4.38：预览卡重排，封面/标题/时长分层） */
        var row = box.querySelector('.link-preview-row');
        var img = document.createElement('img');
        img.id = 'linkCover';
        img.alt = '封面';
        img.className = 'link-cover';
        img.src = data.image.replace(/"/g, '&quot;');
        row.insertBefore(img, row.firstChild);
      }
      if (data.kind === 'direct') {
        guide.hidden = true;
        submitBtn.hidden = false;
        submitBtn.textContent = '直接转写';
        byofBtn.hidden = true;
        byofDisabled.hidden = true;
      } else if (data.kind === 'page') {
        if (data.byof_available) {
          submitBtn.hidden = true;
          byofBtn.hidden = false;
          byofDisabled.hidden = true;
          guide.hidden = true;
        } else {
          submitBtn.hidden = true;
          byofBtn.hidden = true;
          byofDisabled.hidden = false;
          guide.hidden = false;
        }
      } else { /* unsupported：给可读原因，不给提交按钮 */
        submitBtn.hidden = true;
        byofBtn.hidden = true;
        byofDisabled.hidden = true;
        guide.hidden = false;
        showMsg('error', data.reason || '该链接无法导入');
        box.hidden = true;
        return;
      }
      box.hidden = false;
    }, function () {
      setBusy(false);
      showMsg('error', '服务暂时不可用，请稍后重试');
    });
  });

  function submitLink(useByof) {
    if (busy || !currentUrl) return;
    setBusy(true);
    showMsg('info', useByof ? '外部工具抓取中…（可能需要一些时间）' : '提交中…');
    var body = { url: currentUrl };
    if (useByof) body.use_byof = true;
    postJson('api/v1/tasks/link', body).then(function (data) {
      setBusy(false);
      if (data.__httpStatus === 201) {
        rememberTask(data.task_id, fmtHost(currentUrl) + ' 导入');
        showMsg('info', '已受理：任务 #' + data.task_id + '（' + statusText(data.status) + '），正在打开详情页…');
        setTimeout(function () { window.location.href = 'task.html?id=' + data.task_id; }, 600);
      } else {
        showMsg('error', '提交失败（' + (data.code || data.__httpStatus) + '）：' + (data.message || '未知错误'));
      }
    }, function () {
      setBusy(false);
      showMsg('error', '服务暂时不可用，请稍后重试');
    });
  }

  submitBtn.addEventListener('click', function () { submitLink(false); });
  byofBtn.addEventListener('click', function () { submitLink(true); });
})();
