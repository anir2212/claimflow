"""Crash and sustained-load check, as pre-registered in docs/CRASH_LOAD_PROTOCOL.md.

Starts a throwaway MySQL 8.4 server and two API processes from the same JAR, drives the
pre-registered load mix for 600 seconds, kills API processes with `kill -9` on the pre-registered
schedule, then checks invariants C1-C9 with SQL over `claims`, `audit_events` and
`extraction_jobs` plus the client's own request records.

Every threshold, count and kill time in the PROTOCOL section below is pre-registered in
`docs/CRASH_LOAD_PROTOCOL.md` (frozen at commit f5d9aa3) and must not be changed here. The
criterion functions `check_c1` .. `check_c9` are pure: they take decoded database rows and client
records and return a verdict dict, so they can be unit tested on synthetic rows without a run
(`scripts/crash_load_check_test.py`).

Exit codes: 0 pass, 1 failure, 2 invalid run. All claims and receipts are synthetic. Latency
figures are descriptive only and are not a capacity claim.
"""

from __future__ import annotations

import argparse
import itertools
import json
import os
from pathlib import Path
import queue
import random
import shutil
import signal
import socket
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter

# --------------------------------------------------------------------------------------------
# PROTOCOL constants. Pre-registered in docs/CRASH_LOAD_PROTOCOL.md. Do not edit.
# --------------------------------------------------------------------------------------------
LOAD_SECONDS = 600
SUBMITTER_THREADS = 6
SUBMIT_GAP_S = 1.0
REVIEWER_THREADS = 12
CONSOLE_GAP_S = 10.0
BURST_CLAIMS = 120
BURST_THREADS = 20
RETRY_WINDOW_S = 90.0
RETRY_GAP_S = 1.0
DRAIN_SECONDS = 180
KILL_A_AT = 150
RESTART_A_AT = 210
BURST_AT = 360
DOUBLE_KILL_LATEST = 363
RESTART_BOTH_AT = 390
HEALTHY_WITHIN_S = 60
MIN_ACKED_CLAIMS = 1000
IN_FLIGHT_WINDOW_S = 5.0
TRANSPORT_GRACE_S = 2.0
DISK_ABORT_BYTES = int(8.1 * 1024 ** 3)
EARLY_WINDOW = (60.0, 120.0)
LATE_WINDOW = (540.0, 600.0)
SUSTAIN_RATIO = 0.50
MIX = (("async_valid", 0.70), ("sync_valid", 0.20), ("async_unparseable", 0.10))
RECEIPT_DATE = "2026-11-06"
AMOUNT_MIN_CENTS = 1000
AMOUNT_MAX_CENTS = 2499
MYSQL_PORT = 13316
PORT_A = 18081
PORT_B = 18082
BUFFER_POOL_BYTES = 134217728
MAX_CONNECTIONS = 151
API_HEAP = "-Xmx384m"
EVIDENCE_DATE = "2026-11-06"  # The protocol fixes the evidence filename's date.

# Shake-down only: at most 120 seconds with one kill, for debugging the script. Not scored.
SHAKEDOWN_LOAD_SECONDS = 100
SHAKEDOWN_KILL_AT = 40
SHAKEDOWN_RESTART_AT = 60
SHAKEDOWN_DRAIN_SECONDS = 60

MYSQLD = "/opt/anaconda3/bin/mysqld"
MYSQL_CLI = "/opt/anaconda3/bin/mysql"
MYSQL_BASEDIR = "/opt/anaconda3"
MYSQL_MESSAGES = "/opt/anaconda3/share/mysql"
JAR_NAME = "claimflow-0.1.0.jar"
DEMO_PASSWORD = "local-only"
JWT_SECRET = "crash-load-check-secret-local-only"
STATE_CHANGING_PHASES = frozenset({"submit", "burst", "correction", "decision", "payment"})
CONFLICT_ALLOWED_PHASES = frozenset({"decision", "payment", "correction"})

# --------------------------------------------------------------------------------------------
# Criterion functions. Pure: rows in, verdict out. Unit tested on synthetic rows.
#
# `db` is {"claims": [{"id","state","receipt_hash","idempotency_key"}],
#          "audit":  [{"claim_id","action"}],
#          "jobs":   [{"claim_id","status","attempts"}],
#          "flyway_unsuccessful": int}
# `rec` is the client's record dict (see Recorder.snapshot).
# --------------------------------------------------------------------------------------------

AUDIT_PREFIXES = (
    ("SUBMITTED",),                                              # synchronous, no job
    ("SUBMITTED", "EXTRACTED"),                                   # async extraction
    ("SUBMITTED", "NEEDS_HUMAN_CHECK", "RECEIPT_CHECKED"),        # corrected by a manager
)
STATE_SUFFIXES = {
    "IN_REVIEW": (),
    "APPROVED": ("APPROVED",),
    "REJECTED": ("REJECTED",),
    "PAID": ("APPROVED", "PAID"),
}


def allowed_audit_chains(state: str) -> list[Counter]:
    """The audit action multisets C5 allows for a claim in `state`."""
    if state == "SUBMITTED":
        return [Counter(("SUBMITTED",))]
    if state == "NEEDS_HUMAN_CHECK":
        return [Counter(("SUBMITTED", "NEEDS_HUMAN_CHECK"))]
    if state not in STATE_SUFFIXES:
        return []  # DRAFT and EXTRACTED are transient; no persisted claim may sit in them.
    suffix = STATE_SUFFIXES[state]
    return [Counter(prefix + suffix) for prefix in AUDIT_PREFIXES]


def audit_by_claim(db: dict) -> dict[str, Counter]:
    grouped: dict[str, Counter] = {}
    for row in db["audit"]:
        grouped.setdefault(row["claim_id"], Counter())[row["action"]] += 1
    return grouped


def claims_by_id(db: dict) -> dict[str, dict]:
    return {row["id"]: row for row in db["claims"]}


def check_c1(db: dict, rec: dict) -> dict:
    """C1, no lost claim: every 2xx-acknowledged claim ID is in the database."""
    present = set(claims_by_id(db))
    acked = {ack["claim_id"] for ack in rec["acks"]}
    missing = sorted(acked - present)
    return {"ok": not missing,
            "acknowledged_claims": len(acked),
            "claims_in_database": len(present),
            "acknowledged_but_missing": len(missing),
            "missing_examples": missing[:10]}


def check_c2(db: dict, rec: dict) -> dict:
    """C2, idempotent retries: one claim per key, one ID per key, no shared receipt hash."""
    ids_by_key: dict[str, set[str]] = {}
    for ack in rec["acks"]:
        ids_by_key.setdefault(ack["key"], set()).add(ack["claim_id"])
    keys_with_two_ids = sorted(k for k, ids in ids_by_key.items() if len(ids) > 1)
    claims_per_key = Counter(row["idempotency_key"] for row in db["claims"])
    keys_with_two_claims = sorted(k for k, n in claims_per_key.items() if n > 1)
    hashes = Counter(row["receipt_hash"] for row in db["claims"] if row["receipt_hash"])
    shared_hashes = sorted(h for h, n in hashes.items() if n > 1)
    retried_keys = sorted(rec["retried_submit_keys"])
    claims_for_retried = sum(claims_per_key.get(key, 0) for key in retried_keys)
    return {"ok": not keys_with_two_ids and not keys_with_two_claims and not shared_hashes,
            "distinct_submit_keys_acknowledged": len(ids_by_key),
            "keys_returning_two_different_ids": len(keys_with_two_ids),
            "keys_with_more_than_one_claim_row": len(keys_with_two_claims),
            "claims_sharing_a_receipt_hash": len(shared_hashes),
            "submit_keys_retried_after_a_failed_attempt": len(retried_keys),
            "claim_rows_for_those_retried_keys": claims_for_retried,
            "examples": {"two_ids": keys_with_two_ids[:5], "two_claims": keys_with_two_claims[:5],
                         "shared_hash": shared_hashes[:5]}}


def check_c3(db: dict, rec: dict) -> dict:
    """C3, one decision: at most one decision audit event and one 2xx reply, and they agree."""
    audit = audit_by_claim(db)
    claims = claims_by_id(db)
    accepted: dict[str, list[bool]] = {}
    replies: Counter = Counter()
    for row in rec["decisions"]:
        replies[row["status"]] += 1
        if 200 <= row["status"] < 300:
            accepted.setdefault(row["claim_id"], []).append(bool(row["approve"]))
    multi_audit, multi_2xx, disagreements = [], [], []
    for claim_id, counts in audit.items():
        if counts["APPROVED"] + counts["REJECTED"] > 1:
            multi_audit.append(claim_id)
    for claim_id, approvals in accepted.items():
        if len(approvals) > 1:
            multi_2xx.append(claim_id)
            continue
        expected = {"APPROVED", "PAID"} if approvals[0] else {"REJECTED"}
        state = claims.get(claim_id, {}).get("state")
        if state not in expected:
            disagreements.append({"claim": claim_id, "state": state, "approved": approvals[0]})
    return {"ok": not multi_audit and not multi_2xx and not disagreements,
            "decision_requests": sum(replies.values()),
            "decision_status_counts": dict(sorted(replies.items())),
            "claims_with_a_2xx_decision": len(accepted),
            "claims_with_more_than_one_decision_audit_event": len(multi_audit),
            "claims_with_more_than_one_2xx_decision": len(multi_2xx),
            "claims_whose_state_disagrees_with_the_2xx_decision": len(disagreements),
            "examples": {"multi_audit": multi_audit[:5], "multi_2xx": multi_2xx[:5],
                         "disagreement": disagreements[:5]}}


def check_c4(db: dict, rec: dict) -> dict:
    """C4, one payment: at most one PAID event and one 2xx reply; PAID follows APPROVED."""
    audit = audit_by_claim(db)
    claims = claims_by_id(db)
    accepted: Counter = Counter()
    replies: Counter = Counter()
    for row in rec["payments"]:
        replies[row["status"]] += 1
        if 200 <= row["status"] < 300:
            accepted[row["claim_id"]] += 1
    multi_audit = sorted(cid for cid, counts in audit.items() if counts["PAID"] > 1)
    multi_2xx = sorted(cid for cid, n in accepted.items() if n > 1)
    not_paid = sorted(cid for cid in accepted
                      if claims.get(cid, {}).get("state") != "PAID")
    paid_without_approved = sorted(cid for cid, counts in audit.items()
                                   if counts["PAID"] and not counts["APPROVED"])
    state_paid_without_event = sorted(cid for cid, row in claims.items()
                                      if row["state"] == "PAID"
                                      and audit.get(cid, Counter())["APPROVED"] != 1)
    return {"ok": not multi_audit and not multi_2xx and not not_paid
                  and not paid_without_approved and not state_paid_without_event,
            "payment_requests": sum(replies.values()),
            "payment_status_counts": dict(sorted(replies.items())),
            "claims_with_a_2xx_payment": len(accepted),
            "claims_with_more_than_one_paid_audit_event": len(multi_audit),
            "claims_with_more_than_one_2xx_payment": len(multi_2xx),
            "claims_with_a_2xx_payment_not_in_state_paid": len(not_paid),
            "claims_paid_without_an_approved_event": len(paid_without_approved),
            "claims_in_state_paid_without_exactly_one_approved_event":
                len(state_paid_without_event),
            "examples": {"multi_audit": multi_audit[:5], "multi_2xx": multi_2xx[:5],
                         "not_paid": not_paid[:5],
                         "paid_without_approved": paid_without_approved[:5]}}


def check_c5(db: dict, rec: dict) -> dict:
    """C5, audit matches state: each claim's audit multiset equals an allowed chain."""
    audit = audit_by_claim(db)
    claims = claims_by_id(db)
    orphans = sorted({cid for cid in audit if cid not in claims})
    mismatched = []
    states: Counter = Counter()
    for claim_id, row in claims.items():
        states[row["state"]] += 1
        actual = audit.get(claim_id, Counter())
        if actual not in allowed_audit_chains(row["state"]):
            mismatched.append({"claim": claim_id, "state": row["state"],
                               "actions": sorted(actual.elements())})
    return {"ok": not orphans and not mismatched,
            "claims_checked": len(claims),
            "audit_events": len(db["audit"]),
            "claims_in_each_state": dict(sorted(states.items())),
            "claims_whose_audit_does_not_match_its_state": len(mismatched),
            "audit_events_without_a_claim": len(orphans),
            "examples": {"mismatched": mismatched[:5], "orphans": orphans[:5]}}


def check_c6(db: dict, rec: dict) -> dict:
    """C6, jobs accounted for: nothing left queued, and the human-check split is exactly right."""
    audit = audit_by_claim(db)
    claims = claims_by_id(db)
    jobs = {row["claim_id"]: row for row in db["jobs"]}
    unfinished = sorted(cid for cid, row in jobs.items()
                        if row["status"] in ("PENDING", "RETRY"))
    still_submitted = sorted(cid for cid, row in claims.items() if row["state"] == "SUBMITTED")
    async_without_one_job = sorted(cid for cid in rec["async_claims"]
                                   if cid in claims and cid not in jobs)
    snapshot = list(rec["job_snapshot"])
    snapshot_unfinished = sorted(cid for cid in snapshot
                                 if jobs.get(cid, {}).get("status") in ("PENDING", "RETRY"))
    snapshot_submitted = sorted(cid for cid in snapshot
                                if claims.get(cid, {}).get("state") == "SUBMITTED")
    valid_to_human = sorted(cid for cid in rec["valid_receipt_claims"]
                            if audit.get(cid, Counter())["NEEDS_HUMAN_CHECK"])
    unparseable_not_human = sorted(cid for cid in rec["unparseable_claims"]
                                   if cid in claims
                                   and not audit.get(cid, Counter())["NEEDS_HUMAN_CHECK"])
    statuses = Counter(row["status"] for row in db["jobs"])
    return {"ok": not unfinished and not still_submitted and not async_without_one_job
                  and not snapshot_unfinished and not snapshot_submitted
                  and not valid_to_human and not unparseable_not_human,
            "jobs": len(jobs),
            "job_status_counts": dict(sorted(statuses.items())),
            "jobs_still_pending_or_retry": len(unfinished),
            "claims_still_submitted": len(still_submitted),
            "async_claims_without_exactly_one_job": len(async_without_one_job),
            "job_snapshot_at_double_kill": len(snapshot),
            "snapshot_jobs_still_pending_or_retry": len(snapshot_unfinished),
            "snapshot_claims_still_submitted": len(snapshot_submitted),
            "valid_receipt_claims_that_reached_needs_human_check": len(valid_to_human),
            "unparseable_claims_that_did_not_reach_needs_human_check": len(unparseable_not_human),
            # Reported, not scored: a DONE job with attempts > 0 shows a kill or error used a retry.
            "reported_done_jobs_with_attempts_above_zero":
                sum(1 for row in db["jobs"] if row["status"] == "DONE" and row["attempts"] > 0),
            "reported_dead_jobs": statuses.get("DEAD", 0),
            "reported_max_attempts_on_one_job":
                max((row["attempts"] for row in db["jobs"]), default=0),
            "reported_synchronous_claims_with_a_job":
                sum(1 for cid in rec["sync_claims"] if cid in jobs),
            "examples": {"unfinished": unfinished[:5], "still_submitted": still_submitted[:5],
                         "valid_to_human": valid_to_human[:5],
                         "unparseable_not_human": unparseable_not_human[:5]}}


def in_down_window(record: dict, windows: list[dict]) -> bool:
    """True when the record's instance was down at that moment, with 2 seconds of grace."""
    return any(window["instance"] == record["instance"]
               and window["from"] <= record["t"] <= window["to"] + TRANSPORT_GRACE_S
               for window in windows)


def check_c7(db: dict, rec: dict) -> dict:
    """C7, availability around the kills.

    Scope: load, console and verification requests. `/actuator/health` probes are the client's
    own down-detector, not load, so they are reported separately rather than scored; C8 already
    assumes a restarting process is not yet healthy.
    """
    load = [h for h in rec["http"] if h["phase"] != "health"]
    server_errors = [h for h in load if h["status"] >= 500]
    transport = [h for h in load if h["status"] == 0]
    stray_transport = [h for h in transport if not in_down_window(h, rec["down_windows"])]
    bad_4xx = [h for h in load if 400 <= h["status"] < 500
               and not (h["status"] == 409 and h["phase"] in CONFLICT_ALLOWED_PHASES)]
    gave_up = [h for h in load if h.get("gave_up")]
    statuses = Counter(h["status"] for h in load)
    return {"ok": not server_errors and not stray_transport and not bad_4xx and not gave_up,
            "requests_scored": len(load),
            "status_counts": dict(sorted(statuses.items())),
            "server_errors_5xx": len(server_errors),
            "transport_failures": len(transport),
            "transport_failures_outside_a_down_window": len(stray_transport),
            "non_409_4xx": len(bad_4xx),
            "conflicts_409_on_decision_payment_or_correction":
                sum(1 for h in load if h["status"] == 409 and h["phase"] in CONFLICT_ALLOWED_PHASES),
            "requests_that_gave_up_after_90s_of_retries": len(gave_up),
            "reported_health_probe_non_200":
                sum(1 for h in rec["http"] if h["phase"] == "health" and h["status"] != 200),
            "examples": {"server_errors": server_errors[:5], "stray_transport": stray_transport[:5],
                         "non_409_4xx": bad_4xx[:5], "gave_up": gave_up[:5]}}


def check_c8(db: dict, rec: dict) -> dict:
    """C8, recovery: each restarted process reports healthy within 60 s; Flyway is clean."""
    slow = [r for r in rec["restarts"] if not r["healthy"] or r["seconds"] > HEALTHY_WITHIN_S]
    return {"ok": not slow and db["flyway_unsuccessful"] == 0 and bool(rec["restarts"]),
            "restarts": len(rec["restarts"]),
            "restarts_not_healthy_within_60s": len(slow),
            "seconds_to_healthy": [round(r["seconds"], 1) for r in rec["restarts"]],
            "slowest_seconds_to_healthy":
                round(max((r["seconds"] for r in rec["restarts"]), default=0.0), 1),
            "unsuccessful_flyway_rows": db["flyway_unsuccessful"],
            "manual_repairs": 0,
            "examples": {"slow": slow}}


def check_c9(db: dict, rec: dict) -> dict:
    """C9, sustained: both healthy at the end, no OOM, late throughput at least half of early."""
    def window(span: tuple[float, float]) -> int:
        return sum(1 for h in rec["http"] if h.get("state_changing")
                   and 200 <= h["status"] < 300 and span[0] <= h["t"] < span[1])
    early, late = window(EARLY_WINDOW), window(LATE_WINDOW)
    oom = sorted(name for name, found in rec["oom"].items() if found)
    unhealthy = sorted(name for name, up in rec["final_health"].items() if not up)
    ratio = (late / early) if early else None
    return {"ok": not oom and not unhealthy and late >= SUSTAIN_RATIO * early,
            "state_changing_2xx_seconds_60_to_120": early,
            "state_changing_2xx_seconds_540_to_600": late,
            "late_over_early": None if ratio is None else round(ratio, 3),
            "threshold": SUSTAIN_RATIO,
            "instances_not_healthy_at_the_end": unhealthy,
            "logs_with_outofmemoryerror": oom}


CRITERIA = {"C1": check_c1, "C2": check_c2, "C3": check_c3, "C4": check_c4, "C5": check_c5,
            "C6": check_c6, "C7": check_c7, "C8": check_c8, "C9": check_c9}
CRITERION_TITLES = {
    "C1": "no lost claim", "C2": "idempotent retries", "C3": "one decision",
    "C4": "one payment", "C5": "audit matches state", "C6": "jobs accounted for",
    "C7": "availability around the kills", "C8": "recovery", "C9": "sustained",
}


def invalidity_reasons(db: dict, rec: dict, *, scored: bool) -> list[str]:
    """The protocol's own rules for reporting a run as invalid rather than passed or failed."""
    reasons: list[str] = []
    if rec.get("disk_abort"):
        reasons.append(f"disk watchdog stopped the run: free disk reached "
                       f"{rec['disk_abort']['free_gib']} GiB at t={rec['disk_abort']['t']}s "
                       f"(abort floor 8.1 GiB)")
    if not scored:
        return reasons
    acked = len({ack["claim_id"] for ack in rec["acks"]})
    if acked < MIN_ACKED_CLAIMS:
        reasons.append(f"only {acked} claims acknowledged, fewer than {MIN_ACKED_CLAIMS}")
    for kill in rec["kills"]:
        in_flight = sum(1 for h in rec["http"]
                        if h["phase"] != "health" and h["status"] == 0
                        and h["instance"] == kill["instance"]
                        and kill["t"] <= h["t"] < kill["t"] + IN_FLIGHT_WINDOW_S)
        if not in_flight:
            reasons.append(f"no request in flight at the kill of {kill['instance']} at "
                           f"t={round(kill['t'], 1)}s (no transport failure in its first "
                           f"{int(IN_FLIGHT_WINDOW_S)} seconds)")
    if not rec["job_snapshot"]:
        reasons.append("empty extraction job snapshot at the double kill")
    return reasons


def evaluate(db: dict, rec: dict, *, scored: bool) -> dict:
    results = {name: check(db, rec) for name, check in CRITERIA.items()}
    reasons = invalidity_reasons(db, rec, scored=scored)
    verdict = "invalid" if reasons else ("pass" if all(r["ok"] for r in results.values())
                                         else "fail")
    return {"verdict": verdict, "invalid_reasons": reasons,
            "criteria": {name: results[name]["ok"] for name in CRITERIA},
            "criterion_titles": CRITERION_TITLES, "criterion_detail": results}


def exit_code(verdict: str) -> int:
    return {"pass": 0, "fail": 1, "invalid": 2}[verdict]


# --------------------------------------------------------------------------------------------
# Orchestration
# --------------------------------------------------------------------------------------------

def free_bytes() -> int:
    stat = os.statvfs("/")
    return stat.f_bavail * stat.f_frsize


def gib(value: int) -> float:
    return round(value / 1024 ** 3, 2)


def port_free(port: int) -> bool:
    with socket.socket() as probe:
        probe.settimeout(1.0)
        return probe.connect_ex(("127.0.0.1", port)) != 0


class Recorder:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.http: list[dict] = []
        self.acks: list[dict] = []
        self.decisions: list[dict] = []
        self.payments: list[dict] = []
        self.corrections: list[dict] = []
        self.async_claims: list[str] = []
        self.sync_claims: list[str] = []
        self.valid_receipt_claims: list[str] = []
        self.unparseable_claims: list[str] = []
        self.retried_submit_keys: set[str] = set()
        self.job_snapshot: list[str] = []
        self.kills: list[dict] = []
        self.restarts: list[dict] = []
        self.down_windows: list[dict] = []
        self.disk_samples: list[dict] = []
        self.disk_abort: dict | None = None
        self.notes: list[str] = []
        self.oom: dict[str, bool] = {}
        self.final_health: dict[str, bool] = {}

    def add_http(self, record: dict) -> None:
        with self.lock:
            self.http.append(record)

    def note(self, text: str, t: float) -> None:
        with self.lock:
            self.notes.append({"t": round(t, 1), "note": text})

    def snapshot(self) -> dict:
        with self.lock:
            return {"http": list(self.http), "acks": list(self.acks),
                    "decisions": list(self.decisions), "payments": list(self.payments),
                    "corrections": list(self.corrections),
                    "async_claims": list(self.async_claims),
                    "sync_claims": list(self.sync_claims),
                    "valid_receipt_claims": list(self.valid_receipt_claims),
                    "unparseable_claims": list(self.unparseable_claims),
                    "retried_submit_keys": set(self.retried_submit_keys),
                    "job_snapshot": list(self.job_snapshot), "kills": list(self.kills),
                    "restarts": list(self.restarts), "down_windows": list(self.down_windows),
                    "disk_samples": list(self.disk_samples), "disk_abort": self.disk_abort,
                    "notes": list(self.notes), "oom": dict(self.oom),
                    "final_health": dict(self.final_health)}


class MysqlServer:
    """A throwaway MySQL 8.4 server on 127.0.0.1:13316 with a fresh data directory."""

    def __init__(self, root: Path, port: int = MYSQL_PORT) -> None:
        self.root = root
        self.port = port
        self.datadir = root / "data"
        # macOS limits Unix-domain socket paths to 103 bytes. The workspace path is longer.
        self.socket = Path("/private/tmp") / f"claimflow-crash-{os.getpid()}.sock"
        self.error_log = root / "mysqld.err"
        self.process: subprocess.Popen | None = None
        self.command: list[str] = []

    def initialise(self) -> None:
        if self.root.exists():
            shutil.rmtree(self.root)
        self.root.mkdir(parents=True)
        done = subprocess.run([MYSQLD, "--no-defaults", "--initialize-insecure",
                               f"--basedir={MYSQL_BASEDIR}",
                               f"--lc-messages-dir={MYSQL_MESSAGES}",
                               f"--datadir={self.datadir}",
                               f"--log-error={self.root / 'initialize.err'}"],
                              capture_output=True, text=True, timeout=600)
        if done.returncode != 0:
            raise RuntimeError(f"mysqld --initialize-insecure failed: {done.returncode}\n"
                               f"{done.stdout}\n{done.stderr}\n"
                               f"{(self.root / 'initialize.err').read_text()[-3000:]}")

    def start(self) -> None:
        self.command = [MYSQLD, "--no-defaults", f"--basedir={MYSQL_BASEDIR}",
                        f"--lc-messages-dir={MYSQL_MESSAGES}", f"--datadir={self.datadir}",
                        f"--port={self.port}", f"--socket={self.socket}",
                        "--bind-address=127.0.0.1", "--mysqlx=0",
                        f"--innodb-buffer-pool-size={BUFFER_POOL_BYTES}",
                        f"--max-connections={MAX_CONNECTIONS}",
                        f"--log-error={self.error_log}",
                        f"--pid-file={self.root / 'mysqld.pid'}",
                        f"--secure-file-priv={self.root}"]
        self.process = subprocess.Popen(self.command, stdout=subprocess.DEVNULL,
                                        stderr=subprocess.DEVNULL, start_new_session=True)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError(f"mysqld exited {self.process.returncode}\n"
                                   f"{self.error_log.read_text()[-3000:]}")
            if not port_free(self.port) and self.ping():
                return
            time.sleep(0.5)
        raise RuntimeError("mysqld did not accept connections within 120 s")

    def ping(self) -> bool:
        done = subprocess.run([MYSQL_CLI, "--no-defaults", f"--socket={self.socket}",
                               "-u", "root", "-e", "SELECT 1"],
                              capture_output=True, text=True)
        return done.returncode == 0

    def sql(self, statement: str, database: str | None = None) -> str:
        command = [MYSQL_CLI, "--no-defaults", f"--socket={self.socket}", "-u", "root",
                   "--batch", "--skip-column-names"]
        if database:
            command += ["--database", database]
        command += ["-e", statement]
        done = subprocess.run(command, capture_output=True, text=True, timeout=600)
        if done.returncode != 0:
            raise RuntimeError(f"mysql client failed: {done.stderr.strip()}")
        return done.stdout

    def rows(self, statement: str, database: str = "claimflow") -> list[list[str]]:
        return [line.split("\t") for line in self.sql(statement, database).splitlines() if line]

    def create_schema(self) -> None:
        self.sql("CREATE DATABASE claimflow CHARACTER SET utf8mb4; "
                 "CREATE USER 'claimflow'@'%' IDENTIFIED BY 'claimflow'; "
                 "GRANT ALL PRIVILEGES ON claimflow.* TO 'claimflow'@'%'; "
                 "FLUSH PRIVILEGES;")

    def settings(self) -> dict:
        row = self.rows("SELECT @@version, @@innodb_flush_log_at_trx_commit, @@sync_binlog, "
                        "@@innodb_buffer_pool_size, @@max_connections, @@log_bin, @@port",
                        database="mysql")[0]
        return {"version": row[0], "innodb_flush_log_at_trx_commit": int(row[1]),
                "sync_binlog": int(row[2]), "innodb_buffer_pool_size": int(row[3]),
                "max_connections": int(row[4]), "log_bin": row[5], "port": int(row[6]),
                "command": self.command}

    def stop(self) -> None:
        if self.process and self.process.poll() is None:
            self.process.send_signal(signal.SIGTERM)
            try:
                self.process.wait(timeout=120)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=60)

    def kill9(self) -> int | None:
        """Abruptly stop only the server process this object started."""
        if self.process and self.process.poll() is None:
            pid = self.process.pid
            os.kill(pid, signal.SIGKILL)
            self.process.wait(timeout=60)
            return pid
        return None


class ApiInstance:
    """One API process from the shared JAR; killed with SIGKILL on the pre-registered schedule."""

    def __init__(self, name: str, port: int, jar: Path, java: str, logs: Path, jdbc: str,
                 extra_args: tuple[str, ...] = ()) -> None:
        self.name = name
        self.port = port
        self.jar = jar
        self.java = java
        self.log = logs / f"api_{name}.log"
        self.jdbc = jdbc
        self.extra_args = extra_args
        self.process: subprocess.Popen | None = None

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def start(self) -> None:
        env = os.environ.copy()
        env.update({"SPRING_PROFILES_ACTIVE": "mysql", "SERVER_ADDRESS": "127.0.0.1",
                    "SERVER_PORT": str(self.port), "CLAIMFLOW_JDBC_URL": self.jdbc,
                    "CLAIMFLOW_DB_USER": "claimflow", "CLAIMFLOW_DB_PASSWORD": "claimflow",
                    "CLAIMFLOW_JWT_SECRET": JWT_SECRET,
                    "CLAIMFLOW_DEMO_PASSWORD": DEMO_PASSWORD})
        handle = self.log.open("ab")
        self.process = subprocess.Popen([self.java, API_HEAP, "-jar", str(self.jar),
                                         *self.extra_args], env=env,
                                        stdout=handle, stderr=subprocess.STDOUT,
                                        start_new_session=True)

    def kill9(self) -> int | None:
        if self.process and self.process.poll() is None:
            pid = self.process.pid
            os.kill(pid, signal.SIGKILL)
            self.process.wait(timeout=60)
            return pid
        return None

    def healthy(self) -> bool:
        try:
            request = urllib.request.Request(self.base + "/actuator/health")
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status == 200 and json.loads(response.read())["status"] == "UP"
        except Exception:
            return False

    def wait_healthy(self, timeout: float) -> tuple[bool, float]:
        started = time.monotonic()
        while time.monotonic() - started < timeout:
            if self.healthy():
                return True, time.monotonic() - started
            time.sleep(0.5)
        return False, time.monotonic() - started

    def has_oom(self) -> bool:
        return self.log.exists() and b"OutOfMemoryError" in self.log.read_bytes()


class Run:
    """Drives the pre-registered load, kill schedule and verification."""

    def __init__(self, args: argparse.Namespace, project: Path, mysql: MysqlServer,
                 instances: dict[str, ApiInstance], recorder: Recorder) -> None:
        self.args = args
        self.scored = not args.shakedown
        self.project = project
        self.mysql = mysql
        self.instances = instances
        self.rec = recorder
        self.nonce = uuid.uuid4().hex[:10]
        self.load_seconds = LOAD_SECONDS if self.scored else SHAKEDOWN_LOAD_SECONDS
        self.drain_seconds = DRAIN_SECONDS if self.scored else SHAKEDOWN_DRAIN_SECONDS
        self.t0 = 0.0
        self.serial = itertools.count(1)
        self.serial_lock = threading.Lock()
        self.review_queue: queue.Queue = queue.Queue()
        self.stop_submissions = threading.Event()
        self.aborted = threading.Event()
        self.burst_done = threading.Event()
        self.believed_up = {"A": True, "B": True}
        self.up_lock = threading.Lock()
        self.tokens: dict[str, str] = {}
        self.hard_deadline = 0.0

    # ---------------------------------------------------------------- timing and bookkeeping
    def t(self) -> float:
        return time.monotonic() - self.t0

    def next_serial(self) -> int:
        with self.serial_lock:
            return next(self.serial)

    def sleep_until(self, mark: float) -> bool:
        """Waits until `mark` seconds into the load. False when the run was aborted."""
        remaining = mark - self.t()
        if remaining > 0:
            self.aborted.wait(remaining)
        return not self.aborted.is_set()

    def up_instances(self) -> list[str]:
        with self.up_lock:
            return [name for name, up in self.believed_up.items() if up]

    def set_up(self, name: str, up: bool) -> None:
        with self.up_lock:
            self.believed_up[name] = up

    # ------------------------------------------------------------------------------- HTTP
    def _once(self, phase: str, name: str, path: str, method: str, body: dict | None,
              token: str | None, key: str | None, timeout: float) -> tuple[int, object, dict]:
        instance = self.instances[name]
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(
            instance.base + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers=headers, method=method)
        started = time.perf_counter()
        at = self.t()
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read()
                status = response.status
                payload = json.loads(raw) if raw else {}
        except urllib.error.HTTPError as error:
            raw = error.read()
            status = error.code
            try:
                payload = json.loads(raw) if raw else {}
            except ValueError:
                payload = {"raw": raw[:200].decode(errors="replace")}
        except Exception as error:                      # transport failure: the reply is lost
            status, payload = 0, {"transport_error": repr(error)[:200]}
        record = {"t": round(at, 3), "phase": phase, "instance": name, "method": method,
                  "path": path, "status": status, "seconds": round(time.perf_counter() - started, 4)}
        if key:
            record["key"] = key
        if phase in STATE_CHANGING_PHASES:
            record["state_changing"] = True
        self.rec.add_http(record)
        return status, payload, record

    def call(self, phase: str, name: str, path: str, method: str = "GET", body: dict | None = None,
             token: str | None = None, key: str | None = None, timeout: float = 30.0,
             retry: bool = True) -> tuple[int, object]:
        """The protocol's retry rule: a transport failure or 5xx is retried with the same request
        and the same Idempotency-Key, once a second, preferring the other instance, for up to 90
        seconds. A 409 on a retry is accepted as an answer."""
        deadline = time.monotonic() + RETRY_WINDOW_S
        target = name
        attempts = 0
        while True:
            status, payload, record = self._once(phase, target, path, method, body, token, key,
                                                 timeout)
            if status != 0 and status < 500:
                if attempts and key:
                    with self.rec.lock:
                        self.rec.retried_submit_keys.add(key)
                return status, payload
            attempts += 1
            if not retry or time.monotonic() >= deadline or self.aborted.is_set():
                record["gave_up"] = True
                record["retry_attempts"] = attempts
                return status, payload
            target = "B" if target == "A" else "A"
            time.sleep(RETRY_GAP_S)

    def login(self, user: str) -> str:
        status, payload = self.call("login", "A", "/api/auth/token", "POST",
                                    {"username": user, "password": DEMO_PASSWORD})
        if status != 200:
            raise RuntimeError(f"login failed for {user}: {status} {payload}")
        return payload["access_token"]

    # ------------------------------------------------------------------------- submissions
    def receipt_text(self, merchant: str, cents: int) -> str:
        return f"Merchant: {merchant}\nDate: {RECEIPT_DATE}\nTotal: SGD {cents // 100}.{cents % 100:02d}"

    def unparseable_text(self, serial: int) -> str:
        return (f"receipt image too smudged to read {self.nonce} {serial}\n"
                f"no usable merchant line, no usable date, no usable total\n")

    def pick_kind(self, rng: random.Random) -> str:
        roll = rng.random()
        cumulative = 0.0
        for kind, share in MIX:
            cumulative += share
            if roll < cumulative:
                return kind
        return MIX[-1][0]

    def submit_one(self, kind: str, rng: random.Random, phase: str) -> None:
        serial = self.next_serial()
        cents = rng.randint(AMOUNT_MIN_CENTS, AMOUNT_MAX_CENTS)
        key = f"cl-{self.nonce}-{serial}"
        if kind == "async_unparseable":
            text = self.unparseable_text(serial)
        else:
            text = self.receipt_text(f"Crash Cafe {self.nonce} {serial}", cents)
        path = "/api/claims" if kind == "sync_valid" else "/api/claims/async"
        target = rng.choice(("A", "B"))
        status, payload = self.call(phase, target, path, "POST",
                                    {"category": "meal", "amountCents": cents, "receiptText": text},
                                    self.tokens["employee"], key=key)
        if not (200 <= status < 300) or not isinstance(payload, dict) or "id" not in payload:
            return
        claim_id = payload["id"]
        with self.rec.lock:
            self.rec.acks.append({"key": key, "claim_id": claim_id, "kind": kind,
                                  "t": round(self.t(), 3)})
            if kind == "sync_valid":
                self.rec.sync_claims.append(claim_id)
            else:
                self.rec.async_claims.append(claim_id)
            if kind == "async_unparseable":
                self.rec.unparseable_claims.append(claim_id)
            else:
                self.rec.valid_receipt_claims.append(claim_id)
        self.review_queue.put({"claim": claim_id, "kind": kind, "cents": cents, "serial": serial})

    def submitter(self, index: int) -> None:
        rng = random.Random(f"{self.nonce}-submitter-{index}")
        while not self.stop_submissions.is_set() and not self.aborted.is_set():
            self.submit_one(self.pick_kind(rng), rng, "submit")
            self.stop_submissions.wait(SUBMIT_GAP_S)

    def burst(self) -> None:
        """Burst of 120 extra async valid claims from 20 threads at t=360."""
        per_thread = BURST_CLAIMS // BURST_THREADS
        def worker(index: int) -> None:
            rng = random.Random(f"{self.nonce}-burst-{index}")
            for _ in range(per_thread):
                if self.aborted.is_set():
                    return
                self.submit_one("async_valid", rng, "burst")
        threads = [threading.Thread(target=worker, args=(i,), name=f"burst-{i}", daemon=True)
                   for i in range(BURST_THREADS)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=max(1.0, DOUBLE_KILL_LATEST - self.t()))
        self.burst_done.set()

    # ----------------------------------------------------------------------------- reviewing
    def claim_state(self, claim_id: str, phase: str = "poll") -> str | None:
        status, payload = self.call(phase, random.choice(self.up_instances() or ["A"]),
                                    f"/api/claims/{claim_id}", token=self.tokens["manager"])
        if 200 <= status < 300 and isinstance(payload, dict):
            return payload.get("state")
        return None

    def await_state(self, claim_id: str, leave: set[str], deadline: float) -> str | None:
        state = None
        while time.monotonic() < deadline and not self.aborted.is_set():
            state = self.claim_state(claim_id)
            if state is not None and state not in leave:
                return state
            time.sleep(0.5)
        return state

    def correct(self, item: dict) -> None:
        """A manager correction for a receipt that could not be parsed."""
        cents = item["cents"]
        text = self.receipt_text(f"Corrected Cafe {self.nonce} {item['serial']}", cents)
        status, payload = self.call("correction", random.choice(self.up_instances() or ["A"]),
                                    f"/api/claims/{item['claim']}/receipt-check", "POST",
                                    {"receiptText": text}, self.tokens["manager"])
        with self.rec.lock:
            self.rec.corrections.append({"claim_id": item["claim"], "status": status})

    def race(self, phase: str, claim_id: str, bodies: list[dict | None], token: str,
             path: str) -> list[tuple[int, object]]:
        """Releases two requests together, one to each instance when both are up."""
        up = self.up_instances()
        targets = [up[0], up[1]] if len(up) >= 2 else [(up or ["A"])[0]] * len(bodies)
        gate = threading.Barrier(len(bodies))
        results: list[tuple[int, object]] = [(0, {})] * len(bodies)

        def fire(index: int) -> None:
            try:
                gate.wait(timeout=30)
            except threading.BrokenBarrierError:
                pass
            results[index] = self.call(phase, targets[index], path, "POST", bodies[index], token)

        threads = [threading.Thread(target=fire, args=(i,), daemon=True)
                   for i in range(len(bodies))]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=RETRY_WINDOW_S + 60)
        return results

    def decision_race(self, claim_id: str, rng: random.Random) -> str | None:
        approvals = [True, True] if rng.random() < 0.80 else [True, False]
        rng.shuffle(approvals)
        bodies = [{"approve": approve, "managerNote": True} for approve in approvals]
        results = self.race("decision", claim_id, bodies, self.tokens["manager"],
                            f"/api/claims/{claim_id}/decision")
        state = None
        with self.rec.lock:
            for approve, (status, payload) in zip(approvals, results):
                self.rec.decisions.append({"claim_id": claim_id, "approve": approve,
                                           "status": status})
                if 200 <= status < 300 and isinstance(payload, dict):
                    state = payload.get("state")
        return state or self.claim_state(claim_id, "verify")

    def payment_race(self, claim_id: str) -> None:
        results = self.race("payment", claim_id, [None, None], self.tokens["finance"],
                            f"/api/claims/{claim_id}/paid")
        with self.rec.lock:
            for status, _ in results:
                self.rec.payments.append({"claim_id": claim_id, "status": status})

    def handle(self, item: dict, rng: random.Random) -> None:
        claim_id = item["claim"]
        deadline = min(time.monotonic() + 150.0, self.hard_deadline)
        state = self.await_state(claim_id, {"SUBMITTED"}, deadline)
        if state == "NEEDS_HUMAN_CHECK":
            self.correct(item)
            state = self.await_state(claim_id, {"SUBMITTED", "NEEDS_HUMAN_CHECK"}, deadline)
        if state != "IN_REVIEW":
            self.rec.note(f"claim {claim_id} was {state} when its reviewer stopped waiting",
                          self.t())
            return
        state = self.decision_race(claim_id, rng)
        if state == "APPROVED":
            self.payment_race(claim_id)

    def reviewer(self, index: int) -> None:
        rng = random.Random(f"{self.nonce}-reviewer-{index}")
        while not self.aborted.is_set():
            try:
                item = self.review_queue.get(timeout=1.0)
            except queue.Empty:
                if self.stop_submissions.is_set():
                    return
                continue
            try:
                self.handle(item, rng)
            finally:
                self.review_queue.task_done()
            if time.monotonic() > self.hard_deadline:
                return

    # ------------------------------------------------------------------------------- console
    def console(self) -> None:
        pages = [("/api/claims/mine", "employee"), ("/api/claims/queue", "manager"),
                 ("/api/claims/receipt-check", "manager"), ("/api/claims/approved", "finance"),
                 ("/api/claims/stats", "finance")]
        while not self.stop_submissions.is_set() and not self.aborted.is_set():
            target = random.choice(self.up_instances() or ["A"])
            for path, role in pages:
                if self.aborted.is_set():
                    return
                self.call("console", target, path, token=self.tokens[role], timeout=60.0)
            self.stop_submissions.wait(CONSOLE_GAP_S)

    # ------------------------------------------------------------------------- kill schedule
    def kill(self, names: list[str]) -> None:
        for name in names:
            self.set_up(name, False)
        for name in names:
            at = self.t()
            pid = self.instances[name].kill9()
            with self.rec.lock:
                self.rec.kills.append({"instance": name, "t": round(at, 3), "pid": pid,
                                       "signal": "SIGKILL"})
                self.rec.down_windows.append({"instance": name, "from": round(at, 3), "to": None})

    def restart(self, names: list[str]) -> None:
        for name in names:
            self.instances[name].start()
        for name in names:
            healthy, seconds = self.instances[name].wait_healthy(HEALTHY_WITHIN_S)
            at = self.t()
            with self.rec.lock:
                self.rec.restarts.append({"instance": name, "t": round(at, 3),
                                          "seconds": seconds, "healthy": healthy})
                for window in self.rec.down_windows:
                    if window["instance"] == name and window["to"] is None:
                        window["to"] = round(at, 3)
            if healthy:
                self.set_up(name, True)

    def read_job_snapshot(self) -> None:
        """Immediately after the double kill: the PENDING and RETRY job IDs, read from MySQL."""
        try:
            rows = self.mysql.rows("SELECT claim_id FROM extraction_jobs "
                                   "WHERE status IN ('PENDING','RETRY')")
        except Exception as error:
            self.rec.note(f"job snapshot query failed: {error!r}", self.t())
            return
        with self.rec.lock:
            self.rec.job_snapshot = [row[0] for row in rows]
        self.rec.note(f"job snapshot at the double kill: {len(rows)} PENDING or RETRY jobs",
                      self.t())

    def schedule(self) -> None:
        if not self.scored:
            if self.sleep_until(SHAKEDOWN_KILL_AT):
                self.kill(["A"])
            if self.sleep_until(SHAKEDOWN_RESTART_AT):
                self.restart(["A"])
            return
        if self.sleep_until(KILL_A_AT):
            self.kill(["A"])
        if self.sleep_until(RESTART_A_AT):
            self.restart(["A"])
        if not self.sleep_until(BURST_AT):
            return
        threading.Thread(target=self.burst, name="burst", daemon=True).start()
        while self.t() < DOUBLE_KILL_LATEST and not self.burst_done.is_set():
            if self.aborted.is_set():
                return
            time.sleep(0.05)
        self.rec.note("double kill: every burst request answered"
                      if self.burst_done.is_set() else
                      f"double kill at the {DOUBLE_KILL_LATEST}s deadline", self.t())
        self.kill(["A", "B"])
        self.read_job_snapshot()
        if self.sleep_until(RESTART_BOTH_AT):
            self.restart(["A", "B"])

    # ------------------------------------------------------------------------------ watchdog
    def watchdog(self) -> None:
        while not self.aborted.is_set():
            free = free_bytes()
            with self.rec.lock:
                self.rec.disk_samples.append({"t": round(self.t(), 1), "free_gib": gib(free)})
            if free <= DISK_ABORT_BYTES:
                with self.rec.lock:
                    self.rec.disk_abort = {"t": round(self.t(), 1), "free_gib": gib(free),
                                           "floor_gib": gib(DISK_ABORT_BYTES)}
                self.rec.note(f"disk watchdog aborting: {gib(free)} GiB free", self.t())
                self.stop_submissions.set()
                self.aborted.set()
                return
            time.sleep(5.0)

    # ---------------------------------------------------------------------------- drain, read
    def drain(self) -> dict:
        started = time.monotonic()
        jobs_clear_at = None
        while time.monotonic() - started < self.drain_seconds and not self.aborted.is_set():
            queued = self.review_queue.unfinished_tasks
            try:
                pending = int(self.mysql.rows("SELECT COUNT(*) FROM extraction_jobs "
                                              "WHERE status IN ('PENDING','RETRY')")[0][0])
            except Exception:
                pending = -1
            if queued == 0 and pending == 0:
                jobs_clear_at = time.monotonic() - started
                break
            time.sleep(2.0)
        return {"seconds_used": round(time.monotonic() - started, 1),
                "budget_seconds": self.drain_seconds,
                "drained_before_the_budget_ran_out": jobs_clear_at is not None,
                "review_items_left_unfinished": self.review_queue.unfinished_tasks}

    def read_database(self) -> dict:
        claims = [{"id": row[0], "state": row[1], "receipt_hash": row[2] or None,
                   "idempotency_key": row[3]}
                  for row in self.mysql.rows(
                      "SELECT id, state, IFNULL(receipt_hash,''), idempotency_key FROM claims")]
        audit = [{"claim_id": row[0], "action": row[1]}
                 for row in self.mysql.rows("SELECT claim_id, action FROM audit_events")]
        jobs = [{"claim_id": row[0], "status": row[1], "attempts": int(row[2])}
                for row in self.mysql.rows("SELECT claim_id, status, attempts FROM extraction_jobs")]
        failed = int(self.mysql.rows("SELECT COUNT(*) FROM flyway_schema_history "
                                     "WHERE success = 0")[0][0])
        migrations = int(self.mysql.rows("SELECT COUNT(*) FROM flyway_schema_history")[0][0])
        return {"claims": claims, "audit": audit, "jobs": jobs,
                "flyway_unsuccessful": failed, "flyway_rows": migrations}

    # ----------------------------------------------------------------------------------- run
    def go(self) -> dict:
        for role in ("employee", "manager", "finance"):
            self.tokens[role] = self.login(role)
        self.t0 = time.monotonic()
        self.hard_deadline = self.t0 + self.load_seconds + self.drain_seconds
        threads = [threading.Thread(target=self.watchdog, name="watchdog", daemon=True),
                   threading.Thread(target=self.schedule, name="schedule", daemon=True),
                   threading.Thread(target=self.console, name="console", daemon=True)]
        threads += [threading.Thread(target=self.submitter, args=(i,), name=f"submit-{i}",
                                     daemon=True) for i in range(SUBMITTER_THREADS)]
        reviewers = [threading.Thread(target=self.reviewer, args=(i,), name=f"review-{i}",
                                      daemon=True) for i in range(REVIEWER_THREADS)]
        for thread in threads + reviewers:
            thread.start()
        while self.t() < self.load_seconds and not self.aborted.is_set():
            time.sleep(0.25)
        self.stop_submissions.set()
        self.rec.note("new submissions stopped", self.t())
        drain = self.drain()
        for thread in reviewers:
            thread.join(timeout=max(1.0, self.hard_deadline - time.monotonic()))
        self.rec.note("drain finished", self.t())
        with self.rec.lock:
            for window in self.rec.down_windows:
                if window["to"] is None:
                    window["to"] = round(self.t(), 3)
            for name, instance in self.instances.items():
                self.rec.final_health[name] = instance.healthy()
                self.rec.oom[name] = instance.has_oom()
        return drain


# --------------------------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------------------------

def phase_latency(rec: dict) -> dict:
    out: dict[str, dict] = {}
    for phase in sorted({h["phase"] for h in rec["http"]}):
        seconds = sorted(h["seconds"] for h in rec["http"]
                         if h["phase"] == phase and 200 <= h["status"] < 300)
        if not seconds:
            out[phase] = {"successful_requests": 0}
            continue
        out[phase] = {"successful_requests": len(seconds),
                      "p50_ms": round(statistics.median(seconds) * 1000, 1),
                      "p95_ms": round(seconds[max(0, int(len(seconds) * 0.95) - 1)] * 1000, 1),
                      "max_ms": round(seconds[-1] * 1000, 1)}
    return out


def phase_status_counts(rec: dict) -> dict:
    out: dict[str, dict] = {}
    for record in rec["http"]:
        out.setdefault(record["phase"], Counter())[record["status"]] += 1
    return {phase: dict(sorted(counts.items())) for phase, counts in sorted(out.items())}


def throughput_buckets(rec: dict, width: float = 30.0) -> dict:
    buckets: Counter = Counter()
    for record in rec["http"]:
        if record.get("state_changing") and 200 <= record["status"] < 300:
            buckets[int(record["t"] // width) * int(width)] += 1
    return {str(start): buckets[start] for start in sorted(buckets)}


def notable_requests(rec: dict, cap: int = 2000) -> dict:
    notable = [h for h in rec["http"]
               if h["phase"] != "health" and (h["status"] == 0 or h["status"] >= 400
                                              or h.get("gave_up"))]
    return {"count": len(notable), "listed": min(len(notable), cap), "records": notable[:cap]}


def summarise(rec: dict, db: dict) -> dict:
    kinds = Counter(ack["kind"] for ack in rec["acks"])
    return {"claims_acknowledged": len({a["claim_id"] for a in rec["acks"]}),
            "claims_acknowledged_by_kind": dict(sorted(kinds.items())),
            "claims_in_database": len(db["claims"]),
            "audit_events_in_database": len(db["audit"]),
            "extraction_jobs_in_database": len(db["jobs"]),
            "flyway_rows": db["flyway_rows"],
            "requests_total": len(rec["http"]),
            "requests_by_phase_and_status": phase_status_counts(rec),
            "state_changing_2xx_per_30s": throughput_buckets(rec),
            "corrections": len(rec["corrections"]),
            "kills": rec["kills"], "restarts": [
                {"instance": r["instance"], "t": r["t"], "seconds_to_healthy": round(r["seconds"], 1),
                 "healthy": r["healthy"]} for r in rec["restarts"]],
            "down_windows": rec["down_windows"],
            "job_snapshot_at_double_kill": len(rec["job_snapshot"]),
            "notes": rec["notes"],
            "latency_descriptive_only": phase_latency(rec)}


def print_summary(report: dict) -> None:
    print(json.dumps({"label": report["label"], "scored": report["scored"],
                      "verdict": report["verdict"],
                      "invalid_reasons": report["invalid_reasons"],
                      "criteria": report["criteria"],
                      "claims_acknowledged": report["summary"]["claims_acknowledged"],
                      "claims_in_database": report["summary"]["claims_in_database"],
                      "kills": len(report["summary"]["kills"]),
                      "restarts": report["summary"]["restarts"],
                      "job_snapshot_at_double_kill":
                          report["summary"]["job_snapshot_at_double_kill"],
                      "disk": report["disk"], "evidence": report["evidence_path"]}, indent=2))


# --------------------------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------------------------

def git_commit(project: Path) -> str:
    done = subprocess.run(["git", "-C", str(project), "rev-parse", "HEAD"],
                          capture_output=True, text=True)
    return done.stdout.strip() if done.returncode == 0 else "unknown"


def java_binary(project: Path) -> str:
    toolchain = project / ".." / ".." / ".toolchains" / "jdk-21.0.12.1+1" / "Contents" / "Home" \
        / "bin" / "java"
    if toolchain.exists():
        return str(toolchain.resolve())
    if os.environ.get("JAVA_HOME"):
        return os.environ["JAVA_HOME"] + "/bin/java"
    return "java"


def preflight(project: Path) -> dict:
    problems = []
    for binary in (MYSQLD, MYSQL_CLI):
        if not Path(binary).exists():
            problems.append(f"missing {binary}")
    jar = project / "target" / JAR_NAME
    if not jar.exists():
        problems.append(f"missing {jar}; build it with ./mvnw -DskipTests package")
    for port in (MYSQL_PORT, PORT_A, PORT_B):
        if not port_free(port):
            problems.append(f"port {port} is already in use")
    free = free_bytes()
    if free <= DISK_ABORT_BYTES:
        problems.append(f"free disk is already {gib(free)} GiB, at or below the "
                        f"{gib(DISK_ABORT_BYTES)} GiB abort floor")
    if problems:
        raise SystemExit("pre-flight failed:\n  " + "\n  ".join(problems))
    return {"free_disk_gib_before": gib(free), "jar": str(jar), "jar_bytes": jar.stat().st_size}


def teardown(mysql: MysqlServer, instances: dict[str, ApiInstance], crashdb: Path,
             keep: bool) -> dict:
    for instance in instances.values():
        try:
            instance.kill9()
        except Exception:
            pass
    mysql.stop()
    deleted = False
    if not keep and crashdb.exists():
        shutil.rmtree(crashdb)
        deleted = True
    listening = [port for port in (MYSQL_PORT, PORT_A, PORT_B) if not port_free(port)]
    mysqld_left = subprocess.run(["pgrep", "-f", str(crashdb)], capture_output=True, text=True)
    return {"datadir_deleted": deleted, "datadir_kept": keep and crashdb.exists(),
            "ports_still_listening": listening,
            "processes_still_matching_the_datadir":
                len([line for line in mysqld_left.stdout.split() if line]),
            "free_disk_gib_after": gib(free_bytes())}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", required=True,
                        help="before or after for a scored run; anything for a shake-down")
    parser.add_argument("--shakedown", action="store_true",
                        help="short unscored debugging run: 100 s of load, one kill")
    parser.add_argument("--keep-datadir", action="store_true",
                        help="leave target/crashdb in place (debugging only)")
    parser.add_argument("--out", default=None, help="override the output path")
    args = parser.parse_args(argv)

    project = Path(__file__).resolve().parent.parent
    scored = not args.shakedown
    crashdb = project / "target" / "crashdb"
    logs = project / "target" / "crashdb_logs"
    logs.mkdir(parents=True, exist_ok=True)
    for stale in logs.glob("api_*.log"):
        stale.unlink()
    disk = preflight(project)
    jdbc = (f"jdbc:mysql://127.0.0.1:{MYSQL_PORT}/claimflow"
            "?allowPublicKeyRetrieval=true&useSSL=false")
    mysql = MysqlServer(crashdb)
    recorder = Recorder()
    java = java_binary(project)
    instances = {
        "A": ApiInstance("A", PORT_A, project / "target" / JAR_NAME, java, logs, jdbc),
        "B": ApiInstance("B", PORT_B, project / "target" / JAR_NAME, java, logs, jdbc),
    }
    started_at = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    report: dict = {"label": args.label, "scored": scored, "shakedown": args.shakedown,
                    "protocol": "docs/CRASH_LOAD_PROTOCOL.md, frozen at commit f5d9aa3",
                    "protocol_unchanged_by_this_script": True,
                    "git_commit": git_commit(project), "started": started_at,
                    "evidence_filename_date_fixed_by_the_protocol": EVIDENCE_DATE,
                    "java": java, "jar_bytes": disk["jar_bytes"],
                    "all_claims_and_receipts_are_synthetic": True}
    drain: dict = {}
    try:
        mysql.initialise()
        mysql.start()
        mysql.create_schema()
        report["mysql"] = mysql.settings()
        startup = {}
        for name in ("A", "B"):
            instances[name].start()
            healthy, seconds = instances[name].wait_healthy(180)
            startup[name] = {"healthy": healthy, "seconds": round(seconds, 1)}
            if not healthy:
                raise RuntimeError(f"instance {name} never became healthy: "
                                   f"{instances[name].log.read_text()[-3000:]}")
        report["initial_startup_not_scored"] = startup
        run = Run(args, project, mysql, instances, recorder)
        report["load_shape"] = {
            "load_seconds": run.load_seconds, "submitter_threads": SUBMITTER_THREADS,
            "submit_gap_seconds": SUBMIT_GAP_S, "reviewer_threads": REVIEWER_THREADS,
            "console_gap_seconds": CONSOLE_GAP_S, "mix": {k: v for k, v in MIX},
            "burst_claims": BURST_CLAIMS if scored else 0,
            "burst_threads": BURST_THREADS if scored else 0,
            "retry_window_seconds": RETRY_WINDOW_S, "drain_seconds": run.drain_seconds,
            "kill_schedule_seconds": ({"kill_A": KILL_A_AT, "restart_A": RESTART_A_AT,
                                       "burst": BURST_AT,
                                       "double_kill_latest": DOUBLE_KILL_LATEST,
                                       "restart_both": RESTART_BOTH_AT, "stop": LOAD_SECONDS}
                                      if scored else
                                      {"kill_A": SHAKEDOWN_KILL_AT,
                                       "restart_A": SHAKEDOWN_RESTART_AT,
                                       "stop": SHAKEDOWN_LOAD_SECONDS}),
            "nonce": run.nonce}
        drain = run.go()
        rec = recorder.snapshot()
        db = run.read_database()
    except BaseException as failure:
        # Never leave a server, an API process or the data directory behind.
        down = teardown(mysql, instances, crashdb, args.keep_datadir)
        report.update({"verdict": "invalid", "invalid_reasons": [f"the run crashed: {failure!r}"],
                       "criteria": {}, "teardown": down,
                       "disk": {"free_gib_before": disk["free_disk_gib_before"],
                                "free_gib_after": down["free_disk_gib_after"]},
                       "partial_records": recorder.snapshot()["notes"]})
        broken = project / "target" / f"crash_load_{args.label}_crashed.json"
        broken.write_text(json.dumps(report, indent=2, default=str) + "\n")
        print(f"run crashed, nothing scored; detail in {broken}", file=sys.stderr)
        raise
    report["drain"] = drain
    report.update(evaluate(db, rec, scored=scored))
    report["summary"] = summarise(rec, db)
    report["notable_requests"] = notable_requests(rec)
    report["request_records_note"] = ("Ordinary 2xx requests are aggregated in summary, not "
                                      "listed. Every transport failure, 4xx, 5xx and give-up is "
                                      "listed in notable_requests. The full per-request log is "
                                      "written to target/ and is not committed.")
    report["disk_samples_during_the_run"] = rec["disk_samples"]
    report["finished"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    down = teardown(mysql, instances, crashdb, args.keep_datadir)
    report["disk"] = {"free_gib_before": disk["free_disk_gib_before"],
                      "free_gib_after": down["free_disk_gib_after"],
                      "lowest_free_gib_during_the_run":
                          min((s["free_gib"] for s in rec["disk_samples"]),
                              default=disk["free_disk_gib_before"]),
                      "abort_floor_gib": gib(DISK_ABORT_BYTES)}
    report["teardown"] = down
    report["limits"] = ("One laptop: load generator, both API processes and the database on the "
                        "same machine over loopback, at no more than about 6 new claims per "
                        "second. Three process kills at chosen times. The database is not "
                        "killed. Not a capacity figure and not a proof that every interleaving "
                        "is safe.")

    if args.out:
        out = Path(args.out)
    elif scored:
        out = project / "evidence" / "crash_load" / f"{args.label}_{EVIDENCE_DATE}.json"
    else:
        out = project / "target" / f"crash_load_shakedown_{args.label}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    report["evidence_path"] = str(out.relative_to(project))
    out.write_text(json.dumps(report, indent=2, sort_keys=False) + "\n")
    raw = project / "target" / f"crash_load_{args.label}_raw.json"
    raw.write_text(json.dumps({"http": rec["http"], "acks": rec["acks"],
                               "decisions": rec["decisions"], "payments": rec["payments"],
                               "corrections": rec["corrections"],
                               "job_snapshot": rec["job_snapshot"]}, indent=1) + "\n")
    print_summary(report)
    return exit_code(report["verdict"])


if __name__ == "__main__":
    raise SystemExit(main())
