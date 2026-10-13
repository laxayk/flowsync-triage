# Sync schedules and run frequency
plan: all
tags: schedule, frequency, interval, skipped run, real time
reviewed: 2026-10-13

## How syncing works
FlowSync syncs on a schedule. It is not a real-time or streaming product: a change in the source appears in the target at the next run.

## Shortest interval per plan
Starter: every 15 minutes. Pro: every 5 minutes. Business: every 5 minutes. Enterprise: every 1 minute. A schedule can also be hourly, daily or weekly.

## Skipped runs
A run is skipped while the previous run is still active. Check the connector's "Schedule" tab to see why a run was skipped.

## Source API limits
Salesforce and HubSpot connectors respect the source API limits and slow down automatically when the source returns rate-limit errors.

