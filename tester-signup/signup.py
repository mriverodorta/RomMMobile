"""Private SQLite storage with a small, write-only browser interface."""

import argparse
from collections import deque
from contextlib import closing
import json
import os
import re
import sqlite3
import sys
import time


MAX_BODY = 1024
TOTAL_LIMIT = 250
HOURLY_LIMIT = 20
EMAIL = re.compile(
    r"[a-z0-9][a-z0-9._%+\-]{0,63}@"
    r"(?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}",
    re.ASCII,
)


def normalize_email(value):
    if not isinstance(value, str):
        return None
    value = value.strip()
    if len(value) > 254 or not value.isascii():
        return None
    email = value.lower()
    if not EMAIL.fullmatch(email):
        return None
    local = email.split("@", 1)[0]
    return email if ".." not in local and not local.endswith(".") else None


def connect(path):
    return sqlite3.connect(path, timeout=1)


def initialize(path):
    with closing(connect(path)) as db, db:
        db.execute("""
            CREATE TABLE IF NOT EXISTS signup (
                email TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL
            )
        """)


def save_signup(path, email, now):
    with closing(connect(path)) as db, db:
        db.execute("BEGIN IMMEDIATE")
        if db.execute("SELECT 1 FROM signup WHERE email = ?", (email,)).fetchone():
            return True
        total, recent = db.execute(
            "SELECT COUNT(*), COUNT(CASE WHEN created_at > ? THEN 1 END) FROM signup",
            (now - 3600,),
        ).fetchone()
        if total >= TOTAL_LIMIT or recent >= HOURLY_LIMIT:
            return False
        db.execute("INSERT INTO signup (email, created_at) VALUES (?, ?)", (email, now))
        return True


def make_app(path, allowed_origin):
    initialize(path)
    # Gunicorn uses sync workers, so each process has one request at a time.
    # This queue bounds early work even if the reverse proxy shares one address.
    recent_requests = deque()

    def application(env, start_response):
        origin = env.get("HTTP_ORIGIN")
        method = env["REQUEST_METHOD"]
        route = env["PATH_INFO"]
        headers = [("Cache-Control", "no-store"), ("Vary", "Origin"),
                   ("X-Content-Type-Options", "nosniff")]
        if origin == allowed_origin:
            headers.append(("Access-Control-Allow-Origin", allowed_origin))

        def respond(status, code, extra=()):
            body = b"" if code is None else json.dumps({"status": code}).encode()
            start_response(status, headers + list(extra) + [
                ("Content-Type", "application/json"), ("Content-Length", str(len(body))),
            ])
            return [body]

        if route == "/healthz" and method == "GET":
            try:
                with closing(connect(path)) as db:
                    db.execute("SELECT 1 FROM signup LIMIT 1").fetchone()
            except sqlite3.Error:
                return respond("503 Service Unavailable", "unavailable")
            return respond("200 OK", "rommmobiles_testers_ready")
        if route != "/api/testers":
            return respond("404 Not Found", "not_found")
        if origin != allowed_origin:
            return respond("403 Forbidden", "origin")
        if method == "OPTIONS":
            if env.get("HTTP_ACCESS_CONTROL_REQUEST_METHOD") != "POST":
                return respond("403 Forbidden", "method")
            return respond("204 No Content", None, [
                ("Access-Control-Allow-Methods", "POST"),
                ("Access-Control-Allow-Headers", "Content-Type"),
                ("Access-Control-Max-Age", "3600"),
            ])
        if method != "POST":
            return respond("405 Method Not Allowed", "method", [("Allow", "POST, OPTIONS")])

        now = time.monotonic()
        while recent_requests and recent_requests[0] <= now - 60:
            recent_requests.popleft()
        if len(recent_requests) >= 25:
            return respond("429 Too Many Requests", "try_later", [("Retry-After", "60")])
        recent_requests.append(now)

        if env.get("CONTENT_TYPE", "").split(";", 1)[0].strip().lower() != "application/json":
            return respond("415 Unsupported Media Type", "content_type")
        try:
            size = int(env.get("CONTENT_LENGTH") or "0")
        except ValueError:
            return respond("400 Bad Request", "body")
        if size < 1:
            return respond("400 Bad Request", "body")
        if size > MAX_BODY:
            return respond("413 Content Too Large", "body")
        try:
            payload = json.loads(env["wsgi.input"].read(size))
        except (ValueError, RecursionError):
            return respond("400 Bad Request", "body")
        if not isinstance(payload, dict) or set(payload) - {"email", "website"}:
            return respond("400 Bad Request", "body")

        if payload.get("website"):
            # Match the real success response so automated clients cannot check this trap.
            return respond("200 OK", "received")
        email = normalize_email(payload.get("email"))
        if email is None:
            return respond("400 Bad Request", "email")
        try:
            accepted = save_signup(path, email, int(time.time()))
        except sqlite3.Error:
            return respond("503 Service Unavailable", "unavailable")
        if not accepted:
            return respond("429 Too Many Requests", "try_later", [("Retry-After", "3600")])
        return respond("200 OK", "received")

    return application


def database_path():
    return os.environ.get("SIGNUP_DB", "/data/signups.sqlite3")


def main():
    parser = argparse.ArgumentParser(description="Manage private tester signups in the container")
    parser.add_argument("command", choices=["export", "count", "delete"])
    args = parser.parse_args()
    with closing(connect(database_path())) as db, db:
        if args.command == "export":
            for (email,) in db.execute("SELECT email FROM signup ORDER BY created_at, email"):
                print(email)
        elif args.command == "count":
            print(db.execute("SELECT COUNT(*) FROM signup").fetchone()[0])
        else:
            email = normalize_email(sys.stdin.readline(MAX_BODY))
            if email is None:
                parser.error("Provide a valid email on standard input")
            deleted = db.execute("DELETE FROM signup WHERE email = ?", (email,)).rowcount
            print(f"Deleted {deleted} signup")


if __name__ == "__main__":
    main()
