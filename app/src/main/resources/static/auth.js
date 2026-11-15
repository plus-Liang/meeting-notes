/* 录音纪要平台 · API Token 适配（T4.6 / US-10）
 * /api/v1/** 需要 Authorization: Bearer <API_TOKEN>（.env 里的 API_TOKEN）。
 * apiFetch(url, opts)：自动带 localStorage.mn_token；遇 401 → prompt 粘一次 token → 存 → 只重试一次；
 * 仍失败时返回 401 响应，调用方按 { code, message } 展示可读错误（与全局错误口径一致）。
 * 纪律：只用相对路径；不引前端框架；token 只存本浏览器 localStorage。
 */
function mnGetToken() {
  try {
    return localStorage.getItem('mn_token') || '';
  } catch (e) {
    return '';
  }
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
    var input = window.prompt('需要 API Token（服务端 .env 里的 API_TOKEN），请粘贴：');
    if (!input) {
      return resp; // 用户取消：把 401 交给调用方展示
    }
    try {
      localStorage.setItem('mn_token', input.trim());
    } catch (e) { /* 存不了就直接用本次输入 */ }
    headers['Authorization'] = 'Bearer ' + input.trim();
    return fetch(url, Object.assign({}, opts, { headers: headers })); // 只重试一次，不循环
  });
}
