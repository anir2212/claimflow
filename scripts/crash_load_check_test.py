"""Unit tests for the crash/load checker's invariant logic, on synthetic rows.

These test the pure criterion functions in `scripts/crash_load_check.py` only: C1-C9 and the
protocol's invalidity rules, each against hand-built `claims`, `audit_events` and
`extraction_jobs` rows and hand-built client records. No server, no database and no load are
started, so this suite says nothing about a real run; it only shows that a real run's rows would
be judged the way docs/CRASH_LOAD_PROTOCOL.md says.

Run with: python3 -m unittest discover -s scripts -p 'crash_load_check_test.py' -v
"""

from __future__ import annotations

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import crash_load_check as c  # noqa: E402


class MysqlSocketPath(unittest.TestCase):
    def test_socket_path_fits_macos_limit_for_long_workspace_path(self):
        root = Path("/Users/project-owner/Desktop/Projects/more_projects/implemented_projects/") \
            / "p06_claimflow/target/crashdb"
        server = c.MysqlServer(root)
        self.assertLessEqual(len(str(server.socket).encode()), 103)
        self.assertNotEqual(server.socket.parent, root)


def claim(cid: str, state: str, key: str | None = None,
          receipt_hash: str | None = None) -> dict:
    return {"id": cid, "state": state, "idempotency_key": key or f"key-{cid}",
            "receipt_hash": receipt_hash}


def audit(cid: str, *actions: str) -> list[dict]:
    return [{"claim_id": cid, "action": action} for action in actions]


def job(cid: str, status: str, attempts: int = 0) -> dict:
    return {"claim_id": cid, "status": status, "attempts": attempts}


def db(claims=(), audit_rows=(), jobs=(), flyway_unsuccessful=0, flyway_rows=6) -> dict:
    return {"claims": list(claims), "audit": list(audit_rows), "jobs": list(jobs),
            "flyway_unsuccessful": flyway_unsuccessful, "flyway_rows": flyway_rows}


def rec(**overrides) -> dict:
    base = {"http": [], "acks": [], "decisions": [], "payments": [], "corrections": [],
            "async_claims": [], "sync_claims": [], "valid_receipt_claims": [],
            "unparseable_claims": [], "retried_submit_keys": set(), "job_snapshot": [],
            "kills": [], "restarts": [], "down_windows": [], "disk_samples": [],
            "disk_abort": None, "notes": [], "oom": {"A": False, "B": False},
            "final_health": {"A": True, "B": True}}
    base.update(overrides)
    return base


def http(t=10.0, phase="submit", instance="A", status=200, **extra) -> dict:
    record = {"t": t, "phase": phase, "instance": instance, "method": "POST", "path": "/api/claims",
              "status": status, "seconds": 0.01}
    if phase in c.STATE_CHANGING_PHASES:
        record["state_changing"] = True
    record.update(extra)
    return record


class AllowedChains(unittest.TestCase):
    def test_in_review_allows_each_of_the_three_prefixes(self):
        chains = [sorted(chain.elements()) for chain in c.allowed_audit_chains("IN_REVIEW")]
        self.assertIn(["SUBMITTED"], chains)
        self.assertIn(["EXTRACTED", "SUBMITTED"], chains)
        self.assertIn(["NEEDS_HUMAN_CHECK", "RECEIPT_CHECKED", "SUBMITTED"], chains)

    def test_paid_requires_approved_and_paid_on_top_of_a_prefix(self):
        for chain in c.allowed_audit_chains("PAID"):
            self.assertEqual(chain["APPROVED"], 1)
            self.assertEqual(chain["PAID"], 1)

    def test_transient_states_have_no_allowed_chain(self):
        self.assertEqual(c.allowed_audit_chains("EXTRACTED"), [])
        self.assertEqual(c.allowed_audit_chains("DRAFT"), [])


class C1NoLostClaim(unittest.TestCase):
    def test_passes_when_every_acknowledged_claim_is_stored(self):
        result = c.check_c1(db([claim("a", "PAID")]),
                            rec(acks=[{"key": "k1", "claim_id": "a", "kind": "sync_valid"}]))
        self.assertTrue(result["ok"])
        self.assertEqual(result["acknowledged_claims"], 1)

    def test_fails_when_an_acknowledged_claim_is_missing(self):
        result = c.check_c1(db([claim("a", "PAID")]),
                            rec(acks=[{"key": "k1", "claim_id": "a", "kind": "sync_valid"},
                                      {"key": "k2", "claim_id": "ghost", "kind": "async_valid"}]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["acknowledged_but_missing"], 1)
        self.assertEqual(result["missing_examples"], ["ghost"])


class C2IdempotentRetries(unittest.TestCase):
    def test_passes_for_one_claim_per_key_and_distinct_hashes(self):
        result = c.check_c2(db([claim("a", "IN_REVIEW", key="k1", receipt_hash="h1"),
                                claim("b", "IN_REVIEW", key="k2", receipt_hash="h2")]),
                            rec(acks=[{"key": "k1", "claim_id": "a", "kind": "async_valid"},
                                      {"key": "k1", "claim_id": "a", "kind": "async_valid"},
                                      {"key": "k2", "claim_id": "b", "kind": "async_valid"}],
                                retried_submit_keys={"k1"}))
        self.assertTrue(result["ok"])
        self.assertEqual(result["submit_keys_retried_after_a_failed_attempt"], 1)
        self.assertEqual(result["claim_rows_for_those_retried_keys"], 1)

    def test_fails_when_one_key_returned_two_different_ids(self):
        result = c.check_c2(db([claim("a", "IN_REVIEW", key="k1"),
                                claim("b", "IN_REVIEW", key="k2")]),
                            rec(acks=[{"key": "k1", "claim_id": "a", "kind": "async_valid"},
                                      {"key": "k1", "claim_id": "b", "kind": "async_valid"}]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["keys_returning_two_different_ids"], 1)

    def test_fails_when_a_key_has_two_claim_rows(self):
        result = c.check_c2(db([claim("a", "IN_REVIEW", key="same"),
                                claim("b", "IN_REVIEW", key="same")]), rec())
        self.assertFalse(result["ok"])
        self.assertEqual(result["keys_with_more_than_one_claim_row"], 1)

    def test_fails_when_two_claims_share_a_receipt_hash(self):
        result = c.check_c2(db([claim("a", "IN_REVIEW", key="k1", receipt_hash="dup"),
                                claim("b", "IN_REVIEW", key="k2", receipt_hash="dup")]), rec())
        self.assertFalse(result["ok"])
        self.assertEqual(result["claims_sharing_a_receipt_hash"], 1)

    def test_ignores_null_receipt_hashes(self):
        result = c.check_c2(db([claim("a", "NEEDS_HUMAN_CHECK", key="k1"),
                                claim("b", "NEEDS_HUMAN_CHECK", key="k2")]), rec())
        self.assertTrue(result["ok"])


class C3OneDecision(unittest.TestCase):
    def test_passes_for_one_2xx_approve_and_a_matching_state(self):
        result = c.check_c3(db([claim("a", "APPROVED")], audit("a", "SUBMITTED", "APPROVED")),
                            rec(decisions=[{"claim_id": "a", "approve": True, "status": 200},
                                           {"claim_id": "a", "approve": True, "status": 409}]))
        self.assertTrue(result["ok"])
        self.assertEqual(result["decision_status_counts"], {200: 1, 409: 1})

    def test_accepts_paid_as_agreeing_with_an_approve(self):
        result = c.check_c3(db([claim("a", "PAID")], audit("a", "SUBMITTED", "APPROVED", "PAID")),
                            rec(decisions=[{"claim_id": "a", "approve": True, "status": 200}]))
        self.assertTrue(result["ok"])

    def test_fails_on_two_decision_audit_events(self):
        result = c.check_c3(db([claim("a", "APPROVED")],
                               audit("a", "SUBMITTED", "APPROVED", "REJECTED")),
                            rec(decisions=[{"claim_id": "a", "approve": True, "status": 200}]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["claims_with_more_than_one_decision_audit_event"], 1)

    def test_fails_on_two_2xx_decision_replies(self):
        result = c.check_c3(db([claim("a", "APPROVED")], audit("a", "SUBMITTED", "APPROVED")),
                            rec(decisions=[{"claim_id": "a", "approve": True, "status": 200},
                                           {"claim_id": "a", "approve": False, "status": 200}]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["claims_with_more_than_one_2xx_decision"], 1)

    def test_fails_when_the_state_contradicts_the_accepted_reject(self):
        result = c.check_c3(db([claim("a", "APPROVED")], audit("a", "SUBMITTED", "APPROVED")),
                            rec(decisions=[{"claim_id": "a", "approve": False, "status": 200}]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["claims_whose_state_disagrees_with_the_2xx_decision"], 1)


class C4OnePayment(unittest.TestCase):
    def test_passes_for_one_payment_on_an_approved_claim(self):
        result = c.check_c4(db([claim("a", "PAID")], audit("a", "SUBMITTED", "APPROVED", "PAID")),
                            rec(payments=[{"claim_id": "a", "status": 200},
                                          {"claim_id": "a", "status": 409}]))
        self.assertTrue(result["ok"])

    def test_fails_on_two_paid_audit_events(self):
        result = c.check_c4(db([claim("a", "PAID")],
                               audit("a", "SUBMITTED", "APPROVED", "PAID", "PAID")),
                            rec(payments=[{"claim_id": "a", "status": 200}]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["claims_with_more_than_one_paid_audit_event"], 1)

    def test_fails_on_two_2xx_payment_replies(self):
        result = c.check_c4(db([claim("a", "PAID")], audit("a", "SUBMITTED", "APPROVED", "PAID")),
                            rec(payments=[{"claim_id": "a", "status": 200},
                                          {"claim_id": "a", "status": 200}]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["claims_with_more_than_one_2xx_payment"], 1)

    def test_fails_when_a_paid_reply_did_not_leave_the_claim_paid(self):
        result = c.check_c4(db([claim("a", "APPROVED")], audit("a", "SUBMITTED", "APPROVED")),
                            rec(payments=[{"claim_id": "a", "status": 200}]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["claims_with_a_2xx_payment_not_in_state_paid"], 1)

    def test_fails_when_paid_did_not_follow_approved(self):
        result = c.check_c4(db([claim("a", "PAID")], audit("a", "SUBMITTED", "PAID")), rec())
        self.assertFalse(result["ok"])
        self.assertEqual(result["claims_paid_without_an_approved_event"], 1)


class C5AuditMatchesState(unittest.TestCase):
    def test_passes_for_each_legal_chain(self):
        rows = [claim("sync", "IN_REVIEW"), claim("async", "APPROVED"),
                claim("fixed", "PAID"), claim("waiting", "NEEDS_HUMAN_CHECK"),
                claim("new", "SUBMITTED"), claim("no", "REJECTED")]
        events = (audit("sync", "SUBMITTED")
                  + audit("async", "SUBMITTED", "EXTRACTED", "APPROVED")
                  + audit("fixed", "SUBMITTED", "NEEDS_HUMAN_CHECK", "RECEIPT_CHECKED",
                          "APPROVED", "PAID")
                  + audit("waiting", "SUBMITTED", "NEEDS_HUMAN_CHECK")
                  + audit("new", "SUBMITTED")
                  + audit("no", "SUBMITTED", "EXTRACTED", "REJECTED"))
        result = c.check_c5(db(rows, events), rec())
        self.assertTrue(result["ok"], result["examples"])
        self.assertEqual(result["claims_checked"], 6)

    def test_fails_on_a_duplicate_event_in_an_otherwise_legal_chain(self):
        result = c.check_c5(db([claim("a", "IN_REVIEW")],
                               audit("a", "SUBMITTED", "EXTRACTED", "EXTRACTED")), rec())
        self.assertFalse(result["ok"])
        self.assertEqual(result["claims_whose_audit_does_not_match_its_state"], 1)

    def test_fails_when_an_approved_claim_has_no_decision_event(self):
        result = c.check_c5(db([claim("a", "APPROVED")], audit("a", "SUBMITTED", "EXTRACTED")),
                            rec())
        self.assertFalse(result["ok"])

    def test_fails_on_an_audit_event_without_a_claim(self):
        result = c.check_c5(db([claim("a", "IN_REVIEW")],
                               audit("a", "SUBMITTED") + audit("ghost", "SUBMITTED")), rec())
        self.assertFalse(result["ok"])
        self.assertEqual(result["audit_events_without_a_claim"], 1)

    def test_fails_when_a_claim_is_stuck_in_a_transient_state(self):
        result = c.check_c5(db([claim("a", "EXTRACTED")], audit("a", "SUBMITTED")), rec())
        self.assertFalse(result["ok"])


class C6JobsAccountedFor(unittest.TestCase):
    def passing_case(self, **overrides):
        rows = [claim("async", "PAID"), claim("bad", "APPROVED"), claim("sync", "IN_REVIEW")]
        events = (audit("async", "SUBMITTED", "EXTRACTED", "APPROVED", "PAID")
                  + audit("bad", "SUBMITTED", "NEEDS_HUMAN_CHECK", "RECEIPT_CHECKED", "APPROVED")
                  + audit("sync", "SUBMITTED"))
        jobs = [job("async", "DONE"), job("bad", "DEAD")]
        records = rec(async_claims=["async", "bad"], sync_claims=["sync"],
                      valid_receipt_claims=["async", "sync"], unparseable_claims=["bad"],
                      job_snapshot=["async"])
        records.update(overrides)
        return db(rows, events, jobs), records

    def test_passes_when_every_job_finished_and_the_split_is_right(self):
        database, records = self.passing_case()
        result = c.check_c6(database, records)
        self.assertTrue(result["ok"], result["examples"])
        self.assertEqual(result["job_snapshot_at_double_kill"], 1)
        self.assertEqual(result["reported_dead_jobs"], 1)

    def test_fails_when_a_job_is_still_pending(self):
        database, records = self.passing_case()
        database["jobs"].append(job("async2", "PENDING"))
        result = c.check_c6(database, records)
        self.assertFalse(result["ok"])
        self.assertEqual(result["jobs_still_pending_or_retry"], 1)

    def test_fails_when_a_claim_is_still_submitted(self):
        database, records = self.passing_case()
        database["claims"].append(claim("stuck", "SUBMITTED"))
        database["audit"].extend(audit("stuck", "SUBMITTED"))
        result = c.check_c6(database, records)
        self.assertFalse(result["ok"])
        self.assertEqual(result["claims_still_submitted"], 1)

    def test_fails_when_an_async_claim_has_no_job(self):
        database, records = self.passing_case()
        database["jobs"] = [j for j in database["jobs"] if j["claim_id"] != "async"]
        result = c.check_c6(database, records)
        self.assertFalse(result["ok"])
        self.assertEqual(result["async_claims_without_exactly_one_job"], 1)

    def test_fails_when_a_snapshot_job_never_left_pending(self):
        database, records = self.passing_case()
        database["jobs"] = [job("async", "RETRY"), job("bad", "DEAD")]
        result = c.check_c6(database, records)
        self.assertFalse(result["ok"])
        self.assertEqual(result["snapshot_jobs_still_pending_or_retry"], 1)

    def test_fails_when_a_valid_receipt_reached_needs_human_check(self):
        database, records = self.passing_case()
        database["audit"].extend(audit("sync", "NEEDS_HUMAN_CHECK"))
        result = c.check_c6(database, records)
        self.assertFalse(result["ok"])
        self.assertEqual(result["valid_receipt_claims_that_reached_needs_human_check"], 1)

    def test_fails_when_an_unparseable_receipt_did_not_reach_needs_human_check(self):
        database, records = self.passing_case()
        database["audit"] = [row for row in database["audit"]
                             if not (row["claim_id"] == "bad"
                                     and row["action"] == "NEEDS_HUMAN_CHECK")]
        result = c.check_c6(database, records)
        self.assertFalse(result["ok"])
        self.assertEqual(result["unparseable_claims_that_did_not_reach_needs_human_check"], 1)

    def test_reports_retried_jobs_without_failing(self):
        database, records = self.passing_case()
        database["jobs"] = [job("async", "DONE", attempts=2), job("bad", "DEAD")]
        result = c.check_c6(database, records)
        self.assertTrue(result["ok"])
        self.assertEqual(result["reported_done_jobs_with_attempts_above_zero"], 1)
        self.assertEqual(result["reported_max_attempts_on_one_job"], 2)


class C7Availability(unittest.TestCase):
    def test_passes_with_transport_failures_only_inside_a_down_window(self):
        result = c.check_c7(db(), rec(
            http=[http(t=151.0, status=0, instance="A"), http(t=151.5, status=200, instance="B"),
                  http(t=60.0, phase="decision", status=409)],
            down_windows=[{"instance": "A", "from": 150.0, "to": 215.0}]))
        self.assertTrue(result["ok"])
        self.assertEqual(result["transport_failures"], 1)
        self.assertEqual(result["conflicts_409_on_decision_payment_or_correction"], 1)

    def test_allows_two_seconds_of_grace_after_an_instance_reports_healthy(self):
        windows = [{"instance": "A", "from": 150.0, "to": 215.0}]
        self.assertTrue(c.check_c7(db(), rec(http=[http(t=216.5, status=0)],
                                             down_windows=windows))["ok"])
        self.assertFalse(c.check_c7(db(), rec(http=[http(t=218.5, status=0)],
                                              down_windows=windows))["ok"])

    def test_fails_on_a_transport_failure_against_a_live_instance(self):
        result = c.check_c7(db(), rec(http=[http(t=300.0, status=0, instance="B")],
                                      down_windows=[{"instance": "A", "from": 150.0,
                                                     "to": 215.0}]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["transport_failures_outside_a_down_window"], 1)

    def test_fails_on_any_5xx(self):
        result = c.check_c7(db(), rec(http=[http(status=500)]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["server_errors_5xx"], 1)

    def test_fails_on_a_409_outside_decision_payment_or_correction(self):
        result = c.check_c7(db(), rec(http=[http(phase="submit", status=409)]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["non_409_4xx"], 1)

    def test_fails_on_a_400(self):
        self.assertFalse(c.check_c7(db(), rec(http=[http(phase="decision", status=400)]))["ok"])

    def test_fails_when_a_request_gave_up_after_the_retry_window(self):
        result = c.check_c7(db(), rec(http=[http(t=400.0, status=0, gave_up=True)],
                                      down_windows=[{"instance": "A", "from": 363.0,
                                                     "to": 500.0}]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["requests_that_gave_up_after_90s_of_retries"], 1)

    def test_health_probes_are_reported_but_not_scored(self):
        result = c.check_c7(db(), rec(http=[http(phase="health", status=503)]))
        self.assertTrue(result["ok"])
        self.assertEqual(result["reported_health_probe_non_200"], 1)
        self.assertEqual(result["requests_scored"], 0)


class C8Recovery(unittest.TestCase):
    def test_passes_when_every_restart_was_healthy_in_time_and_flyway_is_clean(self):
        result = c.check_c8(db(), rec(restarts=[{"instance": "A", "seconds": 12.0,
                                                 "healthy": True}]))
        self.assertTrue(result["ok"])
        self.assertEqual(result["seconds_to_healthy"], [12.0])

    def test_fails_when_a_restart_took_longer_than_sixty_seconds(self):
        result = c.check_c8(db(), rec(restarts=[{"instance": "A", "seconds": 61.0,
                                                 "healthy": True}]))
        self.assertFalse(result["ok"])
        self.assertEqual(result["restarts_not_healthy_within_60s"], 1)

    def test_fails_when_a_restart_never_reported_healthy(self):
        self.assertFalse(c.check_c8(db(), rec(restarts=[{"instance": "B", "seconds": 5.0,
                                                         "healthy": False}]))["ok"])

    def test_fails_on_an_unsuccessful_flyway_row(self):
        self.assertFalse(c.check_c8(db(flyway_unsuccessful=1),
                                    rec(restarts=[{"instance": "A", "seconds": 5.0,
                                                   "healthy": True}]))["ok"])

    def test_fails_when_no_restart_was_recorded_at_all(self):
        self.assertFalse(c.check_c8(db(), rec(restarts=[]))["ok"])


class C9Sustained(unittest.TestCase):
    def requests(self, early: int, late: int) -> list[dict]:
        return ([http(t=61.0 + i * 0.001, phase="submit", status=200) for i in range(early)]
                + [http(t=541.0 + i * 0.001, phase="submit", status=200) for i in range(late)])

    def test_passes_when_late_throughput_is_at_least_half_of_early(self):
        result = c.check_c9(db(), rec(http=self.requests(100, 50)))
        self.assertTrue(result["ok"])
        self.assertEqual(result["state_changing_2xx_seconds_60_to_120"], 100)
        self.assertEqual(result["state_changing_2xx_seconds_540_to_600"], 50)
        self.assertEqual(result["late_over_early"], 0.5)

    def test_fails_just_below_the_threshold(self):
        self.assertFalse(c.check_c9(db(), rec(http=self.requests(100, 49)))["ok"])

    def test_fails_when_an_instance_is_unhealthy_at_the_end(self):
        self.assertFalse(c.check_c9(db(), rec(http=self.requests(10, 10),
                                              final_health={"A": True, "B": False}))["ok"])

    def test_fails_on_an_out_of_memory_error_in_a_log(self):
        result = c.check_c9(db(), rec(http=self.requests(10, 10),
                                      oom={"A": False, "B": True}))
        self.assertFalse(result["ok"])
        self.assertEqual(result["logs_with_outofmemoryerror"], ["B"])

    def test_counts_only_state_changing_2xx_inside_the_windows(self):
        noisy = (self.requests(10, 10)
                 + [http(t=61.0, phase="poll", status=200), http(t=61.0, status=409),
                    http(t=300.0, status=200)])
        result = c.check_c9(db(), rec(http=noisy))
        self.assertEqual(result["state_changing_2xx_seconds_60_to_120"], 10)


class Invalidity(unittest.TestCase):
    def valid_records(self, acked: int = 1500) -> dict:
        acks = [{"key": f"k{i}", "claim_id": f"c{i}", "kind": "async_valid"}
                for i in range(acked)]
        return rec(acks=acks,
                   kills=[{"instance": "A", "t": 150.0}, {"instance": "A", "t": 362.0},
                          {"instance": "B", "t": 362.0}],
                   http=[http(t=150.4, status=0, instance="A"),
                         http(t=362.3, status=0, instance="A"),
                         http(t=362.4, status=0, instance="B")],
                   job_snapshot=["c1", "c2"])

    def test_no_reason_when_the_run_satisfies_every_rule(self):
        self.assertEqual(c.invalidity_reasons(db(), self.valid_records(), scored=True), [])

    def test_too_few_acknowledged_claims_is_invalid(self):
        reasons = c.invalidity_reasons(db(), self.valid_records(999), scored=True)
        self.assertEqual(len(reasons), 1)
        self.assertIn("999", reasons[0])

    def test_a_kill_with_nothing_in_flight_is_invalid(self):
        records = self.valid_records()
        records["http"] = [h for h in records["http"] if h["t"] < 200]
        reasons = c.invalidity_reasons(db(), records, scored=True)
        self.assertEqual(len(reasons), 2)
        self.assertTrue(all("no request in flight" in reason for reason in reasons))

    def test_a_transport_failure_after_the_five_second_window_does_not_count(self):
        records = self.valid_records()
        records["http"] = [http(t=156.0, status=0, instance="A"),
                           http(t=362.3, status=0, instance="A"),
                           http(t=362.4, status=0, instance="B")]
        self.assertEqual(len(c.invalidity_reasons(db(), records, scored=True)), 1)

    def test_an_empty_job_snapshot_is_invalid(self):
        records = self.valid_records()
        records["job_snapshot"] = []
        self.assertIn("empty extraction job snapshot",
                      " ".join(c.invalidity_reasons(db(), records, scored=True)))

    def test_a_disk_abort_is_invalid_even_for_a_shakedown(self):
        records = self.valid_records()
        records["disk_abort"] = {"t": 120.0, "free_gib": 8.05, "floor_gib": 8.1}
        self.assertIn("disk watchdog",
                      " ".join(c.invalidity_reasons(db(), records, scored=False)))

    def test_a_shakedown_is_not_judged_on_claim_count_or_kills(self):
        self.assertEqual(c.invalidity_reasons(db(), rec(), scored=False), [])


class VerdictAndExitCode(unittest.TestCase):
    def passing_run(self) -> tuple[dict, dict]:
        claims, events, jobs, acks, decisions, payments = [], [], [], [], [], []
        for index in range(1200):
            cid = f"c{index}"
            claims.append(claim(cid, "PAID", key=f"k{index}", receipt_hash=f"h{index}"))
            events.extend(audit(cid, "SUBMITTED", "EXTRACTED", "APPROVED", "PAID"))
            jobs.append(job(cid, "DONE"))
            acks.append({"key": f"k{index}", "claim_id": cid, "kind": "async_valid"})
            decisions.append({"claim_id": cid, "approve": True, "status": 200})
            decisions.append({"claim_id": cid, "approve": True, "status": 409})
            payments.append({"claim_id": cid, "status": 200})
            payments.append({"claim_id": cid, "status": 409})
        records = rec(acks=acks, decisions=decisions, payments=payments,
                      async_claims=[f"c{i}" for i in range(1200)],
                      valid_receipt_claims=[f"c{i}" for i in range(1200)],
                      job_snapshot=["c0"],
                      kills=[{"instance": "A", "t": 150.0}, {"instance": "A", "t": 362.0},
                             {"instance": "B", "t": 362.0}],
                      restarts=[{"instance": "A", "seconds": 11.0, "healthy": True},
                                {"instance": "A", "seconds": 12.0, "healthy": True},
                                {"instance": "B", "seconds": 13.0, "healthy": True}],
                      down_windows=[{"instance": "A", "from": 150.0, "to": 215.0},
                                    {"instance": "A", "from": 362.0, "to": 395.0},
                                    {"instance": "B", "from": 362.0, "to": 396.0}],
                      http=[http(t=150.2, status=0, instance="A"),
                            http(t=362.2, status=0, instance="A"),
                            http(t=362.3, status=0, instance="B")]
                           + [http(t=61.0, status=200) for _ in range(100)]
                           + [http(t=541.0, status=200) for _ in range(80)])
        return db(claims, events, jobs), records

    def test_a_clean_run_passes_every_criterion(self):
        database, records = self.passing_run()
        report = c.evaluate(database, records, scored=True)
        self.assertEqual(report["verdict"], "pass", report["criterion_detail"])
        self.assertTrue(all(report["criteria"].values()))
        self.assertEqual(c.exit_code(report["verdict"]), 0)

    def test_one_broken_invariant_makes_the_run_fail(self):
        database, records = self.passing_run()
        database["audit"].extend(audit("c7", "PAID"))
        report = c.evaluate(database, records, scored=True)
        self.assertEqual(report["verdict"], "fail")
        self.assertFalse(report["criteria"]["C4"])
        self.assertEqual(c.exit_code(report["verdict"]), 1)

    def test_invalidity_outranks_a_broken_invariant(self):
        database, records = self.passing_run()
        database["audit"].extend(audit("c7", "PAID"))
        records["job_snapshot"] = []
        report = c.evaluate(database, records, scored=True)
        self.assertEqual(report["verdict"], "invalid")
        self.assertEqual(c.exit_code(report["verdict"]), 2)

    def test_every_criterion_is_evaluated(self):
        database, records = self.passing_run()
        report = c.evaluate(database, records, scored=True)
        self.assertEqual(sorted(report["criteria"]),
                         ["C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9"])


class ProtocolConstants(unittest.TestCase):
    """The numbers below are pre-registered; this guards against an accidental edit."""

    def test_the_committed_numbers_are_unchanged(self):
        self.assertEqual(c.LOAD_SECONDS, 600)
        self.assertEqual((c.SUBMITTER_THREADS, c.REVIEWER_THREADS), (6, 12))
        self.assertEqual((c.KILL_A_AT, c.RESTART_A_AT, c.BURST_AT, c.DOUBLE_KILL_LATEST,
                          c.RESTART_BOTH_AT), (150, 210, 360, 363, 390))
        self.assertEqual((c.BURST_CLAIMS, c.BURST_THREADS), (120, 20))
        self.assertEqual((c.RETRY_WINDOW_S, c.DRAIN_SECONDS), (90.0, 180))
        self.assertEqual(c.MIN_ACKED_CLAIMS, 1000)
        self.assertEqual(c.SUSTAIN_RATIO, 0.50)
        self.assertEqual((c.EARLY_WINDOW, c.LATE_WINDOW), ((60.0, 120.0), (540.0, 600.0)))
        self.assertEqual(c.HEALTHY_WITHIN_S, 60)
        self.assertEqual(c.TRANSPORT_GRACE_S, 2.0)
        self.assertEqual(c.DISK_ABORT_BYTES, int(8.1 * 1024 ** 3))
        self.assertEqual(dict(c.MIX), {"async_valid": 0.70, "sync_valid": 0.20,
                                       "async_unparseable": 0.10})
        self.assertEqual((c.MYSQL_PORT, c.PORT_A, c.PORT_B), (13316, 18081, 18082))
        self.assertEqual(c.EVIDENCE_DATE, "2026-11-06")

    def test_a_shakedown_stays_within_its_120_second_one_kill_budget(self):
        self.assertLessEqual(c.SHAKEDOWN_LOAD_SECONDS, 120)
        self.assertLess(c.SHAKEDOWN_KILL_AT, c.SHAKEDOWN_RESTART_AT)
        self.assertLess(c.SHAKEDOWN_RESTART_AT, c.SHAKEDOWN_LOAD_SECONDS)


if __name__ == "__main__":
    unittest.main(verbosity=2)
