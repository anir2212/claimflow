"""One-shot, isolated MySQL SIGKILL/restart check from docs/DB_CRASH_PROTOCOL.md."""

from __future__ import annotations

from collections import Counter
import datetime as dt
import hashlib
import json
from pathlib import Path
import shutil
import time
import urllib.error
import urllib.request
import uuid

import crash_load_check as base


PROJECT = Path(__file__).resolve().parent.parent
PROTOCOL = PROJECT / "docs" / "DB_CRASH_PROTOCOL.md"
EVIDENCE = PROJECT / "evidence" / "db_crash" / "first_2026-11-11.json"
DATADIR = PROJECT / "target" / "db_crash_20261111"
MYSQL_PORT = 13317
API_PORT = 18083
CLAIMS = 20
DISK_FLOOR = int(8.1 * 1024**3)


class EvidenceReadError(RuntimeError):
    """The checker could not obtain the SQL rows its verdict requires."""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def http(base_url: str, path: str, *, method: str = "GET", body: dict | None = None,
         token: str | None = None, key: str | None = None) -> tuple[int, dict]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if key:
        headers["Idempotency-Key"] = key
    request = urllib.request.Request(base_url + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, json.loads(raw) if raw else {}
        except ValueError:
            return error.code, {"error": "non-JSON HTTP response"}
    except (OSError, TimeoutError) as error:
        return 0, {"transport_error": type(error).__name__}


def login(api: base.ApiInstance) -> str:
    status, body = http(api.base, "/api/auth/token", method="POST",
                        body={"username": "employee", "password": base.DEMO_PASSWORD})
    if status != 200 or "access_token" not in body:
        raise RuntimeError(f"employee login failed: HTTP {status}")
    return body["access_token"]


def snapshot(mysql: base.MysqlServer) -> dict:
    try:
        return {
            "claims": [dict(zip(("id", "state", "key"), row)) for row in mysql.rows(
                "SELECT id, state, idempotency_key FROM claims ORDER BY id")],
            "jobs": [dict(zip(("claim_id", "status", "attempts"), row)) for row in mysql.rows(
                "SELECT claim_id, status, attempts FROM extraction_jobs ORDER BY claim_id")],
            "audit": [dict(zip(("claim_id", "action"), row)) for row in mysql.rows(
                "SELECT claim_id, action FROM audit_events ORDER BY claim_id, action")],
            "flyway_unsuccessful": int(mysql.rows(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")[0][0]),
        }
    except (RuntimeError, IndexError, ValueError) as error:
        raise EvidenceReadError(str(error)) from error


def validate_snapshot(data: dict, expected: set[str], *, final: bool) -> dict:
    """Check exact row sets and chains, including extra rows and orphan records."""
    claim_ids = [row["id"] for row in data["claims"]]
    state = "IN_REVIEW" if final else "SUBMITTED"
    job_state = "DONE" if final else "PENDING"
    wanted_audit = Counter({"SUBMITTED": 1, "EXTRACTED": 1} if final else
                           {"SUBMITTED": 1})
    audits: dict[str, Counter] = {}
    for row in data["audit"]:
        audits.setdefault(row["claim_id"], Counter())[row["action"]] += 1
    problems = []
    if len(claim_ids) != CLAIMS or set(claim_ids) != expected:
        problems.append("claim ID set differs from the 20 acknowledgements")
    if any(row["state"] != state for row in data["claims"]):
        problems.append(f"one or more claims are not {state}")
    if len(data["jobs"]) != CLAIMS or {row["claim_id"] for row in data["jobs"]} != expected:
        problems.append("job ID set differs from the 20 acknowledgements")
    if any(row["status"] != job_state for row in data["jobs"]):
        problems.append(f"one or more jobs are not {job_state}")
    if set(audits) != expected or any(audits.get(claim_id) != wanted_audit
                                      for claim_id in expected):
        problems.append("audit chains differ from the exact expected actions")
    if data["flyway_unsuccessful"]:
        problems.append("unsuccessful Flyway migration row exists")
    return {"ok": not problems, "problems": problems,
            "claims": len(data["claims"]), "jobs": len(data["jobs"]),
            "audit_events": len(data["audit"]), "flyway_unsuccessful":
                data["flyway_unsuccessful"]}


def states(api: base.ApiInstance, token: str, ids: list[str]) -> dict:
    found = {}
    for claim_id in ids:
        status, body = http(api.base, f"/api/claims/{claim_id}", token=token)
        found[claim_id] = {"status": status, "state": body.get("state")}
    return found


def all_states(found: dict, state: str, ids: set[str]) -> bool:
    return set(found) == ids and all(row == {"status": 200, "state": state}
                                     for row in found.values())


def until(predicate, timeout: float) -> float | None:
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        if predicate():
            return round(time.monotonic() - started, 2)
        time.sleep(0.5)
    return None


def preflight(jar: Path, mysql: base.MysqlServer) -> list[str]:
    problems = []
    for path in (Path(base.MYSQLD), Path(base.MYSQL_CLI), jar, PROTOCOL):
        if not path.is_file():
            problems.append(f"missing required file: {path}")
    if DATADIR.exists():
        problems.append(f"disposable data directory already exists: {DATADIR}")
    if mysql.socket.exists():
        problems.append(f"socket path already exists: {mysql.socket}")
    for port in (MYSQL_PORT, API_PORT):
        if not base.port_free(port):
            problems.append(f"port {port} is occupied")
    if base.free_bytes() <= DISK_FLOOR:
        problems.append("free disk is at or below the pre-registered 8.1 GiB floor")
    return problems


def run() -> int:
    if EVIDENCE.exists():
        raise SystemExit(f"refusing to overwrite first scored evidence: {EVIDENCE}")
    jar = PROJECT / "target" / base.JAR_NAME
    mysql = base.MysqlServer(DATADIR, port=MYSQL_PORT)
    report: dict = {"protocol": str(PROTOCOL.relative_to(PROJECT)),
        "protocol_sha256": sha256(PROTOCOL), "project_commit": base.git_commit(PROJECT),
        "started_at": dt.datetime.now().astimezone().isoformat(),
        "jar_sha256": sha256(jar) if jar.is_file() else None,
        "checks": {}, "events": {}, "snapshots": {}, "invalid_reasons": []}
    problems = preflight(jar, mysql)
    if problems:
        report["invalid_reasons"] = problems
        report["verdict"] = "invalid"
        EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
        EVIDENCE.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps({"verdict": "invalid", "reasons": problems}, indent=2))
        return 2
    api: base.ApiInstance | None = None
    ids: list[str] = []
    payloads: list[dict] = []
    scoreable = True
    failed = False
    try:
        mysql.initialise()
        mysql.start()
        mysql.create_schema()
        report["settings_before"] = mysql.settings()
        if any(report["settings_before"][name] != 1 for name in
               ("innodb_flush_log_at_trx_commit", "sync_binlog")):
            report["invalid_reasons"].append("MySQL durability settings were not both 1")
            scoreable = False
            raise RuntimeError("durability settings differ from the protocol")
        api = base.ApiInstance("db-crash", API_PORT, jar, base.java_binary(PROJECT),
            DATADIR, f"jdbc:mysql://127.0.0.1:{MYSQL_PORT}/claimflow",
            extra_args=("--claimflow.extraction.enabled=false",))
        api.start()
        up, seconds = api.wait_healthy(60)
        report["events"]["api_initial_health"] = {"up": up, "seconds": round(seconds, 2)}
        if not up:
            raise RuntimeError("initial API did not become healthy within 60 seconds")
        token = login(api)
        nonce = uuid.uuid4().hex[:12]
        for index in range(CLAIMS):
            key = f"db-crash-{nonce}-{index}"
            body = {"category": "meal", "amountCents": 1850,
                "receiptText": f"Merchant: Crash Check {nonce} {index}\n"
                               "Date: 2026-11-11\nTotal: SGD 18.50"}
            status, response = http(api.base, "/api/claims/async", method="POST",
                                    body=body, token=token, key=key)
            payloads.append({"key": key, "body": body, "status": status,
                             "id": response.get("id")})
            if 200 <= status < 300 and response.get("id"):
                ids.append(response["id"])
        report["submissions"] = payloads
        expected = set(ids)
        if len(ids) != CLAIMS or len(expected) != CLAIMS:
            report["invalid_reasons"].append("not exactly 20 distinct acknowledged claims")
            scoreable = False
            raise RuntimeError("submission set is not scoreable")
        report["snapshots"]["before"] = snapshot(mysql)
        report["events"]["api_states_before"] = states(api, token, ids)
        report["checks"]["D1"] = {
            "ok": validate_snapshot(report["snapshots"]["before"], expected,
                                      final=False)["ok"] and
                  all_states(report["events"]["api_states_before"], "SUBMITTED", expected),
            "sql": validate_snapshot(report["snapshots"]["before"], expected, final=False)}
        if base.free_bytes() <= DISK_FLOOR:
            report["invalid_reasons"].append("free disk reached 8.1 GiB before the kill")
            scoreable = False
            raise RuntimeError("disk floor reached before kill")
        pid = mysql.kill9()
        report["events"]["mysql_kill"] = {"signal": "SIGKILL", "pid": pid,
            "exited": bool(pid and mysql.process and mysql.process.poll() is not None)}
        down_seconds = until(lambda: not api.healthy(), 15)
        report["events"]["api_down_seconds"] = down_seconds
        report["checks"]["D2"] = {"ok": bool(pid) and down_seconds is not None}
        if not pid:
            report["invalid_reasons"].append("isolated MySQL process was not killed")
            scoreable = False
            raise RuntimeError("MySQL process missing at kill")
        # SIGKILL can leave a stale socket file; its owner has exited.
        mysql.socket.unlink(missing_ok=True)
        resumed_at = time.monotonic()
        mysql.start()
        report["events"]["mysql_restart_seconds"] = round(time.monotonic() - resumed_at, 2)
        report["settings_after"] = mysql.settings()
        report["events"]["same_api_up_seconds"] = until(api.healthy, 60)
        report["snapshots"]["after_db_restart"] = snapshot(mysql)
        report["events"]["api_states_after_db_restart"] = states(api, token, ids)
        after_sql = validate_snapshot(report["snapshots"]["after_db_restart"],
                                      expected, final=False)
        durable = all(report["settings_before"][name] == 1 and
                      report["settings_after"][name] == 1
                      for name in ("innodb_flush_log_at_trx_commit", "sync_binlog"))
        if not durable:
            report["invalid_reasons"].append("MySQL durability settings were not both 1")
            scoreable = False
        report["checks"]["D3"] = {"ok": durable and after_sql["ok"] and
            report["events"]["mysql_restart_seconds"] <= 120 and
            report["events"]["same_api_up_seconds"] is not None and
            all_states(report["events"]["api_states_after_db_restart"],
                       "SUBMITTED", expected), "sql": after_sql}
        api.kill9()
        api = base.ApiInstance("db-crash-restarted", API_PORT, jar,
            base.java_binary(PROJECT), DATADIR,
            f"jdbc:mysql://127.0.0.1:{MYSQL_PORT}/claimflow")
        api.start()
        up, seconds = api.wait_healthy(60)
        report["events"]["new_api_health"] = {"up": up, "seconds": round(seconds, 2)}
        if not up:
            raise RuntimeError("new API did not become healthy within 60 seconds")
        token = login(api)
        def jobs_done() -> bool:
            rows = mysql.rows("SELECT COUNT(*) FROM extraction_jobs WHERE status = 'DONE'")
            return int(rows[0][0]) == CLAIMS
        report["events"]["jobs_done_seconds"] = until(jobs_done, 60)
        report["snapshots"]["final"] = snapshot(mysql)
        report["events"]["api_states_final"] = states(api, token, ids)
        final_sql = validate_snapshot(report["snapshots"]["final"], expected, final=True)
        report["checks"]["D4"] = {"ok": report["events"]["jobs_done_seconds"] is not None
            and final_sql["ok"] and
            all_states(report["events"]["api_states_final"], "IN_REVIEW", expected),
            "sql": final_sql}
        first = payloads[0]
        replay_status, replay_body = http(api.base, "/api/claims/async", method="POST",
            body=first["body"], token=token, key=first["key"])
        claim_count = int(mysql.rows("SELECT COUNT(*) FROM claims")[0][0])
        report["events"]["replay"] = {"status": replay_status,
            "returned_id": replay_body.get("id"), "original_id": first["id"],
            "claim_rows_after_replay": claim_count}
        report["checks"]["D5"] = {"ok": 200 <= replay_status < 300 and
            replay_body.get("id") == first["id"] and claim_count == CLAIMS}
    except Exception as error:
        report["error"] = {"type": type(error).__name__, "message": str(error)[:400]}
        if isinstance(error, EvidenceReadError):
            report["invalid_reasons"].append("checker could not read the SQL/audit evidence")
            scoreable = False
        failed = True
    finally:
        if api:
            try:
                api.kill9()
            except Exception as error:
                report["teardown_api_error"] = str(error)[:200]
        try:
            mysql.stop()
        except Exception as error:
            report["teardown_mysql_error"] = str(error)[:200]
        mysql.socket.unlink(missing_ok=True)
        ports_free = all(base.port_free(port) for port in (MYSQL_PORT, API_PORT))
        prior_checks_ok = all(report["checks"].get(name, {}).get("ok", False)
                              for name in ("D1", "D2", "D3", "D4", "D5"))
        if ports_free and prior_checks_ok and scoreable and not failed:
            shutil.rmtree(DATADIR)
        report["checks"]["D6"] = {"ok": ports_free and not DATADIR.exists(),
            "ports_free": ports_free, "datadir_deleted": not DATADIR.exists()}
        report["finished_at"] = dt.datetime.now().astimezone().isoformat()
        if report["invalid_reasons"] or not scoreable:
            report["verdict"] = "invalid"
        elif all(report["checks"].get(name, {}).get("ok", False)
                 for name in ("D1", "D2", "D3", "D4", "D5", "D6")):
            report["verdict"] = "pass"
        else:
            report["verdict"] = "fail"
        EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
        EVIDENCE.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps({"verdict": report["verdict"], "checks": report["checks"],
            "invalid_reasons": report["invalid_reasons"], "error": report.get("error"),
            "evidence": str(EVIDENCE.relative_to(PROJECT))}, indent=2))
    return {"pass": 0, "fail": 1, "invalid": 2}[report["verdict"]]


if __name__ == "__main__":
    raise SystemExit(run())
