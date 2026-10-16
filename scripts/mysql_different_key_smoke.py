"""Race two distinct keys with the same receipt against the MySQL-profile API.

The script creates one unique synthetic receipt and prints only outcome metadata.
Start the packaged JAR and MySQL as described in README.md before running it.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
import threading
import urllib.error
import urllib.request
import uuid


def request(base: str, path: str, method: str = "GET", body: dict | None = None,
            token: str | None = None, key: str | None = None) -> tuple[int, dict]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if key:
        headers["Idempotency-Key"] = key
    call = urllib.request.Request(base + path,
                                  data=json.dumps(body).encode() if body is not None else None,
                                  headers=headers, method=method)
    try:
        with urllib.request.urlopen(call, timeout=20) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def main(base_url: str) -> dict:
    base = base_url.rstrip("/")
    status, auth = request(base, "/api/auth/token", "POST", {
        "username": "employee", "password": os.environ.get("CLAIMFLOW_DEMO_PASSWORD", "local-only"),
    })
    assert status == 200, ("login", status, auth)
    token = auth["access_token"]
    nonce = uuid.uuid4().hex[:12]
    body = {"category": "meal", "amountCents": 1750,
            "receiptText": f"Merchant: Different Keys {nonce}\nDate: 2026-10-15\nTotal: SGD 17.50"}
    gate = threading.Barrier(3)

    def submit(key: str) -> tuple[int, dict]:
        gate.wait(timeout=10)
        return request(base, "/api/claims", "POST", body, token, key)

    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(submit, f"mysql-diff-a-{nonce}")
        second = pool.submit(submit, f"mysql-diff-b-{nonce}")
        gate.wait(timeout=10)
        results = [first.result(timeout=25), second.result(timeout=25)]

    successes = [payload for code, payload in results if code == 200]
    duplicates = [payload for code, payload in results
                  if code == 400 and "Duplicate receipt" in payload.get("error", "")]
    assert len(successes) == len(duplicates) == 1, (
        "expected one committed claim and one classified duplicate", results)
    claim_id = successes[0]["id"]
    status, claim = request(base, f"/api/claims/{claim_id}", token=token)
    assert status == 200 and claim["state"] == "IN_REVIEW", (status, claim)
    status, owned = request(base, "/api/claims/mine", token=token)
    matching = [item for item in owned if item.get("receiptHash") == claim["receiptHash"]]
    assert status == 200 and len(matching) == 1, ("receipt count", status, matching)
    return {"backend": "MySQL", "simultaneous_distinct_keys": 2,
            "successes": 1, "classified_duplicates": 1,
            "matching_receipt_claims": len(matching), "final_state": claim["state"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    args = parser.parse_args()
    print(json.dumps(main(args.base_url), indent=2))
