# Google Sheets connector
plan: Pro, Business, Enterprise as source; all plans as target
tags: sheets, google sheets, spreadsheet, tab
reviewed: 2026-10-13

## What it syncs
The Google Sheets connector reads or writes one tab per connector. The first row is treated as column headers.

## Connecting
Sign in with a Google account that can edit the spreadsheet. Sheets as a source is available on Pro and above; as a target it is available on every plan.

## Limits
Large tabs sync more slowly because Google limits the rate of API calls. Formulas are read as their computed values, and FlowSync overwrites cells it writes.

