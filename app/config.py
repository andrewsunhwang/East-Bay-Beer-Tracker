"""Application configuration, sourced from environment variables."""

import os
import secrets
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = Path(os.environ.get("DATA_DIR", BASE_DIR / "data"))
DATA_DIR.mkdir(parents=True, exist_ok=True)

# The single admin account. Only this email sees/uses the admin panel.
ADMIN_EMAIL = os.environ.get("ADMIN_EMAIL", "andrewsunhwang@gmail.com").strip().lower()

# Optional admin password. When set, the admin can sign in with this password
# at /admin/login (no email code needed — useful when SMTP is unavailable).
# Set this in your host's environment, never in the repo.
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "")

DB_PATH = os.environ.get("DB_PATH", str(DATA_DIR / "beer_tracker.db"))

# Base URL used in emails to link back to the site.
BASE_URL = os.environ.get("BASE_URL", "http://localhost:8000").rstrip("/")

# Hour of day (local server time, 0-23) at which the daily scrape runs.
SCRAPE_HOUR = int(os.environ.get("SCRAPE_HOUR", "4"))

# Claude model used for parsing brewery pages.
CLAUDE_MODEL = os.environ.get("CLAUDE_MODEL", "claude-sonnet-5")

# Max characters of page text sent to the LLM per source URL.
SCRAPE_TEXT_LIMIT = int(os.environ.get("SCRAPE_TEXT_LIMIT", "80000"))

# SMTP settings. If SMTP_HOST is unset, emails are printed to the server log
# (dev mode) instead of being sent.
SMTP_HOST = os.environ.get("SMTP_HOST", "")
SMTP_PORT = int(os.environ.get("SMTP_PORT", "587"))
SMTP_USER = os.environ.get("SMTP_USER", "")
SMTP_PASSWORD = os.environ.get("SMTP_PASSWORD", "")
SMTP_FROM = os.environ.get("SMTP_FROM", "East Bay Beer Tracker <no-reply@localhost>")
SMTP_STARTTLS = os.environ.get("SMTP_STARTTLS", "1") not in ("0", "false", "no")

LOGIN_CODE_TTL_MINUTES = 10
LOGIN_CODE_MAX_ATTEMPTS = 5
SESSION_MAX_AGE_SECONDS = 60 * 60 * 24 * 30  # 30 days


def _load_secret_key() -> str:
    """Use SECRET_KEY from the environment, else generate one and persist it
    so sessions survive restarts."""
    env_key = os.environ.get("SECRET_KEY")
    if env_key:
        return env_key
    key_file = DATA_DIR / ".secret_key"
    if key_file.exists():
        return key_file.read_text().strip()
    key = secrets.token_urlsafe(48)
    key_file.write_text(key)
    try:
        key_file.chmod(0o600)
    except OSError:
        pass
    return key


SECRET_KEY = _load_secret_key()
