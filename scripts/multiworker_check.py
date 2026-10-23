"""Two-instance locking check, as pre-registered in docs/MULTI_WORKER_PROTOCOL.md.

Races manager decisions, finance payments and extraction workers across two API processes that
share one MySQL database, then checks the pass criteria D1-D3, P1, E1 and G1. All receipts are
synthetic. Exits 0 when every criterion holds, 1 otherwise; per-claim detail goes to
target/multiworker_LABEL.json. Latency figures are descriptive only.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import random
import statistics
import threading
import time
import urllib.error
import urllib.request
import uuid

CLAIMS = 40
BATCH = 10
ASYNC_CLAIMS = 60
EXTRACTION_DEADLINE_S = 90


class Client:
    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.lock = threading.Lock()

    def request(self, base: str, path: str, method: str = "GET", body: dict | None = None,
                token: str | None = None, key: str | None = None, phase: str = "") -> tuple[int, dict]:
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if key:
            headers["Idempotency-Key"] = key
        call = urllib.request.Request(base + path,
                                      data=json.dumps(body).encode() if body is not None else None,
                                      headers=headers, method=method)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(call, timeout=60) as response:
                status, payload = response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                payload = json.loads(raw or b"{}")
            except ValueError:
                payload = {"raw": raw[:200].decode(errors="replace")}
            status = error.code
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            status, payload = 0, {"transport_error": repr(error)}
        elapsed = time.perf_counter() - started
        with self.lock:
            self.calls.append({"phase": phase, "status": status, "seconds": elapsed})
        return status, payload


def login(client: Client, base: str, user: str) -> str:
    status, auth = client.request(base, "/api/auth/token", "POST", {
        "username": user, "password": os.environ.get("CLAIMFLOW_DEMO_PASSWORD", "local-only")})
    if status != 200:
        raise SystemExit(f"login failed for {user} on {base}: {status} {auth}")
    return auth["access_token"]


def receipt(merchant: str, cents: int) -> str:
    return f"Merchant: {merchant}\nDate: 2026-10-24\nTotal: SGD {cents // 100}.{cents % 100:02d}"


def race(client: Client, jobs: list[tuple], phase: str) -> list[tuple[int, dict]]:
    """Runs every job at once from one barrier; each job is (base, path, body, token)."""
    gate = threading.Barrier(len(jobs))

    def fire(job: tuple) -> tuple[int, dict]:
        base, path, body, token = job
        gate.wait(timeout=30)
        return client.request(base, path, "POST", body, token, phase=phase)

    with ThreadPoolExecutor(max_workers=len(jobs)) as pool:
        return list(pool.map(fire, jobs))


def audit_actions(client: Client, base: str, token: str, claim_id: str) -> list[str]:
    status, events = client.request(base, f"/api/claims/{claim_id}/audit", token=token, phase="verify")
    if status != 200:
        return [f"AUDIT_HTTP_{status}"]
    return [event["action"] for event in events]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--a", default="http://127.0.0.1:18081")
    parser.add_argument("--b", default="http://127.0.0.1:18082")
    parser.add_argument("--label", required=True)
    parser.add_argument("--out", default=None)
    args = parser.parse_args()
    instances = [args.a.rstrip("/"), args.b.rstrip("/")]
    client = Client()
    nonce = uuid.uuid4().hex[:10]
    rng = random.Random(nonce)
    tokens = {user: login(client, instances[0], user) for user in ("employee", "manager", "finance")}
    report: dict = {"label": args.label, "nonce": nonce, "instances": instances,
                    "started": time.strftime("%Y-%m-%dT%H:%M:%S%z")}

    # 1. Set-up claims, alternately on A and B.
    claim_ids: list[str] = []
    for index in range(CLAIMS):
        cents = rng.randint(1000, 2499)
        body = {"category": "meal", "amountCents": cents,
                "receiptText": receipt(f"Race Cafe {nonce} {index}", cents)}
        status, claim = client.request(instances[index % 2], "/api/claims", "POST", body,
                                       tokens["employee"], key=f"mw-{nonce}-{index}", phase="setup")
        if status != 200 or claim.get("state") != "IN_REVIEW":
            print(json.dumps({"invalid_run": "set-up claim failed", "status": status, "body": claim}))
            return 1
        claim_ids.append(claim["id"])

    # 2. Decision race: approve and reject on each instance, ten claims per barrier.
    decisions: dict[str, list[dict]] = {claim_id: [] for claim_id in claim_ids}
    for start in range(0, CLAIMS, BATCH):
        jobs, owners = [], []
        for claim_id in claim_ids[start:start + BATCH]:
            for base in instances:
                for approve in (True, False):
                    jobs.append((base, f"/api/claims/{claim_id}/decision", {"approve": approve},
                                 tokens["manager"]))
                    owners.append((claim_id, base, approve))
        for (claim_id, base, approve), (status, body) in zip(owners, race(client, jobs, "decision")):
            decisions[claim_id].append({"instance": base, "approve": approve, "status": status,
                                        "state": body.get("state"), "error": body.get("error")})

    per_claim = []
    approved_ids = []
    for claim_id in claim_ids:
        results = decisions[claim_id]
        winners = [r for r in results if 200 <= r["status"] < 300]
        losers_409 = [r for r in results if r["status"] == 409]
        status, claim = client.request(instances[1], f"/api/claims/{claim_id}", token=tokens["manager"],
                                       phase="verify")
        final = claim.get("state")
        actions = audit_actions(client, instances[0], tokens["manager"], claim_id)
        decision_events = sum(1 for action in actions if action in ("APPROVED", "REJECTED"))
        expected = None if len(winners) != 1 else ("APPROVED" if winners[0]["approve"] else "REJECTED")
        row = {"claim": claim_id, "winners": len(winners), "conflicts_409": len(losers_409),
               "final_state": final, "expected_state": expected, "decision_audit_events": decision_events,
               "D1": len(winners) == 1 and len(losers_409) == 3,
               "D2": expected is not None and final == expected,
               "D3": decision_events == 1, "requests": results}
        per_claim.append(row)
        if final == "APPROVED":
            approved_ids.append(claim_id)

    # 3. Payment race on every approved claim.
    payments: dict[str, list[dict]] = {claim_id: [] for claim_id in approved_ids}
    for start in range(0, len(approved_ids), BATCH):
        jobs, owners = [], []
        for claim_id in approved_ids[start:start + BATCH]:
            for base in instances:
                for _ in range(2):
                    jobs.append((base, f"/api/claims/{claim_id}/paid", None, tokens["finance"]))
                    owners.append((claim_id, base))
        for (claim_id, base), (status, body) in zip(owners, race(client, jobs, "payment")):
            payments[claim_id].append({"instance": base, "status": status, "state": body.get("state"),
                                       "error": body.get("error")})
    per_payment = []
    for claim_id in approved_ids:
        results = payments[claim_id]
        winners = sum(1 for r in results if 200 <= r["status"] < 300)
        conflicts = sum(1 for r in results if r["status"] == 409)
        _, claim = client.request(instances[0], f"/api/claims/{claim_id}", token=tokens["finance"], phase="verify")
        actions = audit_actions(client, instances[1], tokens["manager"], claim_id)
        paid_events = actions.count("PAID")
        per_payment.append({"claim": claim_id, "winners": winners, "conflicts_409": conflicts,
                            "final_state": claim.get("state"), "paid_audit_events": paid_events,
                            "P1": winners == 1 and conflicts == 3 and claim.get("state") == "PAID"
                                  and paid_events == 1, "requests": results})

    # 4. Two extraction workers: async submissions on both instances, ten at a time.
    async_ids: list[str] = []
    async_errors: list[dict] = []

    def submit_async(index: int) -> tuple[int, dict]:
        cents = 1000 + index
        body = {"category": "meal", "amountCents": cents,
                "receiptText": receipt(f"Worker Deli {nonce} {index}", cents)}
        return client.request(instances[index % 2], "/api/claims/async", "POST", body, tokens["employee"],
                              key=f"mw-async-{nonce}-{index}", phase="async_submit")

    async_started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=BATCH) as pool:
        for status, body in pool.map(submit_async, range(ASYNC_CLAIMS)):
            if status == 200:
                async_ids.append(body["id"])
            else:
                async_errors.append({"status": status, "body": body})
    pending = set(async_ids)
    states: dict[str, str] = {}
    while pending and time.perf_counter() - async_started < EXTRACTION_DEADLINE_S:
        for claim_id in list(pending):
            _, claim = client.request(instances[len(pending) % 2], f"/api/claims/{claim_id}",
                                      token=tokens["manager"], phase="poll")
            states[claim_id] = claim.get("state")
            if claim.get("state") != "SUBMITTED":
                pending.discard(claim_id)
        if pending:
            time.sleep(0.5)
    extraction_seconds = time.perf_counter() - async_started
    per_async = []
    for claim_id in async_ids:
        actions = audit_actions(client, instances[0], tokens["manager"], claim_id)
        per_async.append({"claim": claim_id, "state": states.get(claim_id),
                          "extracted_audit_events": actions.count("EXTRACTED"),
                          "E1": states.get(claim_id) == "IN_REVIEW" and actions.count("EXTRACTED") == 1})

    # Criteria and descriptive timing.
    server_errors = [c for c in client.calls if c["status"] >= 500 or c["status"] == 0]

    def timing(phase: str) -> dict:
        seconds = sorted(c["seconds"] for c in client.calls if c["phase"] == phase)
        if not seconds:
            return {"requests": 0}
        return {"requests": len(seconds), "p50_ms": round(statistics.median(seconds) * 1000, 1),
                "p95_ms": round(seconds[max(0, int(len(seconds) * 0.95) - 1)] * 1000, 1),
                "max_ms": round(seconds[-1] * 1000, 1)}

    def count_status(phase: str) -> dict:
        counts: dict[str, int] = {}
        for c in client.calls:
            if c["phase"] == phase:
                counts[str(c["status"])] = counts.get(str(c["status"]), 0) + 1
        return dict(sorted(counts.items()))

    criteria = {
        "D1": all(row["D1"] for row in per_claim),
        "D2": all(row["D2"] for row in per_claim),
        "D3": all(row["D3"] for row in per_claim),
        "P1": bool(per_payment) and all(row["P1"] for row in per_payment),
        "E1": len(async_ids) == ASYNC_CLAIMS and not async_errors and all(row["E1"] for row in per_async),
        "G1": not server_errors,
    }
    summary = {
        "claims": CLAIMS,
        "decision_requests": sum(len(v) for v in decisions.values()),
        "decision_status_counts": count_status("decision"),
        "claims_failing_D1": sum(not row["D1"] for row in per_claim),
        "claims_failing_D2": sum(not row["D2"] for row in per_claim),
        "claims_failing_D3": sum(not row["D3"] for row in per_claim),
        "claims_with_multiple_2xx_decisions": sum(row["winners"] > 1 for row in per_claim),
        "max_decision_audit_events_on_one_claim": max(row["decision_audit_events"] for row in per_claim),
        "final_states": {s: sum(row["final_state"] == s for row in per_claim) for s in ("APPROVED", "REJECTED")},
        "approved_claims_raced_for_payment": len(approved_ids),
        "payment_requests": sum(len(v) for v in payments.values()),
        "payment_status_counts": count_status("payment"),
        "claims_failing_P1": sum(not row["P1"] for row in per_payment),
        "max_paid_audit_events_on_one_claim": max((row["paid_audit_events"] for row in per_payment), default=0),
        "async_submitted": len(async_ids),
        "async_submit_errors": len(async_errors),
        "async_final_states": {s: sum(row["state"] == s for row in per_async)
                               for s in sorted({row["state"] or "UNKNOWN" for row in per_async})},
        "async_failing_E1": sum(not row["E1"] for row in per_async),
        "extraction_wall_seconds": round(extraction_seconds, 1),
        "server_or_transport_errors": len(server_errors),
        "total_requests": len(client.calls),
        "timing_descriptive_only": {phase: timing(phase) for phase in ("setup", "decision", "payment", "async_submit")},
    }
    report.update({"criteria": criteria, "passed": all(criteria.values()), "summary": summary,
                   "decisions": per_claim, "payments": per_payment, "extraction": per_async,
                   "async_errors": async_errors})
    out = Path(args.out or f"target/multiworker_{args.label}.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2))
    print(json.dumps({"label": args.label, "passed": report["passed"], "criteria": criteria,
                      "summary": summary, "detail": str(out)}, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
