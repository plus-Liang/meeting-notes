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
import tempfile
import time

from fastapi import FastAPI, File, Form, UploadFile
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
    model = WhisperModel(MODEL_NAME, device="cpu", compute_type=COMPUTE_TYPE, local_files_only=True)
    print(f"[startup] model={MODEL_NAME} compute={COMPUTE_TYPE} loaded in {time.time() - started:.1f}s", flush=True)


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME, "ready": model is not None}


@app.get("/v1/models")
def models():
    return {"object": "list", "data": [{"id": MODEL_NAME, "object": "model"}]}


@app.post("/v1/audio/transcriptions")
async def transcriptions(file: UploadFile = File(...), model_name: str = Form(default=None, alias="model")):
    suffix = os.path.splitext(file.filename or "audio.wav")[1] or ".wav"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp.write(await file.read())
        tmp_path = tmp.name

    started = time.time()
    try:
        raw_segments, info = model.transcribe(
            tmp_path,
            language=LANGUAGE,
            vad_filter=VAD_FILTER,
            beam_size=BEAM_SIZE,
            initial_prompt=INITIAL_PROMPT or None,
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
    print(f"[asr] audio={duration:.1f}s cost={cost:.1f}s RTF={rtf:.3f} segments={len(segments_out)}", flush=True)
    return JSONResponse({"text": text, "duration": round(duration, 1), "cost_sec": round(cost, 2),
                         "segments": segments_out})
