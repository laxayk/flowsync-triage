# OrbitFin
domain: orbitfin.example
plan: Enterprise
sla: 24x7, first response within 1 hour for P1 and P2
csm: maya
connectors: Salesforce to Google Sheets (nightly) used for finance reporting
notes: Finance team depends on the nightly sheet for month-end close. Missing rows are treated as a data-integrity incident by the customer.
