from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from io import BytesIO
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

from signup import HOURLY_LIMIT, TOTAL_LIMIT, make_app, save_signup

ORIGIN = "https://mriverodorta.github.io"


class SignupTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.db = Path(self.directory.name) / "signups.sqlite3"
        self.app = make_app(self.db, ORIGIN)

    def request(self, body=b'{"email":"tester@example.com"}', **overrides):
        env = {
            "REQUEST_METHOD": "POST",
            "PATH_INFO": "/api/testers",
            "HTTP_ORIGIN": ORIGIN,
            "CONTENT_TYPE": "application/json",
            "CONTENT_LENGTH": str(len(body)),
            "wsgi.input": BytesIO(body),
            **overrides,
        }
        started = []
        result = b"".join(self.app(env, lambda code, headers: started.append((code, dict(headers)))))
        return int(started[0][0].split()[0]), started[0][1], json.loads(result) if result else None

    def test_origin_and_preflight(self):
        code, headers, _ = self.request(HTTP_ORIGIN="https://example.com")
        self.assertEqual(code, 403)
        self.assertNotIn("Access-Control-Allow-Origin", headers)
        code, headers, _ = self.request(REQUEST_METHOD="OPTIONS", HTTP_ACCESS_CONTROL_REQUEST_METHOD="POST")
        self.assertEqual(code, 204)
        self.assertEqual(headers["Access-Control-Allow-Origin"], ORIGIN)
        self.assertEqual(headers["Access-Control-Allow-Headers"], "Content-Type")

    def test_public_endpoint_has_no_read_access(self):
        self.assertEqual(self.request(REQUEST_METHOD="GET")[0], 405)
        self.assertEqual(self.request(PATH_INFO="/export", REQUEST_METHOD="GET")[0], 404)
        _, _, response = self.request(PATH_INFO="/healthz", REQUEST_METHOD="GET")
        self.assertEqual(response, {"status": "rommmobiles_testers_ready"})

    def test_invalid_payloads(self):
        self.assertEqual(self.request(b'{"email":"no-address"}')[0], 400)
        self.assertEqual(self.request(b'{"email":"a@b.com","extra":"x"}')[0], 400)
        self.assertEqual(self.request(b"x" * 1025)[0], 413)
        self.assertEqual(self.request(CONTENT_TYPE="text/plain")[0], 415)

    def test_duplicate_and_honeypot_do_not_add_rows(self):
        self.assertEqual(self.request()[0], 200)
        self.assertEqual(self.request(b'{"email":" TESTER@EXAMPLE.COM " }')[0], 200)
        self.assertEqual(self.request(b'{"email":"another@example.com","website":"filled"}')[0], 200)
        with closing(sqlite3.connect(self.db)) as db:
            self.assertEqual(db.execute("SELECT email FROM signup").fetchall(), [("tester@example.com",)])

    def test_hourly_limit_is_atomic(self):
        def add(index):
            return save_signup(self.db, f"tester{index}@example.com", 10000)
        with ThreadPoolExecutor(4) as pool:
            self.assertEqual(sum(pool.map(add, range(HOURLY_LIMIT + 10))), HOURLY_LIMIT)
        self.assertTrue(save_signup(self.db, "new@example.com", 13601))

    def test_total_limit_survives_restart(self):
        for index in range(TOTAL_LIMIT):
            self.assertTrue(save_signup(self.db, f"tester{index}@example.com", 10000 + index * 3601))
        make_app(self.db, ORIGIN)
        self.assertFalse(save_signup(self.db, "blocked@example.com", 10000 + TOTAL_LIMIT * 3601))
        self.assertTrue(save_signup(self.db, "tester0@example.com", 10000 + TOTAL_LIMIT * 3601))


if __name__ == "__main__":
    unittest.main()
