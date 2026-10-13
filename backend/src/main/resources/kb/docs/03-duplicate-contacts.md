# Avoiding duplicate contacts
plan: all
tags: duplicates, upsert, match key
reviewed: 2026-10-13

## Why duplicates appear
Contacts are matched on the key field you choose in the mapping (email is the default). If the target has no unique constraint on that field, FlowSync cannot upsert and inserts a new row each run.

## Fix
Set the match key in the connector mapping and enable "Upsert on key". Existing duplicates are not merged automatically; remove them in the target.

