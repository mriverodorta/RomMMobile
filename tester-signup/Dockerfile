FROM python:3.13-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
RUN pip install --no-cache-dir gunicorn==27.0.1
COPY signup.py wsgi.py /app/
USER 10001:10001
EXPOSE 8080
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--workers", "2", "--preload", "--timeout", "8", "--backlog", "64", "--limit-request-line", "1024", "--limit-request-fields", "32", "--limit-request-field_size", "2048", "--forwarded-allow-ips=", "wsgi:application"]
