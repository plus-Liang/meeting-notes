"""Minimal OpenAI-compatible ASR service (validated 2026-11-13).

Contract the Java side depends on — do not change without updating the spec:
  GET  /health                     -> {"status":"ok","model":"small","ready":true}
  GET  /v1/models                  -> {"object":"list","data":[{"id":"small","object":"model"}]}
  POST /v1/audio/transcriptions    -> {"text":"...","duration":90.0,"cost_sec":9.5,
                                          "segments":[{"start":0.12,"end":3.45,"text":"..."}, ...]}
      multipart: file=<audio/video>, model=<optional>
      segments（T4.35a）：句子级时间戳，start/end 秒（3 位小数）、text 去首尾空白；
      空音频 -> segments: []；旧三键 text/duration/cost_sec 逐字不变。

Pinned versions matter (see Dockerfile / README):
  - av==13.1.0         : av 19 breaks faster-whisper 1.2.1 (av.open(metadata_errors=...))
  - faster-whisper==1.2.1
  - initial_prompt     : without a 简体中文 hint, output may come back in 繁体
"""

import os
import re
import sys
import tempfile
import time

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from faster_whisper import WhisperModel

MODEL_NAME = os.environ.get("ASR_MODEL_NAME", "small")
LANGUAGE = os.environ.get("ASR_LANGUAGE", "zh")
COMPUTE_TYPE = os.environ.get("ASR_COMPUTE_TYPE", "int8")
INITIAL_PROMPT = os.environ.get("ASR_INITIAL_PROMPT", "以下是普通话的句子，请用简体中文输出。")
BEAM_SIZE = int(os.environ.get("ASR_BEAM_SIZE", "1"))
VAD_FILTER = os.environ.get("ASR_VAD_FILTER", "true").lower() == "true"

app = FastAPI(title="meeting-notes-asr", version="0.1.0")
model = None


@app.on_event("startup")
def load_model() -> None:
    global model
    started = time.time()
    try:
        model = WhisperModel(MODEL_NAME, device="cpu", compute_type=COMPUTE_TYPE, local_files_only=True)
    except Exception as exc:
        # 快速失败（T4.9）：模型缺失时给出明确中文提示后退出码 2，绝不静默循环 unhealthy
        print(
            f"模型未找到或加载失败（ASR_MODEL_NAME={MODEL_NAME}，模型目录=/models）。"
            "请先运行 scripts/download-model.ps1 下载模型；"
            "国内网络可设 HF_ENDPOINT=https://hf-mirror.com 后重试。",
            flush=True,
        )
        print(f"[startup] 原始错误：{exc}", flush=True)
        sys.stdout.flush()
        os._exit(2)
    print(f"[startup] model={MODEL_NAME} compute={COMPUTE_TYPE} language={LANGUAGE}（部署级默认；请求可用 language 覆盖，auto=自动检测）loaded in {time.time() - started:.1f}s", flush=True)


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME, "ready": model is not None}


@app.get("/v1/models")
def models():
    return {"object": "list", "data": [{"id": MODEL_NAME, "object": "model"}]}


@app.post("/v1/audio/transcriptions")
async def transcriptions(file: UploadFile = File(...), model_name: str = Form(default=None, alias="model"),
                         language: str = Form(default=None, alias="language")):
    # T4.99/C4：per-request 语言——缺省/空白 = 部署级 LANGUAGE；auto = None（faster-whisper 自动检测）；
    # 其它须匹配 ^[a-z]{2,3}$，否则 400（与 Java 入口层同一口径，此处是最后一道闸）。
    lang = LANGUAGE
    if language is not None and language.strip():
        v = language.strip()
        if v == "auto":
            lang = None
        elif re.fullmatch(r"[a-z]{2,3}", v):
            lang = v
        else:
            raise HTTPException(status_code=400, detail="language 需为 2-3 位小写语言码（如 zh、en、ja），或 auto")
    # 中文 initial_prompt 只对中文转写有意义：显式指定非 zh 语言时置空，避免把英文音频带成中文（T4.99/A5）
    prompt = INITIAL_PROMPT or None
    if lang is not None and lang != "zh":
        prompt = None
    suffix = os.path.splitext(file.filename or "audio.wav")[1] or ".wav"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp.write(await file.read())
        tmp_path = tmp.name

    started = time.time()
    try:
        raw_segments, info = model.transcribe(
            tmp_path,
            language=lang,
            vad_filter=VAD_FILTER,
            beam_size=BEAM_SIZE,
            initial_prompt=prompt,
        )
        # 生成器只能消费一次：边遍历边收集句子级时间戳（T4.35a），
        # text 的拼接口径与旧版逐字一致（segment.text 原样连接，不去空白）。
        text_parts = []
        segments_out = []
        for segment in raw_segments:
            text_parts.append(segment.text)
            t = (segment.text or "").strip()
            if t:
                segments_out.append({
                    "start": round(float(segment.start), 3),
                    "end": round(float(segment.end), 3),
                    "text": t,
                })
        text = "".join(text_parts)
    finally:
        os.unlink(tmp_path)

    cost = time.time() - started
    duration = float(info.duration or 0.0)
    rtf = cost / duration if duration > 0 else 0.0
    # T4.99：返回体只增 language 键——实际传给 faster-whisper 的语言；auto 时用检测到的 info.language（读不到则 "auto"）
    effective = lang if lang is not None else (getattr(info, "language", None) or "auto")
    print(f"[asr] language={effective} audio={duration:.1f}s cost={cost:.1f}s RTF={rtf:.3f} segments={len(segments_out)}", flush=True)
    return JSONResponse({"text": text, "duration": round(duration, 1), "cost_sec": round(cost, 2),
                         "segments": segments_out, "language": effective})
