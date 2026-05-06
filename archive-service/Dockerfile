FROM python:3.12-slim

WORKDIR /srv

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
# AIO catalog asset — used by import_dropbox to match dropped files
# to album/episode names without a network round-trip. Sourced from
# `android/app/src/main/assets/aio_catalog.json`; refresh both copies
# when scripts/aio-scrape-catalog.py runs.
COPY aio_catalog.json ./aio_catalog.json

ENV ODYSSEY_DATA_DIR=/data \
    PYTHONUNBUFFERED=1

EXPOSE 8088

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8088"]
