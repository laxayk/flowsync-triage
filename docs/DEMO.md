# Demo script (about 5 minutes)

1. `.\run.ps1`, open http://localhost:4200, sign in as **agent**. The sample mailbox loads on start (or click "Load sample mailbox").
2. **Tickets tab.** Eight tickets from 18 emails: one per type (query, bug, wrong behaviour, enhancement, billing, account access, outage). Open the outage: P1, owner ravi, notification trail, pipeline trace with per-stage latency and the same correlation id as the logs.
3. **Threading.** Open the "Sync failed after timeout" ticket: Dana's follow-up is attached to the same ticket, not a new one. The duplicate delivery of the Salesforce mail was dropped on Message-ID.
4. **Review queue.** Show the three security holds (prompt injection, phishing with raw-IP and shortened links, `.pdf.exe` attachment), the spam quarantine, and the no-ticket items (out-of-office, sales pitch, praise, hotel booking). None reached the LLM or became tickets. The reason for each verdict is shown.
5. **Approval.** As agent, the reply draft's Send button is disabled. Sign out, sign in as **approver**, edit the draft, click Approve & send. In capture mode it lands in the outbox, and the audit log shows actor, draft hash and recipient. A second click is rejected (one send per draft).
6. **Live updates.** Open the dashboard in two windows, click "Load sample mailbox" is a no-op the second time (idempotent). With IMAP enabled, send a mail to the test inbox and watch the ticket appear within one poll.
7. **Fallback.** With `LLM_PROVIDER=none` everything above still works on keyword rules and tickets carry a "review" flag. With a Groq key, the same flow uses the model and the flag appears only on low-confidence results.
8. `GET /api/eval` (with a login token) runs the labelled samples through the current classifier and returns accuracy. Report it as a regression check on a small author-written set, not production accuracy.

## What to say about the decisions

- **Gate before LLM:** cheaper, explainable, and the obvious attacks never touch the model.
- **Java:** chosen for experience and typed concurrency. The LLM call dominates latency, so workers are virtual threads and the queue is a table. This has not been load-tested.
- **Not auto-sending:** replies need an approver; every send is audited.
- **Scale:** stateless workers scale out; the Postgres queue table is the first bottleneck, then a broker; IMAP polling can move to Gmail push.
