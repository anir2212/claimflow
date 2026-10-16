"""Exercise a packaged ClaimFlow API against its MySQL profile using stdlib only."""

from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.request
import uuid


def main(base_url: str) -> dict:
    base_url = base_url.rstrip("/")

    def request(path: str, method: str = "GET", body: dict | None = None,
                token: str | None = None, key: str | None = None) -> tuple[int, object]:
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if key:
            headers["Idempotency-Key"] = key
        data = json.dumps(body).encode() if body is not None else None
        call = urllib.request.Request(base_url + path, data=data, headers=headers,
                                      method=method)
        try:
            with urllib.request.urlopen(call, timeout=15) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def login(user: str) -> str:
        status, response = request(
            "/api/auth/token", "POST",
            {"username": user, "password": os.environ.get("CLAIMFLOW_DEMO_PASSWORD", "local-only")},
        )
        assert status == 200, (user, status, response)
        return response["access_token"]

    employee, manager, finance = (login(user) for user in
                                  ("employee", "manager", "finance"))
    nonce = uuid.uuid4().hex[:12]
    receipt = f"Merchant: Smoke {nonce}\nDate: 2026-10-12\nTotal: SGD 12.50"
    body = {"category": "meal", "amountCents": 1250, "receiptText": receipt}
    key = f"mysql-smoke-{nonce}"
    status, first = request("/api/claims", "POST", body, employee, key)
    assert status == 200 and first["state"] == "IN_REVIEW", (status, first)
    claim_id = first["id"]
    status, replay = request("/api/claims", "POST", body, employee, key)
    assert status == 200 and replay["id"] == claim_id, (status, replay)
    status, duplicate = request("/api/claims", "POST", body, employee, key + "-duplicate")
    assert status == 400 and "Duplicate receipt" in duplicate["error"], (status, duplicate)
    status, review = request(f"/api/claims/{claim_id}/review", token=manager)
    assert status == 200 and review["eligible"], (status, review)
    status, approved = request(f"/api/claims/{claim_id}/decision", "POST",
                               {"approve": True}, manager)
    assert status == 200 and approved["state"] == "APPROVED", (status, approved)
    status, paid = request(f"/api/claims/{claim_id}/paid", "POST", {}, finance)
    assert status == 200 and paid["state"] == "PAID", (status, paid)
    status, audit = request(f"/api/claims/{claim_id}/audit", token=employee)
    actions = [event["action"] for event in audit]
    assert status == 200 and actions == ["SUBMITTED", "APPROVED", "PAID"], (status, actions)
    return {"claim_id": claim_id, "backend": "MySQL", "replay_same_id": True,
            "duplicate_rejected": True, "final_state": paid["state"], "audit_actions": actions}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    args = parser.parse_args()
    print(json.dumps(main(args.base_url), indent=2))
