# East Bay Beer Tracker

A website that tracks the beers currently offered by East Bay breweries.

- **Public beer list** at `/` with filters: text search, brewery, style, ABV range, availability, and retired beers.
- **Daily scraping + LLM parsing**: every day (and on demand from the admin panel), the app fetches each brewery's configured URL(s), strips the HTML to text, and asks Claude (`claude-opus-4-8`, structured outputs) to extract the beer list. Beers are upserted; beers that disappear from a page are marked "no longer listed".
- **Passwordless email sign-in**: users enter their email and receive a 6-digit one-time code (no passwords). Signed-in users manage **alerts** at `/alerts` — keyword / brewery / style / ABV-range conditions. When a scrape finds a *new* matching beer, they get an email.
- **Admin panel** at `/admin`, restricted to `ADMIN_EMAIL` (default `andrewsunhwang@gmail.com`) via the same email-code sign-in: add/edit/delete breweries, manage their scrape URLs, trigger scrapes, and view scrape logs.

**Docs:** [Architecture & design](docs/ARCHITECTURE.md) · [Deployment guide](docs/DEPLOYMENT.md)

## Running

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

export ANTHROPIC_API_KEY=sk-ant-...   # required for scraping
# optionally configure SMTP_* (see .env.example); without SMTP_HOST,
# emails (including sign-in codes) are printed to the server log.

uvicorn app.main:app --host 0.0.0.0 --port 8000
```

On first boot the database is created at `data/beer_tracker.db` and seeded with a starter set of East Bay breweries (Fieldwork, Temescal, Ghost Town, Original Pattern, Drake's, East Brother, Ale Industries, Novel). **Review their scrape URLs in the admin panel** — brewery sites change; point each entry at the page that lists what's currently pouring/available, then hit "Scrape now".

## Configuration

All via environment variables — see [.env.example](.env.example). Key ones:

| Variable | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | — | Claude API key used by the scraper |
| `ADMIN_EMAIL` | `andrewsunhwang@gmail.com` | The only account that sees `/admin` |
| `SMTP_HOST` etc. | unset (log emails) | Outbound email for codes + alerts |
| `BASE_URL` | `http://localhost:8000` | Used in alert-email links |
| `SCRAPE_HOUR` | `4` | Daily scrape hour (server local time) |
| `CLAUDE_MODEL` | `claude-opus-4-8` | Parsing model |

## How scraping works

1. For each active brewery, each URL in its "Scrape URLs" list is fetched (`httpx`, browser-like UA, redirects followed).
2. HTML is reduced to readable text (scripts/styles stripped, capped at 80k chars).
3. Claude extracts a structured list of beers (name, style, ABV, description, availability) via the SDK's `messages.parse` with a Pydantic schema.
4. Beers are matched to existing rows by normalized name: existing beers are updated, brand-new beers are inserted (and trigger alert emails), and beers missing from the page are marked not current.
5. Every run is recorded in the scrape log visible in the admin panel.

Note: pages that render their beer list purely client-side (JavaScript) may come back empty; prefer URLs whose HTML contains the beer list, or a print/menu endpoint.

## Notes & limitations

- Sessions are signed cookies (30 days); login codes expire after 10 minutes, 5 attempts max.
- SQLite is the datastore — fine for this scale; back up `data/`.
- Form posts rely on same-site cookies (`SameSite=Lax`) rather than CSRF tokens.
