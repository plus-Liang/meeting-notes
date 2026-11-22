/* ===== T4.57：站内确认弹窗（共享组件）——替代浏览器原生 confirm 弹窗 =====
 * Promise<boolean>；焦点落「取消」（破坏性操作回车不误删）、Esc/点遮罩=取消、关闭后焦点归还触发元素；
 * 同时只允许一个（后来者顶掉旧弹窗）。自包含实现：标题/正文用 textContent 注入，不依赖各页的 escapeHtml。
 * 行为契约与 T4.53 验收版逐字一致（tasks.html / task.html 共用）。 */
(function () {
  'use strict';
  var dlgEl = null, dlgPrevFocus = null;

  function confirmDialog(opts) {
    return new Promise(function (resolve) {
      if (dlgEl) { dlgEl.remove(); dlgEl = null; }
      dlgPrevFocus = document.activeElement;
      var mask = document.createElement('div');
      mask.className = 'dlg-mask';
      var panel = document.createElement('div');
      panel.className = 'dlg-panel';
      panel.setAttribute('role', 'dialog');
      panel.setAttribute('aria-modal', 'true');
      panel.setAttribute('aria-labelledby', 'dlgTitle');
      panel.setAttribute('aria-describedby', 'dlgDesc');
      var title = document.createElement('h2');
      title.id = 'dlgTitle';
      title.className = 'dlg-title';
      title.textContent = opts.title;
      var desc = document.createElement('p');
      desc.id = 'dlgDesc';
      desc.className = 'dlg-desc';
      desc.textContent = opts.desc;
      var actions = document.createElement('div');
      actions.className = 'dlg-actions';
      var cancelBtn = document.createElement('button');
      cancelBtn.type = 'button';
      cancelBtn.id = 'dlgCancel';
      cancelBtn.className = 'btn-secondary';
      cancelBtn.textContent = '取消';
      var okBtn = document.createElement('button');
      okBtn.type = 'button';
      okBtn.id = 'dlgOk';
      okBtn.className = 'btn-danger';
      okBtn.textContent = opts.okText || '确定';
      actions.appendChild(cancelBtn);
      actions.appendChild(okBtn);
      panel.appendChild(title);
      panel.appendChild(desc);
      panel.appendChild(actions);
      mask.appendChild(panel);
      document.body.appendChild(mask);
      /* T4.68 C2（D-211/K-137）：样式表兜底——components.css 缺失或停留在旧缓存（没有 .dlg-* 规则）时，
       * 弹窗会退化成文档流末尾的普通块级元素（实测：面板落到页面左下角、遮罩透明）。
       * 插入后读一次 computed position：不是 fixed ⇒ .dlg-* 规则没生效，给遮罩/面板补关键内联样式；
       * 样式表正常时一个字节不改（内联只在兜底分支生效，components.css 的类规则不动）。 */
      if (getComputedStyle(mask).position !== 'fixed') {
        mask.style.cssText = 'position:fixed; inset:0; z-index:90; background:rgba(20,18,14,0.45);'
          + ' display:flex; align-items:center; justify-content:center;';
        panel.style.cssText = 'position:relative; max-width:min(430px, calc(100vw - 32px));'
          + ' background:#fff; border-radius:12px; padding:20px;';
      }
      dlgEl = mask;
      function done(val) {
        document.removeEventListener('keydown', onKey, true);
        mask.remove();
        dlgEl = null;
        if (dlgPrevFocus && dlgPrevFocus.focus) { dlgPrevFocus.focus(); }
        resolve(val);
      }
      function onKey(e) {
        if (e.key === 'Escape') { e.stopPropagation(); done(false); }
      }
      cancelBtn.addEventListener('click', function () { done(false); });
      okBtn.addEventListener('click', function () { done(true); });
      mask.addEventListener('click', function (e) { if (e.target === mask) { done(false); } });
      document.addEventListener('keydown', onKey, true);
      cancelBtn.focus();
    });
  }

  window.confirmDialog = confirmDialog;
})();
