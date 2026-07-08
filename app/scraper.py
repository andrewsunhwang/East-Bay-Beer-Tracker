"""Daily scraping pipeline: fetch brewery pages, parse beers with Claude,
upsert into the database, and email users whose alerts match new beers."""

from __future__ import annotations

import logging
import re
from typing import Optional

import anthropic
import httpx
from bs4 import BeautifulSoup
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from . import config, emailer
from .db import (
    Alert,
    AlertNotification,
    Beer,
    Brewery,
    ScrapeLog,
    SessionLocal,
    utcnow,
)

logger = logging.getLogger("beer_tracker.scraper")

USER_AGENT = (
    "Mozilla/5.0 (compatible; EastBayBeerTracker/1.0; +https://github.com/andrewsunhwang/east-bay-beer-tracker)"
)


class ParsedBeer(BaseModel):
    name: str = Field(description="The beer's name, without the brewery name prefix")
    style: Optional[str] = Field(
        default=None, description="Beer style, e.g. 'West Coast IPA', 'Czech Pilsner'"
    )
    abv: Optional[float] = Field(default=None, description="Alcohol by volume as a percentage, e.g. 6.8")
    description: Optional[str] = Field(default=None, description="Short description if present on the page")
    availability: Optional[str] = Field(
        default=None,
        description=(
            "How it's currently offered. Use one of: 'on tap', 'cans', 'bottles', "
            "'on tap + cans', 'coming soon', or null if unknown."
        ),
    )


class ParsedBeerList(BaseModel):
    beers: list[ParsedBeer] = Field(description="Every beer currently listed on the page")


def fetch_page_text(url: str) -> str:
    """Fetch a URL and reduce it to readable text for the LLM."""
    with httpx.Client(
        follow_redirects=True,
        timeout=30,
        headers={"User-Agent": USER_AGENT},
    ) as client:
        resp = client.get(url)
        resp.raise_for_status()
    soup = BeautifulSoup(resp.text, "html.parser")
    for tag in soup(["script", "style", "noscript", "svg", "iframe"]):
        tag.decompose()
    text = soup.get_text(separator="\n")
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = "\n".join(line.strip() for line in text.splitlines())
    return text[: config.SCRAPE_TEXT_LIMIT]


def parse_beers_with_llm(brewery_name: str, url: str, page_text: str) -> list[ParsedBeer]:
    """Extract the beer list from page text using Claude structured outputs."""
    client = anthropic.Anthropic()
    response = client.messages.parse(
        model=config.CLAUDE_MODEL,
        max_tokens=16000,
        system=(
            "You extract structured beer lists from the text of brewery web pages. "
            "Include only beers (and brewery-made ciders/seltzers/hard kombucha, noting that in style). "
            "Exclude merchandise, food, events, guest wines, and navigation text. "
            "If the same beer appears in multiple formats, return it once and combine availability. "
            "If the page contains no beer list, return an empty list."
        ),
        messages=[
            {
                "role": "user",
                "content": (
                    f"This is the text content of {url}, a page from the website of "
                    f"the brewery '{brewery_name}'. Extract every beer currently offered.\n\n"
                    f"<page_text>\n{page_text}\n</page_text>"
                ),
            }
        ],
        output_format=ParsedBeerList,
    )
    parsed = response.parsed_output
    return parsed.beers if parsed else []


def _norm_name(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", name.lower()).strip()


def scrape_brewery(db: Session, brewery: Brewery) -> ScrapeLog:
    """Scrape one brewery's source URLs and upsert its beers.

    Returns the ScrapeLog row (already committed). New beers get alert emails."""
    log = ScrapeLog(brewery_id=brewery.id)
    urls = brewery.url_list()
    if not urls:
        log.status = "error"
        log.detail = "No scrape URLs configured"
        _finish(db, brewery, log)
        return log

    parsed: dict[str, ParsedBeer] = {}
    errors: list[str] = []
    for url in urls:
        try:
            text = fetch_page_text(url)
            for beer in parse_beers_with_llm(brewery.name, url, text):
                key = _norm_name(beer.name)
                if not key:
                    continue
                if key in parsed:
                    existing = parsed[key]
                    # Merge: keep first non-empty value for each field.
                    existing.style = existing.style or beer.style
                    existing.abv = existing.abv if existing.abv is not None else beer.abv
                    existing.description = existing.description or beer.description
                    if beer.availability and beer.availability != existing.availability:
                        existing.availability = (
                            f"{existing.availability} + {beer.availability}"
                            if existing.availability
                            else beer.availability
                        )
                else:
                    parsed[key] = beer
        except Exception as exc:  # network, parse, or API failure for this URL
            logger.exception("Scrape failed for %s (%s)", brewery.name, url)
            errors.append(f"{url}: {exc}")

    if errors and not parsed:
        log.status = "error"
        log.detail = " | ".join(errors)[:2000]
        _finish(db, brewery, log)
        return log

    existing_beers = db.query(Beer).filter(Beer.brewery_id == brewery.id).all()
    existing_by_key = {_norm_name(b.name): b for b in existing_beers}
    now = utcnow()
    new_beers: list[Beer] = []
    seen_keys = set()

    for key, pb in parsed.items():
        seen_keys.add(key)
        beer = existing_by_key.get(key)
        if beer is None:
            beer = Beer(
                brewery_id=brewery.id,
                name=pb.name.strip(),
                style=(pb.style or "").strip(),
                abv=pb.abv,
                description=(pb.description or "").strip(),
                availability=(pb.availability or "").strip(),
                first_seen=now,
                last_seen=now,
                is_current=True,
            )
            db.add(beer)
            new_beers.append(beer)
        else:
            beer.style = (pb.style or beer.style or "").strip()
            beer.abv = pb.abv if pb.abv is not None else beer.abv
            beer.description = (pb.description or beer.description or "").strip()
            beer.availability = (pb.availability or "").strip()
            beer.last_seen = now
            beer.is_current = True

    # Anything previously current but missing from this scrape is retired.
    for key, beer in existing_by_key.items():
        if key not in seen_keys and beer.is_current:
            beer.is_current = False

    log.status = "ok" if not errors else "partial"
    log.detail = " | ".join(errors)[:2000] if errors else ""
    log.beers_found = len(parsed)
    log.new_beers = len(new_beers)
    _finish(db, brewery, log)

    if new_beers:
        try:
            notify_alerts(db, new_beers)
        except Exception:
            logger.exception("Alert notification failed for %s", brewery.name)
    return log


def _finish(db: Session, brewery: Brewery, log: ScrapeLog) -> None:
    brewery.last_scraped_at = utcnow()
    summary = log.status if log.status != "ok" else f"ok ({log.beers_found} beers, {log.new_beers} new)"
    brewery.last_scrape_status = summary[:500]
    db.add(log)
    db.commit()


def alert_matches(alert: Alert, beer: Beer) -> bool:
    if alert.brewery_id is not None and alert.brewery_id != beer.brewery_id:
        return False
    if alert.style and alert.style.lower() not in (beer.style or "").lower():
        return False
    if alert.keyword:
        haystack = f"{beer.name} {beer.style} {beer.description}".lower()
        if alert.keyword.lower() not in haystack:
            return False
    if alert.min_abv is not None and (beer.abv is None or beer.abv < alert.min_abv):
        return False
    if alert.max_abv is not None and (beer.abv is None or beer.abv > alert.max_abv):
        return False
    return True


def notify_alerts(db: Session, new_beers: list[Beer]) -> None:
    """Email each user whose active alerts match any of the new beers."""
    alerts = db.query(Alert).filter(Alert.is_active.is_(True)).all()
    per_user: dict[str, list[str]] = {}

    for alert in alerts:
        for beer in new_beers:
            if not alert_matches(alert, beer):
                continue
            already = (
                db.query(AlertNotification)
                .filter(AlertNotification.alert_id == alert.id, AlertNotification.beer_id == beer.id)
                .first()
            )
            if already:
                continue
            db.add(AlertNotification(alert_id=alert.id, beer_id=beer.id))
            abv = f" ({beer.abv}% ABV)" if beer.abv is not None else ""
            style = f" — {beer.style}" if beer.style else ""
            avail = f" [{beer.availability}]" if beer.availability else ""
            per_user.setdefault(alert.user.email, []).append(
                f"• {beer.name}{style}{abv}{avail} @ {beer.brewery.name}"
            )
    db.commit()

    for email, lines in per_user.items():
        # De-duplicate lines when multiple alerts match the same beer.
        unique_lines = list(dict.fromkeys(lines))
        try:
            emailer.send_alert_email(email, unique_lines)
        except Exception:
            logger.exception("Failed to send alert email to %s", email)


def scrape_all_breweries() -> None:
    """Entry point for the daily job and the admin 'scrape all' button."""
    with SessionLocal() as db:
        breweries = db.query(Brewery).filter(Brewery.is_active.is_(True)).all()
        logger.info("Starting scrape of %d breweries", len(breweries))
        for brewery in breweries:
            try:
                log = scrape_brewery(db, brewery)
                logger.info("Scraped %s: %s", brewery.name, log.status)
            except Exception:
                logger.exception("Unhandled scrape failure for %s", brewery.name)
                db.rollback()


def scrape_one_brewery(brewery_id: int) -> None:
    with SessionLocal() as db:
        brewery = db.get(Brewery, brewery_id)
        if brewery is None:
            return
        try:
            scrape_brewery(db, brewery)
        except Exception:
            logger.exception("Unhandled scrape failure for %s", brewery.name)
            db.rollback()
