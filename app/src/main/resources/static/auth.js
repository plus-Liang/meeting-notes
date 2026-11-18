/* 录音纪要平台 · 浏览器登录态适配（T4.6 / T4.14 / T4.37b）
 * /api/v1/** 需要 Authorization: Bearer <账号令牌|API_TOKEN>（T4.37a 双凭据）。
 * 浏览器统一走登录页（T4.37b）：login.html 用账号密码换令牌，存 localStorage.mn_token/mn_user；
 * apiFetch(url, opts)：自动带 token；收 401 → 清本地登录态 → 跳 login.html?next=<当前路径>
 * （login.html 自身不跳，防循环；share.html 匿名只读页不跳，保持行为与 T4.28 一致）。
 * 退出：mnLogout() 调 POST /auth/logout 吊销令牌后回登录页。
 * 纪律：只用相对路径；不引前端框架；登录态只存本浏览器 localStorage（mn_token/mn_user）。
 */
function mnGetToken() {
  try {
    return localStorage.getItem('mn_token') || '';
  } catch (e) {
    return '';
  }
}

function mnCurrentUser() {
  try {
    var raw = localStorage.getItem('mn_user');
    var user = raw ? JSON.parse(raw) : null;
    if (user && user.user_id !== undefined && user.username) return user;
  } catch (e) { /* 坏数据视同缺失 */ }
  return null;
}

/* ?next= 白名单：只收站内相对路径（不认协议/域名/协议相对路径），防开放重定向；
 * 非法一律回 index.html（由调用方兜底）。 */
function mnSafeNext(raw) {
  var v = String(raw === undefined || raw === null ? '' : raw).trim();
  if (!v || !/^[A-Za-z0-9_\-./?=&%]+$/.test(v)) return '';
  var low = v.toLowerCase();
  if (v.indexOf('//') === 0 || low.indexOf('http') === 0 || low.indexOf('javascript:') === 0) return '';
  return v;
}

/* 当前页相对路径 + search（401 跳登录时带回，登录后原路返回） */
function mnCurrentNext() {
  return location.pathname + location.search;
}

function mnClearLoginState() {
  try {
    localStorage.removeItem('mn_token');
    localStorage.removeItem('mn_user');
  } catch (e) { /* 存储不可用时忽略 */ }
}

function mnGoLogin(next) {
  var target = 'login.html';
  var safe = mnSafeNext(next);
  if (safe) target += '?next=' + encodeURIComponent(safe);
  location.replace(target);
}

function mnEscapeHtml(s) {
  return String(s === undefined || s === null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
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
    /* 401：登录态失效（无令牌/令牌被轮换/已注销）→ 清本地并回登录页。
     * login.html 内不跳（防循环）；share.html 匿名只读不跳；其余页带 next 原路返回。 */
    mnClearLoginState();
    var page = location.pathname.split('/').pop() || 'index.html';
    if (page !== 'login.html' && page !== 'share.html') {
      mnGoLogin(mnCurrentNext());
    }
    return resp;
  });
}

/* 退出登录：吊销服务端令牌（无论成败）→ 清本地 → 回登录页 */
function mnLogout() {
  var done = function () {
    mnClearLoginState();
    location.replace('login.html');
  };
  apiFetch('api/v1/auth/logout', { method: 'POST' }).then(done, done);
}

/* 页头用户区：渲染到 [data-mn-userbar]（share.html 不放占位，保持匿名只读） */
function mnRenderUserBar() {
  var bar = document.querySelector('[data-mn-userbar]');
  if (!bar) return;
  if (!mnGetToken()) {
    bar.innerHTML = '<a href="login.html">登录</a>';
    return;
  }
  var user = mnCurrentUser();
  if (!user) {
    /* mn_user 缺失（换浏览器/存储被清）→ 用 GET /auth/me 兜底一次 */
    apiFetch('api/v1/auth/me').then(function (resp) {
      if (!resp.ok) return; /* 401 已由 apiFetch 清理并跳登录 */
      return resp.json().then(function (data) {
        try {
          localStorage.setItem('mn_user', JSON.stringify(
            { user_id: data.user_id, username: data.username, role: data.role }));
        } catch (e) { /* 忽略 */ }
        mnRenderUserBar();
      });
    }).catch(function () { /* 网络错误：保持空占位，不打断页面 */ });
    return;
  }
  bar.innerHTML =
    '<span class="mn-user-name">' + mnEscapeHtml(user.username) + '</span>' +
    (user.role === 'ADMIN' ? '<span class="mn-role-badge">管理员</span>' : '') +
    '<button type="button" class="mn-logout-btn" id="mn-logout-btn">退出</button>';
  var btn = document.getElementById('mn-logout-btn');
  if (btn) btn.addEventListener('click', mnLogout);
}

/* 页面就绪即渲染用户区（login/share 无占位时为 no-op） */
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', mnRenderUserBar);
} else {
  mnRenderUserBar();
}
