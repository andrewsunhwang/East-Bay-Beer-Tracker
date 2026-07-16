FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Chromium + system deps for the JS-rendering scrape fallback.
RUN playwright install --with-deps chromium

COPY . .

ENV DATA_DIR=/data
EXPOSE 8000

# $PORT is provided by Railway/most PaaS; defaults to 8000 elsewhere.
CMD uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}
