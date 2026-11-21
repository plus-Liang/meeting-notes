/* ===== T4.59：HTML 转义收敛——五页五份 escapeHtml + 两份 escapeAttr 收敛到此（R4 单一实现） =====
 * escapeHtml：文本上下文（& < > → 实体）；语义与原五页内联版逐字一致（不转引号——引号归 escapeAttr 管）。
 * escapeAttr：属性上下文 = escapeHtml 再转双/单引号；语义与原 task/tasks 两页内联版逐字一致。
 * 标题/正文一律 textContent 注入的调用方（confirm.js）不受影响；注入 HTML 字符串的调用方用本文件两个函数。 */
(function () {
  'use strict';
  function escapeHtml(s) {
    return String(s === undefined || s === null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function escapeAttr(s) {
    return escapeHtml(s).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  window.escapeHtml = escapeHtml;
  window.escapeAttr = escapeAttr;
})();
