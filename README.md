# FlowSync Triage

AI email triage agent. It watches one support inbox, screens every mail for spam, phishing and prompt injection,
classifies what is left, turns it into tickets, notifies the owner, and drafts replies that a human approves with one click.

The fictional company is **FlowSync**, a B2B SaaS that syncs records between tools customers already use
(Salesforce/HubSpot to databases and sheets). The sample mailbox is written for that product.

> Status: work in progress on `develop`. Built so far: backend pipeline, REST API, live dashboard, Docker, tests.
> Nothing here has been run against a real mailbox yet; the demo uses the bundled sample emails.

## How it works

```
IMAP inbox / sample .eml files
        |  store raw mail (dedupe on Message-ID)           <- emails table is the durable queue
        v
virtual-thread worker (per-thread lock)
   1. Safety gate (rules, no LLM)   prompt injection / phishing / risky attachment -> SECURITY HOLD
                                    spam -> QUARANTINE;  auto-reply / cold sales -> NO TICKET
   2. Thread match                  In-Reply-To / References / subject -> attach to existing ticket
   3. Classify (LLM, JSON, validated) QUERY BUG WRONG_BEHAVIOUR ENHANCEMENT BILLING ACCOUNT_ACCESS OUTAGE | PRAISE SPAM SALES IRRELEVANT
        falls back to keyword rules when the LLM is off, slow or invalid (ticket is flagged "needs review")
   4. Ticket + owner + priority + notifications (Slack webhook, email captured)
   5. Reply draft for query-type mail, grounded in a small knowledge base; sent only after an APPROVER clicks approve
        |
        v
Angular dashboard (live updates over SSE), audit log, pipeline trace per mail
```

Key choices:

- **Java 21 / Spring Boot, Angular, Postgres.** Chosen for experience and for typed concurrency; the LLM call dominates latency, so workers are virtual threads and the pipeline is queue-based.
- **One OpenAI-compatible LLM client.** Groq (default, free tier), OpenAI and Ollama differ only by base URL, model and key. No key means the keyword fallback runs, and the app still works.
- **The gate runs before the model.** Hostile or junk mail never reaches the LLM, which saves cost and removes the injection surface for obvious attacks. Email text is also treated as untrusted data in prompts, validated on the way out, and escaped in every sink.
- **Replies are never auto-sent.** Default send mode is `capture` (stored in the outbox table). `live` sends via SMTP from the dedicated test mailbox.
- **Roles.** `agent` works tickets; `approver` can send replies and release held mail. Every approval is audited with actor, draft hash and recipient.

## Run it (Windows, Docker Desktop)

```powershell
.\run.ps1
```

That copies `.env.example` to `.env` on first run, builds and starts Postgres, backend and dashboard, then opens http://localhost:4200.
Sign in as `agent` or `approver` (demo passwords are in `.env`). The sample mailbox loads on start, or use **Load sample mailbox**.
`.\run.ps1 -Down` stops it, `.\run.ps1 -Logs` follows backend logs. Same thing on any OS: `docker compose up --build`.

### Without Docker (quick look)

```bash
cd backend && SPRING_PROFILES_ACTIVE=h2 mvn spring-boot:run      # in-memory H2, data lost on restart
cd frontend && npm install && npm start                          # http://localhost:4200, proxies /api to :8080
```

## Configuration (`.env`)

| Setting | Purpose |
|---|---|
| `LLM_PROVIDER` `LLM_API_KEY` `LLM_MODEL` `LLM_BASE_URL` | `groq`, `openai`, `ollama` or `none` (keyword fallback). Check the provider's current free-tier limits yourself. |
| `IMAP_ENABLED` `IMAP_USER` `IMAP_PASSWORD` | Poll a **dedicated test Gmail** (app password). Do not point this at a real inbox. |
| `SEND_MODE` `SEND_FROM` `SMTP_*` | `capture` (default) or `live`. |
| `SLACK_WEBHOOK_URL` | Optional. Without it, notifications are recorded in the dashboard only. |
| `AGENT_PASSWORD` `APPROVER_PASSWORD` | Demo logins. Change them if the app is reachable by anyone else. |

Secrets live in `.env` (git-ignored). `.env.example` has no real values.

## Tests

```bash
cd backend && mvn test        # gate and classifier unit tests, full sample-mailbox pipeline test, API/roles/approval test
cd frontend && npx ng build
```

CI runs both on every push (`.github/workflows/ci.yml`).

## Known limits (honest list)

- The keyword fallback is deliberately simple; its accuracy has not been measured. LLM accuracy and latency are also unmeasured until the eval run is done.
- Reply drafts use a keyword search over six short knowledge-base files, not a vector store.
- Sessions are in memory and auth is demo-grade (seeded users). Production would use SSO.
- Not yet built: WhatsApp notifications, Gmail push (IMAP polling only), a labelled eval harness, load numbers.
