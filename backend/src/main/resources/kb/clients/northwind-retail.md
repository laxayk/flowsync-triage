# Northwind Retail
domain: northwind-retail.example
plan: Pro
sla: Business hours, first response within 8 hours
csm: sam
connectors: HubSpot to Postgres (nightly), Postgres to Google Sheets (hourly)
notes: Retail customer, about 2 million contact rows. Dana Whitfield is the data lead. Had timeouts on large syncs in September; replay of failed rows is the usual fix.
