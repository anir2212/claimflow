"""Run same-key concurrent HTTP submissions against a MySQL-profile ClaimFlow JAR.

Start the existing MySQL container and the packaged JAR as described in README.md.
This script uses unique fixture data and prints no credentials or receipt text.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
import threading
import time
import urllib.error
import urllib.request
import uuid


def main(base_url: str) -> dict:
    base_url = base_url.rstrip("/")

    def request(path: str, method: str = "GET", body: dict | None = None,
                token: str | None = None, key: str | None = None) -> tuple[int, dict]:
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if key:
            headers["Idempotency-Key"] = key
        data = json.dumps(body).encode() if body is not None else None
        call = urllib.request.Request(base_url + path, data=data, headers=headers,
                                      method=method)
        try:
            with urllib.request.urlopen(call, timeout=20) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    status, auth = request("/api/auth/token", "POST", {
        "username": "employee",
        "password": os.environ.get("CLAIMFLOW_DEMO_PASSWORD", "local-only"),
    })
    assert status == 200, ("login", status, auth)
    token = auth["access_token"]
    nonce = uuid.uuid4().hex[:12]
    key = f"mysql-race-{nonce}"
    body = {
        "category": "meal", "amountCents": 1450,
        "receiptText": f"Merchant: Concurrent {nonce}\nDate: 2026-10-12\nTotal: SGD 14.50",
    }
    gate = threading.Barrier(3)

    def submit() -> tuple[int, dict]:
        gate.wait(timeout=10)
        return request("/api/claims/async", "POST", body, token, key)

    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(submit)
        second = pool.submit(submit)
        gate.wait(timeout=10)
        a, b = first.result(timeout=25), second.result(timeout=25)

    assert a[0] == b[0] == 200, ("concurrent submit", a, b)
    assert a[1]["id"] == b[1]["id"], ("different IDs", a, b)
    claim_id = a[1]["id"]
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        status, claim = request(f"/api/claims/{claim_id}", token=token)
        assert status == 200, (status, claim)
        if claim["state"] == "IN_REVIEW":
            break
        time.sleep(0.1)
    else:
        raise AssertionError("scheduled extraction did not reach IN_REVIEW")

    status, duplicate = request("/api/claims", "POST", body, token, key + "-duplicate")
    assert status == 400 and "Duplicate receipt" in duplicate.get("error", ""), (
        "duplicate receipt", status, duplicate)
    return {"backend": "MySQL", "same_key_same_id": True,
            "claim_id": claim_id, "final_state": claim["state"],
            "duplicate_receipt_rejected": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    args = parser.parse_args()
    print(json.dumps(main(args.base_url), indent=2))
