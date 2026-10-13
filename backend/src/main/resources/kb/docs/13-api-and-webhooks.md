# API and webhooks
plan: Pro, Business, Enterprise
tags: api, webhook, api key, rest, events
reviewed: 2026-10-13

## API access
The REST API is available on Pro and above. Create an API key under Settings, API keys. Keys can be revoked at any time. The API lets you list connectors, start runs and read run status.

## Webhooks
Webhooks send an HTTP POST when a run finishes or fails. Add the URL under Settings, Webhooks. Each call is signed with a secret so you can verify it. Failed deliveries are retried 3 times.

## Not available
There is no inbound webhook that triggers a sync when a source record changes. Syncs are scheduled or started manually.

