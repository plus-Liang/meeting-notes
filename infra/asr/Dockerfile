FROM python:3.12-slim

# pip 源可覆盖（国内默认走清华）
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    HF_HOME=/models \
    HF_HUB_OFFLINE=1 \
    PYTHONUNBUFFERED=1 \
    ASR_MODEL_NAME=small \
    ASR_LANGUAGE=zh \
    ASR_COMPUTE_TYPE=int8 \
    ASR_INITIAL_PROMPT="以下是普通话的句子，请用简体中文输出。"

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -i "$PIP_INDEX_URL" -r /tmp/requirements.txt

WORKDIR /app
COPY server.py /app/server.py

# 模型目录由 compose 挂载到 /models（只读），HF_HOME 指向它
EXPOSE 8000

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=60 \
    CMD python -c "import urllib.request,sys;sys.exit(0 if urllib.request.urlopen('http://localhost:8000/health').status==200 else 1)"

CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8000"]
