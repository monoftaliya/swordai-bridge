FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY bridge ./bridge
RUN useradd --system --uid 10001 bridge && mkdir -p /app/data && chown bridge /app/data
USER bridge

ENV PORT=8000 \
    DATABASE_URL=sqlite:////app/data/bridge.db
VOLUME ["/app/data"]
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python -c "import os,urllib.request;urllib.request.urlopen('http://127.0.0.1:%s/healthz' % os.environ.get('PORT','8000'), timeout=4)"

CMD ["python", "-m", "bridge"]
