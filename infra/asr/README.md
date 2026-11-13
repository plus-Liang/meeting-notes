# 自建 OpenAI 兼容 ASR 服务（已实测可用）

原计划用现成镜像，但 **Docker Hub 不通**、公共加速源**白名单拒绝了候选镜像**（详见
`docs/项目/录音纪要平台/环境搭建说明.md` §4）。因此改为自建：本机已有 `python:3.12-slim` +
本地 faster-whisper 模型（`D:\huggingface`），只依赖 PyPI（清华源）。

## 接口（Java 侧唯一依赖，改这里要同步改 spec）

| 方法 | 路径 | 返回 |
|---|---|---|
| GET | `/health` | `{"status":"ok","model":"small","ready":true}` |
| GET | `/v1/models` | `{"object":"list","data":[{"id":"small","object":"model"}]}` |
| POST | `/v1/audio/transcriptions` | multipart `file`（音视频）；返回 `{"text":"…","duration":90.0,"cost_sec":9.5}` |

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `ASR_MODEL_NAME` | `small` | 模型名（本地缓存里必须有） |
| `ASR_LANGUAGE` | `zh` | 语言 |
| `ASR_COMPUTE_TYPE` | `int8` | CPU 用 int8 |
| `ASR_INITIAL_PROMPT` | `以下是普通话的句子，请用简体中文输出。` | **不加会输出繁体** |
| `ASR_BEAM_SIZE` | `1` | 1 最快 |
| `ASR_VAD_FILTER` | `true` | 静音段跳过 |
| `HF_HOME` | `/models` | 指向挂载的模型目录 |
| `HF_HUB_OFFLINE` | `1` | 离线加载（不联网） |

## 实测数据（2026-11-13，CPU，int8，small）

| 音频 | 耗时 | RTF |
|---|---|---|
| 90 s（1.5 min） | 9.5 s | **0.105** |
| 783.8 s（13 min） | 88.6 s | **0.113** |

- 模型加载：3.9 – 7.7 s（冷启动）
- 推算 **8.44 小时音频 ≈ 57 分钟**转写（RTF 0.113）
- 镜像大小 ≈ 830 MB；不装 ffmpeg（PyAV 直接解码 mp3/mp4/wav）

## 已知坑（都踩过）

1. **av 必须 13.x**：`av==19` → `TypeError: open() got an unexpected keyword argument 'metadata_errors'`；
2. **必须给 `initial_prompt`**：否则中文输出可能是**繁体**；
3. **模型加载要 `local_files_only=True`**（或 `HF_HUB_OFFLINE=1`），否则会尝试联网；
4. **本地 `medium` / `large-v3` 缓存不完整**（只有 3 MB 配置，没有 `model.bin`）→ 目前只能用 `small`；
5. 容器内监听固定 **8000**，宿主机端口用 `ASR_PORT` 映射（`${ASR_PORT}:8000`），不要假设两边同号。

## 运行

```bash
# 单独跑
docker build -t meeting-notes-asr infra/asr
docker run --rm -p 8001:8000 -v D:\huggingface:/models:ro meeting-notes-asr

# 验证
curl http://localhost:8001/v1/models
curl -X POST http://localhost:8001/v1/audio/transcriptions -F "file=@test.wav" -F "model=small"
```
