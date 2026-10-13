# Connector authentication errors
plan: all
tags: oauth, token expired, reauthorise, 401, auth error
reviewed: 2026-10-13

## Symptoms
A run fails with "authentication expired" or a 401 error when the OAuth token of a connector is revoked or expires.

## Fix
Open the connector and choose "Reconnect". Sign in again with an account that has access. Schedules resume by themselves after reconnecting. Rows that failed while the token was invalid can be replayed for 14 days.

