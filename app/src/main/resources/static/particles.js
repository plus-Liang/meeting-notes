/* 录音纪要平台 · T4.39 背景淡粒子视差（单 canvas，无依赖）
 * 约束（T4.39 卡）：
 * - ≤40 节点（实际 26）；单 canvas，fixed + pointer-events:none + z-index:-1（在卡片玻璃后面）
 * - 只做合成层运动：节点位置由 canvas 绘制，元素本身不动布局；视差偏移只随 scroll 换算坐标
 * - prefers-reduced-motion：初始与运行中均为完全停用（不创建 canvas；运行中变化则移除）
 * - visibilitychange：隐藏即暂停 rAF，回前恢复
 * - 帧率上限 ~30fps，绘制极轻（26 个圆点）
 */
(function () {
  "use strict";
  if (document.getElementById("mnParticles")) return;
  var mq = window.matchMedia ? window.matchMedia("(prefers-reduced-motion: reduce)") : null;
  if (mq && mq.matches) return; // 初始就停用：连 canvas 都不建

  var N = 26;
  var COLORS = ["rgba(140,196,154,", "rgba(110,170,140,", "rgba(170,210,180,"];

  var canvas = document.createElement("canvas");
  canvas.id = "mnParticles";
  canvas.setAttribute("aria-hidden", "true");
  var cs = canvas.style;
  cs.position = "fixed";
  cs.inset = "0";
  cs.width = "100%";
  cs.height = "100%";
  cs.pointerEvents = "none";
  cs.zIndex = "-1";
  var host = document.body || document.documentElement;
  host.appendChild(canvas);

  var ctx = canvas.getContext("2d");
  if (!ctx) { canvas.parentNode.removeChild(canvas); return; }

  var dpr = Math.min(window.devicePixelRatio || 1, 2);
  var W = 0, H = 0, dots = [], raf = 0, last = 0, scrollY = window.scrollY || 0, born = performance.now();
  var running = false;

  function resize() {
    W = canvas.width = Math.floor(window.innerWidth * dpr);
    H = canvas.height = Math.floor(window.innerHeight * dpr);
  }
  function init() {
    dots = [];
    for (var i = 0; i < N; i++) {
      dots.push({
        x: Math.random(),            // 0..1 归一化
        y: Math.random(),
        r: (0.6 + Math.random() * 1.5) * dpr,
        vx: (Math.random() - 0.5) * 0.000055,
        vy: (Math.random() - 0.5) * 0.000045,
        depth: 0.05 + Math.random() * 0.12,   // 视差深度
        a: 0.10 + Math.random() * 0.22,       // 透明度（淡）
        c: COLORS[i % COLORS.length]
      });
    }
  }

  function frame(t) {
    raf = 0;
    if (!running) return;
    if (t - last >= 33) {          // ~30fps 上限
      last = t;
      ctx.clearRect(0, 0, W, H);
      var fade = Math.min(1, (t - born) / 600);   // 前 600ms 淡入
      for (var i = 0; i < N; i++) {
        var d = dots[i];
        d.x += d.vx; d.y += d.vy;
        if (d.x < -0.02) d.x = 1.02; else if (d.x > 1.02) d.x = -0.02;
        if (d.y < -0.02) d.y = 1.02; else if (d.y > 1.02) d.y = -0.02;
        var px = d.x * W;
        var py = d.y * H - scrollY * d.depth * dpr;  // 视差：随滚动按深度错位
        py = ((py % H) + H) % H;
        ctx.beginPath();
        ctx.fillStyle = d.c + (d.a * fade).toFixed(3) + ")";
        ctx.arc(px, py, d.r, 0, 6.2832);
        ctx.fill();
      }
    }
    raf = requestAnimationFrame(frame);
  }

  function start() {
    if (running || (mq && mq.matches)) return;
    running = true;
    last = 0;
    raf = requestAnimationFrame(frame);
  }
  function stop() {
    running = false;
    if (raf) { cancelAnimationFrame(raf); raf = 0; }
  }

  window.addEventListener("resize", function () { resize(); }, { passive: true });
  window.addEventListener("scroll", function () { scrollY = window.scrollY || 0; }, { passive: true });
  document.addEventListener("visibilitychange", function () {
    if (document.hidden) stop(); else start();
  });
  if (mq && mq.addEventListener) {
    mq.addEventListener("change", function (e) {
      if (e.matches) { stop(); if (canvas.parentNode) canvas.parentNode.removeChild(canvas); }
    });
  }

  resize();
  init();
  start();
})();
