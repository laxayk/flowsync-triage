# Replaying failed rows after a sync timeout
plan: all
tags: replay, failed rows, timeout, retry
reviewed: 2026-10-13

## When a run times out
When a sync run times out, FlowSync keeps the rows that failed in the run's "Failed rows" tab. Rows that already synced are not touched, so no duplicates are created.

## How to replay
Open the run and choose "Replay failed rows only". Only the failed rows are retried. A replay counts as a normal run toward your plan's run limits.

## Retention
Failed rows are kept for 14 days. After that they cannot be replayed and the next scheduled run picks the records up again if they still differ.

