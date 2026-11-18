# DESIGN.md —— 视觉世界「白纸档案」（T4.41 定稿，取代 T4.40「审稿台」）

> 本文件是唯一视觉权威（impeccable 约定）。取值来源 = `design-explore/direction-final-mixed.html`（A+B 混合定稿稿，D-156/D-157）。
> T4.40 冷灰纸面/审稿台、T4.39 深色版、T4.14 暖纸方格均已废弃为 anti-reference。
> D-158 用户修正：**三入口是并列的三种提交方式（文件/直链/录音），不是步骤 1-2-3**；按钮/徽章/chip/筛选/toast 一律全圆角 pill，禁止直角方框按钮。

## 0. 方向（THESIS）

**「白纸档案」：一张安静的近白档案纸，衬线标题、黑体正文、等宽数字，像一份被认真归档的纪要。**
瑞士网格骨架 × 档案细节（档案抬头行 REC-NOTES、编号、配额刻度、印章红）混合。这个产品的本质是**归档**：录音变成纪要后进入台账，人回来翻阅、核对、勾待办、导出。

一句话场景（决定明暗）：**用户坐在桌前，日光或台灯下，连续阅读几百行中文逐字稿**——浅色、近白、低反射最耐久。定：**单浅色世界，不做双主题**。

| 校准脸 | 本世界 |
|---|---|
| AI 默认：乳白底 + 紫渐变 + 大圆角卡片海 | 近白纸面 #fbfaf7、零渐变、零卡片化（实线/hairline/dotted 分节） |
| T4.40 审稿台：冷灰 + 系统无衬线标题 | 近白 + 衬线标题（宋体系）+ 档案细节 |
| T4.39：近黑玻璃 + 粒子 + 脉冲点 | 浅色实底、零玻璃、零 canvas、零脉冲 |

## 1. 色（纸面 + 墨阶 + 印章红纪律）

| token | 值 | 用途 / 为什么 |
|---|---|---|
| `--paper` | `#fbfaf7` | 页面底。近白纸面（**禁米色/cream**，detector 判 cream-palette） |
| `--paper-2` | `#f4f1ea` | hover 淡底（台账行/入口） |
| `--leaf` | `#ffffff` | 输入/弹层/播放器/预览卡底（纸上的白纸片） |
| `--ink` | `#14120e` | 正文/结构线/主按钮底。暖墨黑 |
| `--ink-2` | `#48443b` | 次要文字（≈9.3:1） |
| `--ink-3` | `#6d685c` | 弱化 meta/等宽标签（纸上 ≈5.3:1，≥4.5） |
| `--rule` | `#e2ddd2` | hairline 分节线 |
| `--rule-ink` | `#14120e` | 1px 墨结构线（刊头/区块头/页脚上边） |
| `--seal` | `#9d2f1b`（hover `#7f2415`，tint `#f6e7e0`） | **印章红只用于「此刻/重点」**：当前句、录制中、配额刻度、选中态、失败重试按钮 |
| `--done` | `#3c6236` / tint `#eef3ec` | 成功（≈6.8:1） |
| `--wait` | `#8a6400` | 排队（≈5.2:1） |
| `--bad` | `#8f2318` / tint `#f7e9e5` | 失败（≈8.4:1） |

语义纪律：黑色承担动作，印章红只承担注意；正文不染状态色，状态色只出现在徽章/圆点/填充。

## 2. 字（三轨，全系统栈、离线可用）

- 标题衬线：`"Noto Serif SC","Source Han Serif SC","Songti SC",STSong,"STZhongsong",SimSun,Georgia,serif`
- 正文黑体：`"PingFang SC","Microsoft YaHei","Helvetica Neue",Arial,sans-serif`
- 数字/元信息等宽：`ui-monospace,"SF Mono","Cascadia Mono",Menlo,Consolas,monospace`；**所有数字 `tabular-nums`**
- 字号阶：刊头 h1 `clamp(46px,6.2vw,78px)/1.06` 负字距 −2px；区块标题 17–22px serif；正文 15/1.7；次级 13；标签 12px mono（**功能性文字一律 ≥12px**，定稿稿的 11px 已全部提到 12px）

## 3. 形与空间（D-158）

- **pill 全圆角（999px）**：按钮、徽章、chip/胶囊、筛选 segs、toast、登录模式钮。
- **10–14px 圆角**：输入框（12）、播放器容器（14）、弹层/预览卡（14）、抽屉左缘（14）、空态虚线框（16）。
- **播放器控制键 = 圆形**（主播放键墨底，其余描边圆键）。
- **表格与结构线不设圆角**；**零卡片化**——内容直接落在纸面，用实线（区块头）/hairline（分栏）/dotted（台账行）分节。
- 按钮分档：主操作 = 墨色填充胶囊；重试/警示 = 印章红填充胶囊；次要 = 描边胶囊；**文字动作 = 纯下划线**（`border:0;background:transparent`）。
- 阴影只给浮层：弹层/抽屉/toast `0 12px 28px rgba(20,18,14,.16)` 级。
- 间距节奏：区块 34–42px 纵向；台账行 13px 上下；主容器 1260px、40px 边距（26 → 18 递减）。

## 4. 动效

- 120–200ms，只动 `transform/opacity`；缓动 `cubic-bezier(.22,1,.36,1)`；无循环装饰动画（骨架屏 opacity 呼吸是唯一循环，功能性）。
- `prefers-reduced-motion`：全部降级 0.01ms。

## 5. 组件 × 状态矩阵

| 组件 | 状态 |
|---|---|
| 按钮 | 主（墨填充 pill）/ 次（描边 pill）/ 警示（印章红 pill）；hover 深化、active scale .985、disabled 灰底墨灰字、focus-visible 2px 印章红环 |
| 文字动作 | 纯下划线；hover 转印章红 |
| 输入/选择器 | 白底 hairline、12px 圆角、≥44px；focus = 墨边 + 3px 墨 halo；placeholder = ink-3 |
| 徽章 | pill + 状态点：排队（wait 点）/ 转写中（seal 点 tint）/ 已完成（绿 tint）/ 失败（红 tint）/ 模板（素） |
| 台账行 | 编号 mono、文件名单行省略、hover 淡底 + 标题印章红下划线；行 dotted 分隔；状态圆点 6px |
| 筛选 segs | pill 组；active = 墨底白字 + 计数反白 chip；hover 边框加深 |
| 逐字稿 | hover 淡底；当前句 = seal-tint 底 + seal-deep 字；行 ts 等宽 |
| 播放器 | 自绘：播放/±10s/进度（拖+缓冲+hover 气泡）/竖音量弹层（拖动真改 audio.volume）/倍速菜单（选中墨底白字）/静音 aria；容器 14px、控制键圆形 |
| 抽屉 | 右滑 920px（390 全屏）；遮罩墨 32%；头栏 leaf + hairline；关闭圆键 |
| toast | 墨底白字 pill，2s 自灭 |
| 空态 | dashed 虚线框 16px；标题 serif + 一句原因 + 出口按钮 |
| 配额刻度 | 20 格刻度条；已用格 = 印章红（「重点」契约允许） |

## 6. 六页映射

| 页 | 视觉要点 |
|---|---|
| index | 档案抬头行（REC-NOTES+归档数+配额+日期+V0.1.0）→ 顶栏 → 巨大衬线刊头 + 右 meta 表 → **三入口竖线分栏（文件/直链/录音，并列无编号）** → 任务台账预览（最近 3 条）+ 结果示例（示例数据徽标）→ 右索引栏（配额刻度/累计/模板/服务） |
| tasks | 抬头 + 顶栏 + 刊头（状态计数 meta）→ 搜索 → 筛选 segs + 批量条 → 台账行 → 骨架行/空态/离线兜底 |
| task | 抬头 + 顶栏 + 详情刊头（衬线标题 + 徽章行 + meta 表）→ 两栏（左：播放器+逐字稿；右：摘要首字下沉/要点 01-02-03/待办/标签/模板/分享/导出/删除）；<1080 单栏 |
| settings | 抬头 + 顶栏 + 刊头 → kv 区块（状态/配额条/统计/档位/术语表），无按钮化 |
| login | 抬头 + 顶栏 + 单列居中刊头（衬线标题 + 句号印章红）+ 模式 pill + pill 输入；错误就地显示 |
| share | 抬头（只读分享）+ 品牌 + 极简只读正文，无导航按钮，匿名 |

## 7. 检测器口径

- 门禁：`npx -y impeccable detect app/src/main/resources/static --json`（源码）+ 渲染页快照，**warning 0**；advisory 逐条处理或 `impeccable-disable` + 理由。
- 定稿稿 6 条 warning 的落地修复：4× cramped-padding（inlet/segis 补内边距/改 gap 结构）+ 2× tiny-text（11px → 12px），不许 disable 掩盖。
- 浏览器默认件纳入系统：`::selection`（seal-tint）、focus ring（印章红 2px）、滚动条、caret 均按 palette 主题化；图标一律内联 SVG 描边。
- CSS 分层：`tokens.css`（变量）→ `components.css`（共享组件）→ `style.css`（页面骨架/布局/响应式/JS 钩子类收口）。
