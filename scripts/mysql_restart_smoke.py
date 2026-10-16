"""Two-phase MySQL job durability smoke around an operator-controlled API restart.

Run prepare with the extraction poller disabled, stop the JAR, restart it with
the poller enabled, then run verify. The state file contains only a claim ID.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid


def request(base: str, path: str, method: str = "GET", body: dict | None = None,
            token: str | None = None, key: str | None = None) -> tuple[int, object]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if key:
        headers["Idempotency-Key"] = key
    call = urllib.request.Request(base + path,
                                  data=json.dumps(body).encode() if body is not None else None,
                                  headers=headers, method=method)
    try:
        with urllib.request.urlopen(call, timeout=15) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def login(base: str) -> str:
    status, auth = request(base, "/api/auth/token", "POST", {
        "username": "employee", "password": os.environ.get("CLAIMFLOW_DEMO_PASSWORD", "local-only"),
    })
    assert status == 200, ("login", status, auth)
    return auth["access_token"]


def prepare(base: str, state_file: Path) -> dict:
    token = login(base)
    nonce = uuid.uuid4().hex[:12]
    body = {"category": "meal", "amountCents": 1850,
            "receiptText": f"Merchant: Restart {nonce}\nDate: 2026-10-15\nTotal: SGD 18.50"}
    status, claim = request(base, "/api/claims/async", "POST", body, token,
                            f"mysql-restart-{nonce}")
    assert status == 200 and claim["state"] == "SUBMITTED", (status, claim)
    claim_id = claim["id"]
    # A short delay catches an accidentally enabled poller before the operator stops the JAR.
    time.sleep(2)
    status, pending = request(base, f"/api/claims/{claim_id}", token=token)
    assert status == 200 and pending["state"] == "SUBMITTED", (
        "poller must be disabled for prepare", status, pending)
    state_file.parent.mkdir(parents=True, exist_ok=True)
    state_file.write_text(json.dumps({"claim_id": claim_id}) + "\n")
    return {"phase": "prepare", "backend": "MySQL", "pending_state": "SUBMITTED",
            "claim_id": claim_id, "state_file": str(state_file)}


def verify(base: str, state_file: Path) -> dict:
    claim_id = json.loads(state_file.read_text())["claim_id"]
    token = login(base)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        status, claim = request(base, f"/api/claims/{claim_id}", token=token)
        assert status == 200, ("persisted claim missing", status, claim)
        if claim["state"] == "IN_REVIEW":
            break
        time.sleep(0.2)
    else:
        raise AssertionError("persisted job did not advance to IN_REVIEW after restart")
    status, audit = request(base, f"/api/claims/{claim_id}/audit", token=token)
    actions = [event["action"] for event in audit]
    assert status == 200 and actions == ["SUBMITTED", "EXTRACTED"], (status, actions)
    return {"phase": "verify", "backend": "MySQL", "persisted_claim_found": True,
            "final_state": claim["state"], "audit_actions": actions}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=("prepare", "verify"))
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    parser.add_argument("--state-file", type=Path,
                        default=Path("target/mysql_restart_state.json"))
    args = parser.parse_args()
    result = prepare(args.base_url.rstrip("/"), args.state_file) if args.phase == "prepare" else \
        verify(args.base_url.rstrip("/"), args.state_file)
    print(json.dumps(result, indent=2))
