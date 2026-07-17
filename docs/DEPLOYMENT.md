# Deployment

How to run East Bay Beer Tracker in production.

## Requirements

The app is a **single long-running Python process** (the daily scraper runs
inside it) that stores everything in **SQLite on local disk**. So you need:

- A host that runs a persistent process — a VPS, a container platform with
  always-on instances, or a home server. Serverless platforms (Vercel,
  Netlify, Cloudflare Workers, AWS Lambda) will **not** work: no always-on
  process for the scheduler, no persistent filesystem for SQLite.
- A **persistent `data/` directory** — it holds the database and the
  auto-generated session-signing key. On container platforms, mount a volume
  there (or set `DATA_DIR` to the mount path).
- **Exactly one instance.** SQLite has a single writer and the scheduler
  assumes one copy of the app. Don't scale horizontally.
- Outbound HTTPS (brewery sites + Claude API) and outbound SMTP.
- Python 3.11+.

## Configuration

Everything is environment variables (see [.env.example](../.env.example)):

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `ANTHROPIC_API_KEY` | for scraping | — | Claude API key used to parse brewery pages |
| `ADMIN_EMAIL` | for admin | — | The only account that gets the admin panel. No default — admin features are disabled if unset |
| `ADMIN_PASSWORD` | no | unset | Lets the admin sign in at `/admin/login` with a password instead of an emailed code. Set only as a host secret — never in the repo. Leave unset to disable |
| `BASE_URL` | production | `http://localhost:8000` | Public URL, used in alert-email links |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASSWORD` / `SMTP_FROM` | production | unset | Outbound email. **Unset `SMTP_HOST` = dev mode: emails (including sign-in codes) are printed to the server log**, so real SMTP is effectively required in production |
| `SMTP_STARTTLS` | no | `1` | Set `0` to disable STARTTLS |
| `SCRAPE_HOUR` | no | `4` | Hour (0–23, server local time) of the daily scrape |
| `CLAUDE_MODEL` | no | `claude-sonnet-5` | Parsing model — see cost notes below |
| `JS_RENDER` | no | `1` | Headless-Chromium fallback for JavaScript-rendered menus (needs Playwright + Chromium, included in the Dockerfile). Set `0` to disable |
| `CHROMIUM_PATH` | no | unset | Path to a system Chromium binary, if not using Playwright's downloaded build |
| `SECRET_KEY` | no | auto-generated | Session signing key; auto-persisted to `data/.secret_key` if unset. Rotating it signs everyone out |
| `DATA_DIR` / `DB_PATH` | no | `./data` | Where the DB and secret key live |
| `SCRAPE_TEXT_LIMIT` | no | `80000` | Max page characters sent to the LLM |

For email without your own mail server: Amazon SES, Mailgun, Resend (SMTP
mode), or a Gmail app password all work — you just need SMTP credentials.

## Option 1 — VPS with systemd + Caddy (recommended)

Any $4–6/month instance (Hetzner, DigitalOcean, Linode, Lightsail) is plenty.

```bash
# as a non-root user, e.g. /opt/beertracker
sudo useradd -r -m -d /opt/beertracker beertracker
sudo -u beertracker git clone https://github.com/andrewsunhwang/East-Bay-Beer-Tracker /opt/beertracker/app
cd /opt/beertracker/app
sudo -u beertracker python3 -m venv .venv
sudo -u beertracker .venv/bin/pip install -r requirements.txt
```

Store secrets in an env file only root can read:

```bash
# /etc/beertracker.env
ANTHROPIC_API_KEY=sk-ant-...
BASE_URL=https://beer.example.com
SMTP_HOST=email-smtp.us-west-2.amazonaws.com
SMTP_USER=...
SMTP_PASSWORD=...
SMTP_FROM="East Bay Beer Tracker <beer@example.com>"
```

```bash
sudo chmod 600 /etc/beertracker.env
```

Systemd unit:

```ini
# /etc/systemd/system/beertracker.service
[Unit]
Description=East Bay Beer Tracker
After=network-online.target
Wants=network-online.target

[Service]
User=beertracker
WorkingDirectory=/opt/beertracker/app
EnvironmentFile=/etc/beertracker.env
ExecStart=/opt/beertracker/app/.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now beertracker
journalctl -u beertracker -f   # watch logs (dev-mode emails appear here too)
```

HTTPS with [Caddy](https://caddyserver.com) (automatic Let's Encrypt certs):

```
# /etc/caddy/Caddyfile
beer.example.com {
    reverse_proxy 127.0.0.1:8000
}
```

Point your domain's DNS at the server, `sudo systemctl reload caddy`, done.

**Updating:** `git pull && .venv/bin/pip install -r requirements.txt && sudo systemctl restart beertracker`.

## Option 2 — Docker / Fly.io / Railway

The repo ships a [Dockerfile](../Dockerfile) that installs the app **plus
Playwright and headless Chromium** — the browser is what lets the scraper
handle JavaScript-rendered menus (Fieldwork, Ghost Town, and most modern
brewery sites). Expect a ~1 GB image and a slower first build; that's the
Chromium payload and it's worth it.

Platform notes:

- **Railway**: with the Dockerfile in the repo root, Railway automatically
  builds with it instead of Nixpacks — a custom start command is no longer
  needed (the image binds to Railway's `$PORT` on its own). Attach a volume,
  mount it at `/data` (the image already sets `DATA_DIR=/data`); env vars in
  the service settings.
- **Fly.io**: create a volume (`fly volumes create data --size 1`) and mount
  it at `/data`; set secrets with `fly secrets set ANTHROPIC_API_KEY=...`.
  Keep `min_machines_running = 1` and `auto_stop_machines = false` — if the
  machine sleeps, the daily scrape won't run.
- **Render**: persistent disks require a paid instance type; mount at `/data`
  and set `DATA_DIR=/data`.

In all cases: one instance, volume mounted, `DATA_DIR` pointing at it.

Running without the Dockerfile (e.g. bare `pip install` on a VPS)? The app
works fine, but JS-rendered menus scrape empty unless you also run
`playwright install --with-deps chromium` (or set `CHROMIUM_PATH` to a
system Chromium). The scrape log tells you when a brewery needs it.

## Option 3 — Home server / Raspberry Pi

Traffic is tiny, so a Pi or spare machine works. Use the systemd setup from
Option 1, then expose it with **Cloudflare Tunnel** or **Tailscale Funnel**
for public HTTPS without opening router ports. Set `BASE_URL` to the tunnel
hostname.

## First boot checklist

1. Visit the site — the DB is created and seeded with 8 East Bay breweries.
2. Sign in with the admin email (`/login`). If SMTP isn't configured yet, the
   code is in the server log.
3. Open **Admin** and review each seeded brewery's scrape URLs — brewery
   sites change; point each at the page that lists what's currently
   pouring/available. Prefer pages whose HTML contains the beer list
   (JavaScript-only menus scrape empty).
4. Click **Scrape all breweries now**, refresh after a few minutes, and check
   the scrape log at the bottom of the admin page.
5. Send yourself a test sign-in email from a second account to confirm SMTP.

## Operations

- **Backups**: everything lives in `data/` — copy that directory (e.g. a
  nightly `sqlite3 data/beer_tracker.db ".backup ..."` or plain file copy to
  object storage). Losing `.secret_key` just signs users out; losing the DB
  loses breweries/users/alerts.
- **Monitoring**: the admin page shows per-brewery last-scrape status and the
  30 most recent scrape-log rows. `error`/`partial` statuses with detail
  strings are your first stop; the systemd journal has full tracebacks.
- **Scrape cost**: a brewery page costs one Claude call **only when its text
  has changed** since the last scrape — unchanged pages are served from the
  `SourcePage` cache with no API call (the admin log notes this). The default
  model is `claude-sonnet-5`; on the days a page actually changes, expect a
  few cents per brewery. In steady state, most daily scrapes make zero API
  calls. `CLAUDE_MODEL` can be set to `claude-opus-4-8` if you want to
  spot-check extraction quality on a tricky menu.
- **Logs**: standard Python logging to stdout/journal. In dev mode (no
  SMTP_HOST) every "sent" email is logged in full — don't run production
  that way, since sign-in codes would sit in the logs.
- **Restarts** are safe at any time: sessions are stateless cookies, and an
  interrupted scrape simply completes on the next run.
