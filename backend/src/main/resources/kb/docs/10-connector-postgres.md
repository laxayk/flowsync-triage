# Postgres connector
plan: all
tags: postgres, database, sql, table, ssl
reviewed: 2026-10-13

## What it syncs
The Postgres connector reads and writes a table or a read-only view. You choose the table and a unique key column.

## Connecting
Provide host, port, database, user and password. SSL is required. If your database is behind a firewall, allow the FlowSync egress addresses listed under Settings, Network.

## Notes
Writes use upsert on the key column, so the table needs a unique constraint on it. Arbitrary SQL is not supported.

