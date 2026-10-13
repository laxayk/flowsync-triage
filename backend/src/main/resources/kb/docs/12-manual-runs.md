# Running a sync manually
plan: all
tags: manual run, run now, trigger, dashboard, api
reviewed: 2026-10-13

## Run now from the dashboard
Open the connector and choose "Run now". A manual run is a normal run and counts toward your plan's run limits. It is skipped if a run is already active.

## Run from the API
A run can be started with POST /v1/connectors/{id}/runs using an API key. It returns a run id you can poll for status.

