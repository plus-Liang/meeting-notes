/* 录音纪要平台 · API Token 适配（T4.6 / T4.14）
 * /api/v1/** 需要 Authorization: Bearer <API_TOKEN>（.env 里的 API_TOKEN）。
 * apiFetch(url, opts)：自动带 localStorage.mn_token；遇 401 → 页内对话框粘贴 token（T4.14，
 * 替换 window.prompt；DOM 不可用时兜底回退 prompt）→ 存 → 只重试一次；
 * 仍失败时返回 401 响应，调用方按 { code, message } 展示可读错误（与全局错误口径一致）。
 * 防叠弹：轮询页（3s 一次）持续 401 时，已开的对话框不重复弹出，本次直接把 401 交给调用方。
 * 纪律：只用相对路径；不引前端框架；token 只存本浏览器 localStorage。
 */
function mnGetToken() {
  try {
    return localStorage.getItem('mn_token') || '';
  } catch (e) {
    return '';
  }
}

var mnAsking = false;

/* 页内对话框：返回 Promise<string|null>（null = 用户取消 / 已有对话框在开） */
function mnAskToken() {
  return new Promise(function (resolve) {
    if (mnAsking) {
      resolve(null);
      return;
    }
    function fallbackPrompt() {
      var v = null;
      try { v = window.prompt('需要 API Token（服务端 .env 里的 API_TOKEN），请粘贴：'); } catch (e) { /* 无 DOM 环境 */ }
      resolve(v);
    }
    try {
      if (!document.body) { fallbackPrompt(); return; }
      mnAsking = true;
      var overlay = document.createElement('div');
      overlay.className = 'mn-dialog-overlay';
      overlay.innerHTML =
        '<div class="mn-dialog" role="dialog" aria-modal="true" aria-labelledby="mn-dialog-title">' +
        '<h3 id="mn-dialog-title">需要 API Token</h3>' +
        '<p class="hint">此页面需要访问本机服务端 API。请粘贴服务端 .env 中 API_TOKEN 的值，' +
        '只需粘贴一次，之后会记住在本浏览器。</p>' +
        '<input type="password" id="mn-dialog-input" autocomplete="off" spellcheck="false" aria-label="API Token">' +
        '<div class="mn-dialog-actions">' +
        '<button type="button" class="btn-ghost" id="mn-dialog-cancel">取消</button>' +
        '<button type="button" id="mn-dialog-ok">确认</button>' +
        '</div></div>';
      var input = overlay.querySelector('#mn-dialog-input');
      function done(value) {
        mnAsking = false;
        overlay.remove();
        document.removeEventListener('keydown', onKey, true);
        resolve(value);
      }
      function submit() { done(input.value && input.value.trim() ? input.value.trim() : null); }
      function onKey(e) {
        if (e.key === 'Escape') { done(null); }
        else if (e.key === 'Enter') { e.preventDefault(); submit(); }
      }
      overlay.querySelector('#mn-dialog-ok').addEventListener('click', submit);
      overlay.querySelector('#mn-dialog-cancel').addEventListener('click', function () { done(null); });
      overlay.addEventListener('click', function (e) { if (e.target === overlay) done(null); });
      document.addEventListener('keydown', onKey, true);
      document.body.appendChild(overlay);
      input.focus();
    } catch (e) {
      mnAsking = false;
      fallbackPrompt();
    }
  });
}

function apiFetch(url, opts) {
  opts = opts || {};
  var headers = Object.assign({}, opts.headers || {});
  var token = mnGetToken();
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }
  return fetch(url, Object.assign({}, opts, { headers: headers })).then(function (resp) {
    if (resp.status !== 401) {
      return resp;
    }
    return mnAskToken().then(function (input) {
      if (!input) {
        return resp; // 用户取消：把 401 交给调用方展示
      }
      try {
        localStorage.setItem('mn_token', input);
      } catch (e) { /* 存不了就直接用本次输入 */ }
      headers['Authorization'] = 'Bearer ' + input;
      return fetch(url, Object.assign({}, opts, { headers: headers })); // 只重试一次，不循环
    });
  });
}
