# MySQL crash and restart check: pre-registered protocol

Written on 11 November 2026 before the database-kill run. This protocol is a separate check from [the API crash/load protocol](CRASH_LOAD_PROTOCOL.md); that earlier run never killed MySQL. The first scored outcome, including a failure or an invalid setup, belongs in [RESULTS.md](../RESULTS.md) with its raw JSON evidence. Do not change the rules below in response to the result.

The author of this protocol had seen the 15 October single-job API restart smoke and the 7 November ten-minute API-kill/load result. They had not seen a database-kill result for this project. They chose the counts and deadlines below; no independent reviewer selected them.

## Question and boundary

Can one MySQL 8.4 server recover 20 acknowledged, pending synthetic text claims and extraction jobs after its process receives `SIGKILL`, while the API stays up, and can that API reconnect before a fresh API process drains the jobs? This is one host, one database, no replication and no client request deliberately left in flight at the database kill. It cannot establish failover, power-loss or disk-failure behavior.

## Fixed set-up

- Use the local `/opt/anaconda3/bin/mysqld` 8.4 binary with `--no-defaults`, a fresh ignored `target/db_crash_20261111/` directory, socket under `/private/tmp`, TCP port **13317** bound to loopback, 128 MiB buffer pool and 151 maximum connections. Check `innodb_flush_log_at_trx_commit=1` and `sync_binlog=1`; otherwise the scored run is invalid. Require more than **8.1 GiB** free disk before starting and again before the kill, or do not start/mark invalid.
- Use the existing Java 21 packaged JAR and MySQL profile on loopback port **18083**, with extraction polling disabled to make the pre-kill pending set exact. Do not rebuild or change Java application code after freezing this protocol unless a failure is reported and an `after` run is clearly distinguished.
- Generate 20 unique, valid synthetic meal receipts (date `2026-11-11`, SGD 18.50), each with a distinct `Idempotency-Key` and merchant containing a random nonce. Save only IDs, keys, HTTP statuses, process timings, SQL counts and audit actions in evidence; no real receipts or secrets. The JAR, MySQL, and checker share one laptop, so timing is descriptive.
- Before scoring, an unscored setup check may start and stop the empty database and API to debug process launch, but it must not kill MySQL or create the 20-claim set.

## Sequence

1. Create a new data directory, start MySQL, create the `claimflow` schema and user, start one API with the poller disabled, and wait for health. Record the JAR SHA-256 and current project commit.
2. Submit exactly 20 async claims and record each 2xx claim ID. Before killing the database, query SQL for 20 distinct claims, 20 `PENDING` jobs and 20 `SUBMITTED` audit events; query each claim through the API and confirm `SUBMITTED`.
3. Send `SIGKILL` **only** to this isolated MySQL process and wait for that PID to exit. Observe one API health probe reporting down within 15 seconds. Do not send a state-changing request during the outage.
4. Start the **same** MySQL data directory without initialization. Wait up to 120 seconds for the socket query to work. Before restarting the API, require its health to return UP within 60 seconds, and GET all 20 original IDs as `SUBMITTED` through the same API process. Read SQL again: the same 20 claims and jobs remain, with one `SUBMITTED` audit per claim, and no unsuccessful Flyway migration.
5. Stop that API, start a fresh instance from the same JAR with polling enabled on the same port, and wait up to 60 seconds for health. Within another 60 seconds require all 20 claims to reach `IN_REVIEW`. Query SQL for exactly one `SUBMITTED` and one `EXTRACTED` audit per claim, 20 `DONE` jobs, no pending/retry/dead jobs, and no extra claim rows. Replay one original submission with the same key/body and require the same ID with no extra row.
6. Stop the API and MySQL, remove only this check's data directory and socket, and verify ports 13317 and 18083 are free. Write `evidence/db_crash/first_2026-11-11.json` even when a criterion fails. Preserve debug logs in the evidence JSON or under ignored `target/` until the failure is understood.

## Verdict

All of D1–D6 must pass:

- **D1, pre-kill set:** 20 distinct 2xx IDs, 20 claim rows, 20 pending jobs and 20 single `SUBMITTED` audit chains before the kill.
- **D2, actual crash:** this run sent `SIGKILL` to the isolated MySQL PID, the PID exited, and the running API health became down within 15 seconds.
- **D3, durable recovery:** the same directory restarts within 120 seconds with both durability settings equal to 1; the unchanged API becomes healthy within 60 seconds, returns all 20 original claims as `SUBMITTED`, and SQL shows their original 20 pending jobs and single `SUBMITTED` audit chains with clean Flyway history.
- **D4, extraction after recovery:** a new API process becomes healthy within 60 seconds and all 20 jobs finish within another 60 seconds; each claim is `IN_REVIEW`, each job `DONE`, and each audit has exactly one `SUBMITTED` and one `EXTRACTED` action.
- **D5, idempotency:** replay of one saved key and identical body returns its original claim ID and no additional claim row.
- **D6, isolation:** the run leaves no listener on either reserved port and deletes only its own disposable data directory/socket.

The run is **invalid**, rather than a product pass/fail, if the port or data directory was already occupied, the required binary/JAR is missing, free disk is at or below 8.1 GiB before kill, a different process was killed, the generated set was not 20 acknowledged claims, or the checker cannot read the SQL/audit evidence. All other unmet criteria are failures. One first scored run is saved as `first`; a code fix requires a separate dated `after` result with the failed first result retained.

## Limits

`SIGKILL` simulates an abrupt process exit, not loss of power or storage durability. The isolated MySQL has no replica, so this does not test failover. All claims are acknowledged and pending before the kill; it does not cover a commit or HTTP reply in flight at the kill. The 20-claim set and timings were selected by the author. A pass is a narrow observation on this laptop, not a general reliability or throughput guarantee.
