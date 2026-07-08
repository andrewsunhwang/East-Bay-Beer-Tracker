"""Outbound email. Falls back to logging when SMTP is not configured."""

import logging
import smtplib
from email.message import EmailMessage

from . import config

logger = logging.getLogger("beer_tracker.email")


def send_email(to: str, subject: str, body: str) -> None:
    if not config.SMTP_HOST:
        logger.warning(
            "\n===== DEV EMAIL (SMTP not configured) =====\nTo: %s\nSubject: %s\n\n%s\n===========================================",
            to,
            subject,
            body,
        )
        return

    msg = EmailMessage()
    msg["From"] = config.SMTP_FROM
    msg["To"] = to
    msg["Subject"] = subject
    msg.set_content(body)

    with smtplib.SMTP(config.SMTP_HOST, config.SMTP_PORT, timeout=30) as smtp:
        if config.SMTP_STARTTLS:
            smtp.starttls()
        if config.SMTP_USER:
            smtp.login(config.SMTP_USER, config.SMTP_PASSWORD)
        smtp.send_message(msg)


def send_login_code(to: str, code: str) -> None:
    send_email(
        to,
        "Your East Bay Beer Tracker sign-in code",
        f"Your sign-in code is: {code}\n\n"
        f"It expires in {config.LOGIN_CODE_TTL_MINUTES} minutes. "
        "If you didn't request this, you can ignore this email.",
    )


def send_alert_email(to: str, lines: list[str]) -> None:
    body = (
        "New beers matching your alerts just landed:\n\n"
        + "\n".join(lines)
        + f"\n\nManage your alerts: {config.BASE_URL}/alerts\n"
    )
    send_email(to, "New beers matched your alerts \N{CLINKING BEER MUGS}", body)
