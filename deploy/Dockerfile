# syntax=docker/dockerfile:1
FROM python:3.12-slim
WORKDIR /app
COPY server/ ./server/
ENV PYTHONUNBUFFERED=1 WATCHHUB_DATA=/data WATCHHUB_CONFIG=/data/sources.yaml
VOLUME ["/data"]
EXPOSE 8787
WORKDIR /app/server
# 零第三方依赖，所以没有 pip install
CMD ["python", "-m", "hub.main"]
