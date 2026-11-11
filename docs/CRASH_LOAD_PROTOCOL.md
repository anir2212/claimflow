# Crash and sustained-load check: pre-registered protocol

Written and committed on 6 November 2026, before any load or kill was run. Results go in a new dated section of [RESULTS.md](../RESULTS.md), whatever they show. Nothing in this file is a result.

Who chose these settings and what they had seen: the agent implementing this item chose every number below. It had read the code, the 24 October two-instance results and a no-load feasibility check (one API process migrating an empty database and extracting one claim). It had seen no load or crash data from this set-up.

## Question

If an API process is killed with `kill -9` while claims are being submitted, decided and paid, does the database still tell the truth afterwards? "The truth" means: no acknowledged claim is lost, no claim is decided or paid twice, every claim's audit history matches its state, and queued extraction jobs are finished or accounted for.

## Why a kill differs from the earlier restart check

The 15 October restart check stopped a process cleanly, with no requests in flight. `kill -9` gives the process no chance to finish a request, roll back or close its connections. Replies are lost even when the commit succeeded, so a client cannot tell what happened and has to retry.

## Set-up

- **Database: MySQL 8.4.0 from the Anaconda binary already on this Mac (`/opt/anaconda3/bin/mysqld`).** Not Docker, and not the Compose `mysql:8.4` image, so this is a different build of the same release series. It runs with `--no-defaults` on `127.0.0.1:13316` with a new data directory under ignored `target/crashdb/`, created for each scored run and deleted after the last run. Durability settings stay at their defaults (`innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`), with a 128 MiB buffer pool and `max_connections=151`. The database is **not** killed in this check.
- **API:** two processes from the same JAR, `SPRING_PROFILES_ACTIVE=mysql`, the same JWT secret, `-Xmx384m`, ports 18081 (A) and 18082 (B), both extraction pollers on.
- **Client and orchestrator:** `scripts/crash_load_check.py`, Python standard library plus the `mysql` command-line client for the final check. It runs on the same laptop as everything else. The laptop: Apple M3 Pro, 11 cores, 18 GiB memory, macOS 15.3.1, shared with other work. The workspace CPU lock is held during scored runs. Latency and throughput are descriptive only and are not a capacity claim.
- All claims are synthetic: unique merchant names carrying a random run nonce, dated 2026-11-06, SGD 10.00–24.99.

## Load shape

Load runs for **600 seconds**.

- **Submitters:** 6 threads, each waiting 1 second between submissions, so at most about 6 claims per second. Each claim is chosen at random: 70% async text with a valid receipt, 20% synchronous text with a valid receipt, 10% async text that cannot be parsed, which must go to `NEEDS_HUMAN_CHECK`.
- **Reviewers:** 12 threads take each acknowledged claim in turn. They poll it until it leaves `SUBMITTED`. An unparseable claim gets a manager correction. The claim then gets a **decision race**: two requests released together, one to each instance when both are up. For 80% of claims both requests approve; for 20% one approves and one rejects. An approved claim then gets a **payment race** of two requests released together.
- **Console refresh:** one thread calls `mine`, `queue`, `receipt-check`, `approved` and `stats` every 10 seconds, as the console's poll does. These lists have no pagination and grow during the run.
- **Retries:** a transport failure or 5xx is retried with the same request and the same `Idempotency-Key`, once a second, preferring the other instance, for up to 90 seconds. A 409 on a retry is accepted as an answer, because the first attempt or the other racer may have committed. The client records every attempt.

## Kill schedule

Times are seconds from the start of load.

| Time | Action |
|---|---|
| 150 | `kill -9` instance A. B keeps serving. |
| 210 | Restart A. |
| 360 | Burst of 120 extra async valid claims from 20 threads. |
| When every burst request is answered, or at 363 at the latest | `kill -9` A and B together, with no API left running. Immediately read the `PENDING` and `RETRY` job IDs from MySQL. |
| 390 | Restart A and B. |
| 600 | Stop new submissions. Reviewers finish what is queued, with up to 180 seconds to drain. |

## Pass criteria (all must hold)

Checked with SQL over the whole `claims`, `audit_events` and `extraction_jobs` tables, plus the client's records.

- **C1, no lost claim.** Every claim acknowledged with a 2xx is in the database with the acknowledged ID.
- **C2, idempotent retries.** For each `Idempotency-Key` there is at most one claim, and every acknowledgement for a key, including retries, returned the same ID. No two claims share a non-null `receipt_hash`.
- **C3, one decision.** Each claim has at most one `APPROVED` or `REJECTED` audit event and at most one 2xx decision reply. If there was a 2xx reply, the final state agrees with it (`APPROVED` or `PAID` for an approve, `REJECTED` for a reject).
- **C4, one payment.** Each claim has at most one `PAID` audit event and at most one 2xx payment reply. A claim with a 2xx payment reply is `PAID`. `PAID` always follows `APPROVED`.
- **C5, audit matches state.** Every claim's audit actions, taken as a set of counts, equal one allowed chain for its state, and there are no audit events without a claim. The prefix is `SUBMITTED` (synchronous, no job), `SUBMITTED, EXTRACTED` (async), or `SUBMITTED, NEEDS_HUMAN_CHECK, RECEIPT_CHECKED` (corrected). `IN_REVIEW` is a prefix alone; `APPROVED` and `REJECTED` add that decision; `PAID` adds `APPROVED, PAID`. `NEEDS_HUMAN_CHECK` is `SUBMITTED, NEEDS_HUMAN_CHECK`. `SUBMITTED` is `SUBMITTED` alone.
- **C6, jobs accounted for.** After draining: no job is `PENDING` or `RETRY`, no claim is `SUBMITTED`, and each async claim has exactly one job. Every job in the snapshot at the double kill has left `PENDING` and `RETRY`, and its claim has left `SUBMITTED`. No claim with a valid receipt reached `NEEDS_HUMAN_CHECK`, and every claim with an unparseable receipt did. `DONE` jobs with `attempts > 0` are reported, because they show a kill or error used up a retry.
- **C7, availability around the kills.** No 5xx from any instance at any time. A transport failure is allowed only against an instance that was down at that moment, with 2 seconds of grace after it reported healthy. No 4xx except a 409 on a decision, payment or correction. No client request gives up after the 90 seconds of retries.
- **C8, recovery.** Each restarted process reports healthy within 60 seconds with no manual repair, and no Flyway row is unsuccessful.
- **C9, sustained.** Both instances are healthy at the end, no log contains `OutOfMemoryError`, and state-changing 2xx replies in seconds 540–600 are at least 50% of those in seconds 60–120. This threshold was chosen before any load data existed.

## When a run is invalid rather than failed

A run is reported as **invalid**, with its numbers, not as a pass or a fail, if any of these holds: fewer than 1,000 claims acknowledged; no request in flight at a kill (no transport failure recorded in that kill's first 5 seconds); an empty job snapshot at the double kill; or the disk watchdog stops the run because free disk reached 8.1 GiB (the workspace floor is 8 GiB).

## Runs

- **Shake-down.** Up to two short runs, of at most 120 seconds with one kill, may be used only to debug the script itself. Their output is not scored, and they do not change any criterion, threshold or schedule above. They are mentioned in the results.
- **`before`:** the JAR built from the commit current when the run starts, with no backend change. Reported as observed, pass or fail.
- **`after`:** run only if a fix is made, using the JAR with that fix, on a new data directory, with the same protocol and thresholds. A failed `before` stays in the record.

The script exits 0 on a pass, 1 on a failure and 2 on an invalid run. It writes `evidence/crash_load/<label>_2026-11-06.json`. Any later change to this protocol is added below with its date and reason, and the original text stays.

## Limits that apply whatever the outcome

One laptop, with the load generator, both API processes and the database on the same machine, over loopback, at no more than about 6 new claims per second for ten minutes. Three process kills in one run, at chosen times. The database is not killed, and there is no failover, network partition, disk failure or power loss. A pass shows these specific kills did not break these invariants in this run. It does not prove that every interleaving is safe, and it is not a production capacity figure.
