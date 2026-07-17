"""Passwordless email-code authentication and signed-cookie sessions."""

from __future__ import annotations

import hashlib
import hmac
import re
import secrets
import time
from datetime import datetime, timedelta, timezone

from fastapi import Request
from itsdangerous import BadSignature, URLSafeTimedSerializer
from sqlalchemy.orm import Session

from . import config, emailer
from .db import LoginCode, User, utcnow

SESSION_COOKIE = "ebbt_session"

_serializer = URLSafeTimedSerializer(config.SECRET_KEY, salt="ebbt-session")

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def normalize_email(email: str) -> str:
    return email.strip().lower()


def valid_email(email: str) -> bool:
    return bool(EMAIL_RE.match(email)) and len(email) <= 320


def _hash_code(email: str, code: str) -> str:
    return hashlib.sha256(f"{email}:{code}:{config.SECRET_KEY}".encode()).hexdigest()


# --- rate limiting for code requests (protects the SMTP account from abuse)
RATE_WINDOW_MINUTES = 15
MAX_CODES_PER_EMAIL = 3  # per window, tracked in the DB (survives restarts)
MAX_REQUESTS_PER_IP = 12  # per window, in-memory (resets on restart — fine)

_ip_hits: dict[str, list[float]] = {}


def client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:  # Railway/most proxies put the real client first
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def code_request_rate_limited(db: Session, email: str, ip: str) -> bool:
    """True when this email or IP has requested too many codes recently."""
    now = time.monotonic()
    window_start = now - RATE_WINDOW_MINUTES * 60
    hits = [t for t in _ip_hits.get(ip, []) if t > window_start]
    if len(hits) >= MAX_REQUESTS_PER_IP:
        _ip_hits[ip] = hits
        return True
    hits.append(now)
    _ip_hits[ip] = hits
    if len(_ip_hits) > 5000:  # bound memory under address-spraying
        for key in [k for k, v in _ip_hits.items() if not v or v[-1] <= window_start]:
            _ip_hits.pop(key, None)

    cutoff = utcnow() - timedelta(minutes=RATE_WINDOW_MINUTES)
    recent = (
        db.query(LoginCode)
        .filter(LoginCode.email == email, LoginCode.created_at >= cutoff)
        .count()
    )
    return recent >= MAX_CODES_PER_EMAIL


def request_login_code(db: Session, email: str) -> None:
    """Create a fresh 6-digit code for the email and send it."""
    code = f"{secrets.randbelow(1_000_000):06d}"
    # Invalidate outstanding codes for this email.
    db.query(LoginCode).filter(LoginCode.email == email, LoginCode.used.is_(False)).update(
        {LoginCode.used: True}
    )
    db.add(
        LoginCode(
            email=email,
            code_hash=_hash_code(email, code),
            expires_at=utcnow() + timedelta(minutes=config.LOGIN_CODE_TTL_MINUTES),
        )
    )
    db.commit()
    emailer.send_login_code(email, code)


def verify_login_code(db: Session, email: str, code: str) -> bool:
    """Check the code; on success marks it used and returns True."""
    record = (
        db.query(LoginCode)
        .filter(LoginCode.email == email, LoginCode.used.is_(False))
        .order_by(LoginCode.id.desc())
        .first()
    )
    if record is None:
        return False
    expires = record.expires_at
    if expires.tzinfo is None:  # SQLite drops tzinfo
        expires = expires.replace(tzinfo=timezone.utc)
    if expires < utcnow() or record.attempts >= config.LOGIN_CODE_MAX_ATTEMPTS:
        return False
    record.attempts += 1
    ok = hmac.compare_digest(record.code_hash, _hash_code(email, code.strip()))
    if ok:
        record.used = True
    db.commit()
    return ok


def get_or_create_user(db: Session, email: str) -> User:
    user = db.query(User).filter(User.email == email).first()
    if user is None:
        user = User(email=email)
        db.add(user)
        db.commit()
    return user


def make_session_token(email: str) -> str:
    return _serializer.dumps({"email": email})


def read_session_email(request: Request) -> str | None:
    token = request.cookies.get(SESSION_COOKIE)
    if not token:
        return None
    try:
        data = _serializer.loads(token, max_age=config.SESSION_MAX_AGE_SECONDS)
    except BadSignature:
        return None
    email = data.get("email")
    return email if isinstance(email, str) else None


def is_admin(email: str | None) -> bool:
    # The ADMIN_EMAIL guard matters: with it unset, nobody is admin (rather
    # than an empty-string session matching an empty config value).
    return bool(config.ADMIN_EMAIL) and email == config.ADMIN_EMAIL


def admin_password_enabled() -> bool:
    return bool(config.ADMIN_PASSWORD)


def verify_admin_password(password: str) -> bool:
    """Constant-time check against the ADMIN_PASSWORD env var."""
    if not config.ADMIN_PASSWORD:
        return False
    return hmac.compare_digest(password, config.ADMIN_PASSWORD)
