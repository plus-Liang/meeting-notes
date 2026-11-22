/* 录音纪要平台 · 站内详情抽屉（T4.38，D-151）
 * P0：任务详情在网站内打开（右侧抽屉，iframe 复用 task.html?id= 深链），不新开浏览器标签；
 * 列表页 URL 不变；ESC / 点遮罩关闭；关闭即移除 iframe（停音频）；列表滚动/筛选/勾选不受影响。
 * 纪律：零依赖；动效只用 transform/opacity ≤240ms；触控目标 ≥44px；focus 管理保键盘可达。
 */
(function () {
  'use strict';

  var lastTrigger = null; /* 关闭后焦点还原 */

  /* T4.69 C2：抽屉深链版本化——iframe 与「新标签打开」/「复制链接」共用 task.html?id= 同一 URL，
   * 浏览器可能还存着部署前的旧 task.html（T4.68 的 no-cache 只约束之后的响应、不作废已存条目）。
   * 页面加载即取一次构建标识（每页面一次，内存缓存），深链追加 &v=<build>；
   * 取失败或未就绪 → 降级 &_=<Date.now()>（保证能开、且不命中旧缓存条目）。 */
  var mnBuildTag = null;
  try {
    fetch('api/v1/version')
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) {
        if (j && j.build) { mnBuildTag = String(j.build).replace(/[^0-9A-Za-z._-]/g, ''); }
      })
      .catch(function () { /* 保持 null → 降级 cache-buster */ });
  } catch (e) { /* 无 fetch 的极旧环境 → 降级 cache-buster */ }

  function mnCacheBust() {
    return mnBuildTag ? '&v=' + mnBuildTag : '&_=' + Date.now();
  }

  function mnOpenTaskDrawer(taskId, title) {
    if (document.querySelector('.mn-drawer-root')) return; /* 单实例 */
    lastTrigger = document.activeElement;
    var root = document.createElement('div');
    root.className = 'mn-drawer-root';
    root.setAttribute('role', 'dialog');
    root.setAttribute('aria-modal', 'true');
    root.setAttribute('aria-label', '任务详情（站内抽屉）');
    var safeId = String(taskId).replace(/[^0-9]/g, '');
    var deepLink = 'task.html?id=' + safeId + mnCacheBust();
    var safeTitle = String(title === undefined || title === null ? '' : title)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    root.innerHTML =
      '<div class="mn-drawer-mask"></div>' +
      '<aside class="mn-drawer">' +
      '<header class="mn-drawer-head">' +
      '<span class="mn-drawer-title"><span class="mn-drawer-id">#' + safeId + '</span>' +
      '<span class="mn-drawer-name">' + safeTitle + '</span></span>' +
      '<span class="mn-drawer-actions">' +
      '<a href="' + deepLink + '" target="_blank" rel="noopener" class="btn-secondary">新标签打开</a>' +
      '<button type="button" class="btn-secondary mn-drawer-copy">复制链接</button>' +
      '<button type="button" class="mn-drawer-close" aria-label="关闭详情抽屉">' +
      '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" ' +
      'stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg></button>' +
      '</span></header>' +
      '<iframe class="mn-drawer-frame" src="' + deepLink + '" title="任务详情内容"></iframe>' +
      '</aside>';
    document.body.appendChild(root);
    document.body.style.overflow = 'hidden';
    /* 下一帧加 .open：保证 slide+fade 过渡生效 */
    requestAnimationFrame(function () {
      requestAnimationFrame(function () { root.classList.add('open'); });
    });

    function close() {
      root.classList.remove('open');
      document.removeEventListener('keydown', onKey, true);
      /* 等抽屉滑出动画结束后移除 DOM（iframe 一并销毁，音频停止） */
      setTimeout(function () {
        if (root.parentNode) root.parentNode.removeChild(root);
        document.body.style.overflow = '';
        if (lastTrigger && document.contains(lastTrigger)) lastTrigger.focus();
      }, 260);
    }

    function onKey(e) {
      if (e.key === 'Escape') { e.stopPropagation(); close(); }
    }

    root.querySelector('.mn-drawer-mask').addEventListener('click', close);
    root.querySelector('.mn-drawer-close').addEventListener('click', close);
    root.querySelector('.mn-drawer-copy').addEventListener('click', function () {
      var url = location.origin + location.pathname.replace(/[^/]*$/, '') + deepLink;
      mnCopyText(url, function () { mnDrawerToast('已复制链接'); }, function () { mnDrawerToast('复制失败，请手动复制'); });
    });
    document.addEventListener('keydown', onKey, true);
    root.querySelector('.mn-drawer-close').focus();
    return { close: close };
  }

  /* 轻提示 toast（复制成功等，2s 自动消失；只动 opacity/transform） */
  function mnDrawerToast(text) {
    var old = document.querySelector('.mn-drawer-toast');
    if (old) old.remove();
    var el = document.createElement('div');
    el.className = 'mn-drawer-toast';
    el.setAttribute('role', 'status');
    el.textContent = text;
    document.body.appendChild(el);
    requestAnimationFrame(function () { el.classList.add('show'); });
    setTimeout(function () {
      el.classList.remove('show');
      setTimeout(function () { el.remove(); }, 260);
    }, 2000);
  }

  /* 列表页委托：点 .task-detail-link → 抽屉打开（href 保留深链，右键/中键新标签能力不变） */
  function mnBindDrawerLinks(container) {
    if (!container || container.__mnDrawerBound) return;
    container.__mnDrawerBound = true;
    container.addEventListener('click', function (e) {
      var link = e.target.closest('a.task-detail-link');
      if (!link) return;
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return; /* 修饰键保留浏览器行为 */
      e.preventDefault();
      mnOpenTaskDrawer(link.getAttribute('data-task-id'), link.getAttribute('data-task-name'));
    });
  }

  window.mnOpenTaskDrawer = mnOpenTaskDrawer;
  window.mnBindDrawerLinks = mnBindDrawerLinks;
  window.mnDrawerToast = mnDrawerToast;
})();
