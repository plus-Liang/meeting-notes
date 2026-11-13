# ASR 服务（FunASR，OpenAI 兼容接口）

本项目**不自研 ASR**（宪法原则 II / D-005）。ASR 以独立容器提供服务，对外只暴露
OpenAI 兼容的 `POST /v1/audio/transcriptions`，Java 侧只发 HTTP。

## 为什么独立成服务

1. 换实现（FunASR ↔ whisper.cpp ↔ 云 ASR）时，Java 代码零改动（D-006）；
2. 模型体积大，不该塞进应用镜像；
3. 能力独立扩缩容（ASR 是重资源，并发数可配，N9）。

## 待办：镜像选型（T0.1）

**当前状态：镜像未定，`.env` 里的 `ASR_IMAGE` 是占位符，不允许凭猜写死。**

验收标准（T0.1 / T0.2）：

```powershell
docker compose up -d asr
curl.exe -s http://localhost:8000/v1/models                 # 200 且有模型列表
curl.exe -s -X POST http://localhost:8000/v1/audio/transcriptions `
  -F "file=@C:\path\to\your.mp3" -F "model=funasr"          # 返回 JSON，text 非空且中文带标点
```

## 已排除方案（来自本机实测，见 D-010）

| 方案 | 为什么排除 |
|---|---|
| 宿主机直装 FunASR / sherpa-onnx | Windows Smart App Control 拦截未签名二进制（`Application Control policy blocked this file`） |
| 宿主机 ffmpeg 抽音轨 | 同上；统一改在容器内 |
| Java 内嵌 JNI 调模型 | 依赖地狱、镜像巨大 |

## 备选实现（按顺序降级，见 tasks.md 阶段 0 失败预案）

1. FunASR 官方镜像（首选，中文/VAD/标点/说话人最强）
2. whisper.cpp 自带 server（纯 CPU，单文件即可跑）
3. 云 ASR（Spring AI `TranscriptionModel` 云配置）—— 需要 key，**不满足零成本要求，只作兜底**

## 接口契约（Java 侧只依赖这个）

```http
POST /v1/audio/transcriptions
Content-Type: multipart/form-data

file=<二进制>
model=<模型名>
language=zh          （可选）
response_format=json （可选）

→ 200
{ "text": "转写全文……" }
```

异常约定：`4xx` = 请求/文件问题（不重试）；`5xx`/超时 = 服务问题（退避重试，N2）。
