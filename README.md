# FlowSync Triage

AI email triage agent. It watches one support inbox, screens every mail for spam, phishing and prompt injection,
classifies what is left, turns it into tickets, notifies the owner, and drafts replies that a human approves with one click.

The fictional company is **FlowSync**, a B2B SaaS that syncs records between tools customers already use
(Salesforce/HubSpot to databases and sheets). The sample mailbox is written for that product.

> Status: demo build on `main`. Works end to end on the bundled sample mailbox with no API key. The author has run it with Groq (see Known limits for what was measured). Still not verified: real SMTP sending, long-running IMAP polling and a real pgvector database.

**What is real and what is invented.** The app (gate, classifier, pipeline, drafting agent, retrieval, dashboard) is real software. FlowSync, its customers, plans, connectors and docs are fictional and exist only to test it. The docs state that FlowSync is a demo product and claim no certifications.

![Tickets](docs/img/tickets.png)

Demo video (about 1 minute, sample data): [docs/demo.mp4](docs/demo.mp4)

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
   5. Drafting agent (query, billing, access mail): the LLM picks read-only tools in a bounded loop (search_knowledge, lookup_customer,
      ticket_history, open_incidents), then writes the reply. If no document answers, the draft is a short handoff reply flagged for a person.
      A limited claim check flags numbers and product terms that the retrieved text does not contain. Scripted run of the same tools when no LLM
      (the draft says why the model was not used). Sent only after an APPROVER clicks approve
   6. Owner briefing: an email to the owning department's owner with the ticket, customer plan/SLA and the department's escalation process
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

## Diagrams

### Pipeline

```mermaid
flowchart TD
    A[IMAP poll or sample .eml] --> B[Store raw mail, dedupe on Message-ID]
    B --> C{Safety gate, rules only}
    C -->|injection, phishing, risky attachment| H[Security hold, human review]
    C -->|spam| Q[Quarantine]
    C -->|auto-reply, cold sales| N[No ticket]
    C -->|clean| T{Reply to an existing thread?}
    T -->|yes| U[Attach to ticket, reopen, notify assignee]
    T -->|no| K[Classify: LLM JSON, keyword fallback]
    K -->|praise, irrelevant| N
    K -->|ticketable| R[Create ticket, owner, priority]
    R --> S[Notify assignee]
    R --> D[Drafting agent: query, billing, access only]
    R --> O[Owner briefing to department owner]
    D --> V[Approver reviews, edits, sends]
    V --> X[Outbox: capture or SMTP]
```

### Drafting agent loop

```mermaid
flowchart TD
    S[Stored email, untrusted] --> M[Ask model for one JSON action]
    M --> P{Valid action?}
    P -->|model error, invalid twice or no finish in 7 turns| F[Scripted fallback, same tools, reason shown on the draft]
    P -->|tool| TL[Run read-only tool]
    TL --> L[Result appended to next prompt]
    L -->|under 4 calls| M
    L -->|budget used| M2[Ask for final]
    M2 --> Z
    P -->|final| G{Any document cleared the relevance bar?}
    G -->|no| HO[Handoff reply, needs human, logged as a knowledge gap]
    G -->|yes| CK[Claim check: numbers and product terms must appear in the retrieved text]
    CK -->|unsupported| FL[Reply kept, flagged needs human with the unsupported terms]
    CK --> Z[Reply, sources, needs_human]
    FL --> Z
    HO --> Z
    F --> Z
    Z --> H[Draft + step trace stored, human approves]
```

Tools: `search_knowledge` (vector search over docs, client profiles, process notes), `lookup_customer` (bound to the stored sender), `ticket_history`, `open_incidents`. All read-only.

### Modules

| Package | Role |
|---|---|
| `ingest` | ImapPoller, EmailParser, SampleLoader, Assigner |
| `gate` | SafetyGate: regex rules for injection, phishing, spam, sales, auto-replies, risky attachments |
| `llm` | One OpenAI-compatible client, Classifier, keyword HeuristicClassifier |
| `agent` | DraftAgent, AgentTools, KnowledgeBase, Embedder, OwnerAlert, Departments |
| `pipeline` | Pipeline orchestration, Evaluator |
| `notify` / `store` / `web` | Notifier and Outbox, JDBC store with Flyway, REST API with auth and SSE |

### Screens

| Agent trace | Owner briefing |
|---|---|
| ![Agent trace](docs/img/agent-trace.png) | ![Owner briefing](docs/img/owner-briefing.png) |

![Review queue](docs/img/review-queue.png)

## The agent layer

- **Knowledge base.** `backend/src/main/resources/kb/` holds 25 fictional FlowSync product docs, 7 client profiles and 5 escalation process notes.
  Product docs are split by `## ` section so one chunk answers one question; each doc carries `plan`, `tags` and `reviewed` front matter that is searched too.
  Chunks are embedded once and stored in `kb_chunks` with the embedder id and a content hash (restarts re-embed only what changed).
  **Search is hybrid:** BM25 keyword ranking and vector similarity are merged with reciprocal rank fusion. A hit's score is the weighted share of the question's terms found in the chunk,
  and a chunk under the bar (`KnowledgeBase.MIN_RELEVANCE`, 0.30) is not returned, so "no document answers this" is a real outcome. The default embedder is a local feature-hashing one (no model, no network, no RAM); it matches words, not meaning,
  which is why the keyword half matters. Groq has no embeddings endpoint. A hosted or local embedding model can be set with `EMBED_PROVIDER` and `EMBED_MODEL`, but it is not recommended on an 8 GB laptop and not needed so far.
  With `PGVECTOR=true` vectors are also written to a pgvector column (not yet run against a real pgvector database); search itself runs in memory.
  The bar and weights were picked on 41 author-written questions (`backend/src/test/resources/kb-eval.tsv`, run by `KbEvalTest`): against the old whole-file vectors, hit@1 went 24 to 32 and hit@3 33 to 37 of 37 answerable questions, and 4 of 4 unanswerable questions were handed off instead of 2 of 4. The author also wrote the docs, and one doc tag was added after a miss, so this is a regression check, not a benchmark.
- **Drafting agent.** With Groq/OpenAI/Ollama the tools are declared as native functions plus `final_reply` (the earlier JSON-in-text protocol made gpt-oss on Groq answer `tool_use_failed`); other providers keep the JSON protocol. At most 4 tool calls; a model error, invalid output or no finish falls back to the scripted run, and the draft's "by" label says why.
  Every tool is read-only, the customer is always the stored sender (model arguments cannot redirect the lookup), and the email is treated as untrusted data. The dashboard shows the full tool trace under each draft, and **Regenerate draft** rejects the open draft and asks the agent again.
- **Grounding check (a limited heuristic).** After the model writes a reply, numbers that appear in neither the retrieved text nor the customer's mail, and product or compliance terms (for example "real time", "SOC 2", a connector name) that no retrieved text contains, flag the draft as needs human and mark the ticket for review. The reply text is not edited. It cannot prove a reply is grounded: a wrong claim made of words that do appear in the documents passes. A person still approves every send.
- **Unrelated mail** is never silently dropped: it stays in the review list with a "possibly unrelated" reason and can be released into a ticket. Whether it should be dropped instead is still an open policy decision.
- **Rate limits.** Calls to Groq are spaced 2.5 s apart (`LLM_MIN_INTERVAL_MS`, 0 disables) and `Retry-After` is honoured, so a burst stays under the free tier's tokens-per-minute limit. Rate limits and bad requests do not open the circuit breaker, only outages do.
- **Owner briefing.** After a ticket is created, the department owner (`DEPARTMENTS` in `.env`, never taken from the email) gets a short brief. One per ticket.
  Goes through the same switch as replies: `SEND_MODE=capture` stores it (shown on the ticket), `live` sends it over SMTP.

## Checking the real model

Set `LLM_PROVIDER=groq` and `LLM_API_KEY=...` in `.env`, run `.\run.ps1`, then `.\scripts\eval-live.ps1`. It prints accuracy for the basic and messy sets and every miss, and shows whether each answer came from the model or the keyword fallback. Send test mails one at a time (the free tier has a tokens-per-minute limit) and avoid re-running `eval-live` back to back. Open a query ticket to read the agent trace; the draft's "by" label shows whether the model or the scripted fallback wrote it, and the Stats panel shows the last model error.

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
| `IMAP_ENABLED` `IMAP_USER` `IMAP_PASSWORD` | Poll a **dedicated test Gmail** (app password). Do not point this at a real inbox. The poller reads unread mail and marks it read. `IMAP_MAX_AGE_DAYS` skips older unread mail, and a mail that cannot be parsed is skipped until restart so it cannot block the ones behind it. |
| `LLM_MIN_INTERVAL_MS` | Spacing between model calls. Default 2500 for Groq, 0 otherwise. |
| `SEND_MODE` `SEND_FROM` `SMTP_*` | `capture` (default) or `live`. |
| `SLACK_WEBHOOK_URL` | Optional. Without it, notifications are recorded in the dashboard only. |
| `AGENT_PASSWORD` `APPROVER_PASSWORD` | Demo logins. Change them if the app is reachable by anyone else. |

Secrets live in `.env` (git-ignored). `.env.example` has no real values.

## Tests

```bash
cd backend && mvn test        # gate, classifier, LLM client errors and pacing, native tool calls, grounding, retrieval eval, regenerate, full sample-mailbox pipeline, API/roles/approval
cd frontend && npx ng build
```

CI runs both on every push (`.github/workflows/ci.yml`).

## Known limits (honest list)

- Measured by the author on Groq (`openai/gpt-oss-20b`): classification on the basic set 1.0 disposition and 1.0 category (16 mails); on the messy set 1.0 disposition and 0.92 category (28 mails, 18 answered by the model, 5 by the keyword fallback, 5 by the gate). Misses: `m13` (SALES vs SPAM) and `m25` (ENHANCEMENT vs QUERY). These sets are small and author-written: regression checks, not accuracy claims.
- Reply quality is not measured. The author saw the agent write replies and the claim check target an invented "real time" claim, but there is no score for reply accuracy yet. The keyword fallback is deliberately simple.
- The extra "scripted" reply the author once saw next to a model reply is not explained. The code writes one draft per ticket and I could not reproduce a second one, so it stays open until there is a ticket and its "by" label.
- Retrieval numbers above are on the fictional docs and author-written questions. Keyword plus hash vectors match words, not meaning.
- Not verified: real SMTP sending (default is `capture`), IMAP polling over a long period, a real pgvector database. CI builds and tests the code, not the Docker images.
- Sessions are in memory and auth is demo-grade (seeded users). Demo logins are `agent123` and `approver123` by default: change them for anything shared.
- The regex gate is not a security boundary against a determined attacker. Read-only tools, the claim check and human approval limit the damage if injection reaches the model.
- Not built: WhatsApp notifications, Gmail push (IMAP polling only), load numbers.
