# Replaying failed rows after a sync timeout
When a sync run times out, FlowSync keeps the rows that failed in the run's "Failed rows" tab. Open the run, choose "Replay failed rows only" and only those rows are retried. Rows that already synced are not touched, so no duplicates are created.
Replays count as a normal run toward your plan's run limits. Failed rows are kept for 14 days.
