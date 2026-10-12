# Sync schedules and API limits
Connectors sync on a schedule from every 5 minutes (Pro) or every 15 minutes (Starter). Salesforce and HubSpot connectors respect the source API limits and slow down automatically when the source returns rate-limit errors.
If a run is skipped, check the connector's "Schedule" tab: a run is skipped while the previous run is still active.
