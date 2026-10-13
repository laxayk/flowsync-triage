# Salesforce connector
plan: all
tags: salesforce, connector, oauth, objects
reviewed: 2026-10-13

## What it syncs
The Salesforce connector reads and writes Contacts, Accounts and Leads. Custom objects are not supported.

## Connecting
Connect with OAuth from Connectors, Add. The user who authorises needs API access in Salesforce.

## Limits
The connector respects Salesforce API limits and slows down when it sees rate-limit errors. Deleted records are not synced; FlowSync never deletes rows in the target.

