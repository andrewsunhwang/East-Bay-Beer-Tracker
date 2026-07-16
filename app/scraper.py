"""Daily scraping pipeline: fetch brewery pages, parse beers with Claude,
upsert into the database, and email users whose alerts match new beers."""

from __future__ import annotations

import hashlib
import json
import logging
import re
from typing import Optional

import anthropic
import httpx
from bs4 import BeautifulSoup
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from . import config, emailer
from .styles import StyleFamily, classify_style_family
from .db import (
    Alert,
    AlertNotification,
    Beer,
    Brewery,
    ScrapeLog,
    SessionLocal,
    SourcePage,
    utcnow,
)

logger = logging.getLogger("beer_tracker.scraper")

# Browser-like headers: many brewery sites sit behind CDNs that 403 anything
# that doesn't look like a real browser. We fetch one page per brewery per day,
# so this is polite traffic regardless of the header set.
BROWSER_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Upgrade-Insecure-Requests": "1",
}


class PageTextEmpty(Exception):
    """The page fetched fine but contained almost no readable text."""


# Bump when text-extraction logic changes: it salts the cache hash, so cached
# parses made by older extraction code are invalidated and re-parsed even if
# the page bytes themselves haven't changed.
EXTRACTION_VERSION = 3


def _content_hash(text: str) -> str:
    return hashlib.sha256(f"v{EXTRACTION_VERSION}:{text}".encode("utf-8")).hexdigest()


_renderer_ok: bool | None = None


def renderer_available() -> bool:
    """Playwright + Chromium fallback available on this server?"""
    global _renderer_ok
    if not config.JS_RENDER:
        return False
    if _renderer_ok is None:
        try:
            import playwright.sync_api  # noqa: F401

            _renderer_ok = True
        except ImportError:
            _renderer_ok = False
            logger.warning(
                "playwright is not installed — JavaScript-rendered menus can't be scraped. "
                "Deploy with the provided Dockerfile (or `pip install playwright && playwright "
                "install --with-deps chromium`) to enable."
            )
    return _renderer_ok


def fetch_page_text_rendered(url: str) -> str:
    """Fetch a URL in headless Chromium so client-side JavaScript runs, then
    reduce the rendered DOM to text. Used for menus that load via XHR."""
    import os

    from playwright.sync_api import sync_playwright

    with sync_playwright() as p:
        browser = p.chromium.launch(
            # CHROMIUM_PATH lets deployments point at a system-installed
            # Chromium instead of Playwright's downloaded build.
            executable_path=os.environ.get("CHROMIUM_PATH") or None,
            args=["--no-sandbox", "--disable-dev-shm-usage"],
        )
        try:
            page = browser.new_page(
                user_agent=BROWSER_HEADERS["User-Agent"],
                viewport={"width": 1366, "height": 900},
            )
            page.goto(url, wait_until="domcontentloaded", timeout=30000)
            try:
                page.wait_for_load_state("networkidle", timeout=15000)
            except Exception:
                pass  # slow trackers etc. — proceed with whatever has rendered
            html = page.content()
        finally:
            browser.close()
    return html_to_text(html)


def friendly_error(exc: Exception) -> str:
    """Turn an exception from the fetch/parse pipeline into a message an
    admin can act on."""
    if isinstance(exc, PageTextEmpty):
        return (
            "the page loaded but contained almost no readable text — the menu is "
            "probably rendered by JavaScript. Point this brewery at a page whose "
            "HTML contains the beer list (a print/embed menu URL often works)"
        )
    if isinstance(exc, httpx.HTTPStatusError):
        code = exc.response.status_code
        if code in (401, 403):
            return (
                f"the site blocked our request (HTTP {code} — likely bot protection). "
                "Try a different page on the same site, or an embed/print menu URL"
            )
        if code == 404:
            return "page not found (HTTP 404) — the URL has probably changed; update it"
        if code == 429:
            return "the site rate-limited us (HTTP 429) — it may work on the next daily run"
        if code >= 500:
            return f"the brewery's website returned a server error (HTTP {code})"
        return f"unexpected response from the site (HTTP {code})"
    if isinstance(exc, httpx.TooManyRedirects):
        return "the URL redirects in a loop — check it in a browser and update it"
    if isinstance(exc, httpx.TimeoutException):
        return "the site took too long to respond (timeout)"
    if isinstance(exc, httpx.RequestError):
        return "couldn't reach the site (connection/DNS failure) — check the URL is correct"
    if isinstance(exc, anthropic.AuthenticationError):
        return "Claude API key is missing or invalid — set ANTHROPIC_API_KEY on the server"
    if isinstance(exc, anthropic.RateLimitError):
        return "Claude API rate limit hit — it should succeed on the next run"
    if isinstance(exc, anthropic.APIStatusError):
        return f"Claude API error while parsing the page ({exc.status_code})"
    if isinstance(exc, anthropic.APIConnectionError):
        return "couldn't reach the Claude API (network error from the server)"
    if "playwright" in type(exc).__module__:
        return f"browser rendering failed ({type(exc).__name__}) — will retry next run"
    if "api_key" in str(exc).lower():
        return "Claude API key is missing — set ANTHROPIC_API_KEY on the server"
    return f"unexpected error: {type(exc).__name__}: {exc}"


class ParsedBeer(BaseModel):
    name: str = Field(description="The beer's name, without the brewery name prefix")
    style: Optional[str] = Field(
        default=None, description="Beer style, e.g. 'West Coast IPA', 'Czech Pilsner'"
    )
    style_family: Optional[StyleFamily] = Field(
        default=None,
        description="The broad family this beer's style belongs to",
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


def _looks_noisy(s: str) -> bool:
    """Filter out JSON string values that are clearly not beer content:
    URLs, file paths, IDs/hashes, CSS, base64."""
    if s.startswith(("http://", "https://", "/", "#", "data:", "{", "<")):
        return True
    if " " not in s and ("/" in s or "." in s and len(s) > 20):  # paths, filenames
        return True
    if len(s) > 40 and " " not in s:  # long token with no spaces → hash/id/css
        return True
    return False


def _flatten_json_values(obj, out: list[str], seen: set[str], budget: list[int]) -> None:
    """Recursively collect human-readable leaf strings/numbers from parsed JSON.

    Modern sites (Next.js, Shopify, etc.) embed the beer list as JSON in a
    <script> tag. Flattening that JSON to its text leaves recovers the beer
    names, styles, ABVs, and descriptions while dropping structural noise."""
    if budget[0] <= 0:
        return
    if isinstance(obj, dict):
        for value in obj.values():
            _flatten_json_values(value, out, seen, budget)
    elif isinstance(obj, list):
        for value in obj:
            _flatten_json_values(value, out, seen, budget)
    elif isinstance(obj, str):
        s = obj.strip()
        if 1 <= len(s) <= 300 and s not in seen and not _looks_noisy(s):
            seen.add(s)
            out.append(s)
            budget[0] -= len(s) + 1
    elif isinstance(obj, (int, float)) and not isinstance(obj, bool):
        s = str(obj)
        if s not in seen:
            seen.add(s)
            out.append(s)
            budget[0] -= len(s) + 1


def _extract_structured_data(soup: BeautifulSoup, budget: int = 40000) -> str:
    """Pull beer data out of embedded JSON (<script> blobs) that plain text
    extraction would miss. Returns newline-joined text leaves."""
    scripts = (
        soup.find_all("script", attrs={"type": "application/ld+json"})
        + soup.find_all("script", id="__NEXT_DATA__")
        + soup.find_all("script", attrs={"type": "application/json"})
    )
    out: list[str] = []
    seen: set[str] = set()
    remaining = [budget]
    for script in scripts:
        raw = script.string or script.get_text()
        if not raw or not raw.strip():
            continue
        try:
            data = json.loads(raw)
        except ValueError:
            continue
        _flatten_json_values(data, out, seen, remaining)
        if remaining[0] <= 0:
            break
    return "\n".join(out)


def html_to_text(html: str) -> str:
    """Reduce an HTML page to readable text for the LLM, including any beer
    data embedded as JSON in <script> tags (which JS-rendered menus rely on)."""
    soup = BeautifulSoup(html, "html.parser")
    # Capture embedded JSON *before* stripping scripts.
    structured = _extract_structured_data(soup)
    for tag in soup(["script", "style", "noscript", "svg", "iframe"]):
        tag.decompose()
    text = soup.get_text(separator="\n")
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = "\n".join(line.strip() for line in text.splitlines())
    if structured:
        text = f"{text}\n\n--- structured data embedded in the page ---\n{structured}"
    return text[: config.SCRAPE_TEXT_LIMIT]


def fetch_page_text(url: str) -> str:
    """Fetch a URL and reduce it to readable text for the LLM."""
    with httpx.Client(
        follow_redirects=True,
        timeout=30,
        headers=BROWSER_HEADERS,
    ) as client:
        resp = client.get(url)
        resp.raise_for_status()
    return html_to_text(resp.text)


def parse_beers_with_llm(brewery_name: str, url: str, page_text: str) -> list[ParsedBeer]:
    """Extract the beer list from page text using Claude structured outputs."""
    client = anthropic.Anthropic()
    response = client.messages.parse(
        model=config.CLAUDE_MODEL,
        max_tokens=16000,
        system=(
            # Kept stable to maximize prompt-cache reuse across breweries.
            "You extract structured beer lists from the text of brewery web pages. "
            "Include only beers (and brewery-made ciders/seltzers/hard kombucha, noting that in style). "
            "Assign each beer's style_family from the allowed values based on its style. "
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

    pages = {p.url: p for p in db.query(SourcePage).filter(SourcePage.brewery_id == brewery.id)}
    parsed: dict[str, ParsedBeer] = {}
    errors: list[str] = []
    llm_calls = 0
    cached_hits = 0
    for url in urls:
        try:
            page = pages.get(url)
            # URLs known to need JS rendering skip the plain fetch entirely.
            rendered = bool(page is not None and page.needs_render and renderer_available())
            text = fetch_page_text_rendered(url) if rendered else fetch_page_text(url)

            if len(text.strip()) < 200:
                # Nearly-empty shell: render it before giving up.
                if not rendered and renderer_available():
                    text = fetch_page_text_rendered(url)
                    rendered = True
                if len(text.strip()) < 200:
                    raise PageTextEmpty(url)

            content_hash = _content_hash(text)
            # Reuse only non-empty cached parses: a cached 0-beer result is
            # retried every run (so a fixed URL or newly-installed renderer
            # can recover) rather than being trusted forever.
            if (
                page is not None
                and page.content_hash == content_hash
                and page.parsed_json not in ("", "[]")
            ):
                # Page identical to last successful parse — reuse it, no API call.
                beers = [ParsedBeer(**d) for d in json.loads(page.parsed_json)]
                page.fetched_at = utcnow()
                cached_hits += 1
            else:
                beers = parse_beers_with_llm(brewery.name, url, text)
                llm_calls += 1
                if not beers and not rendered and renderer_available():
                    # Plain fetch parsed to nothing — likely a JS-rendered menu.
                    # Render the page and try once more.
                    rendered_text = fetch_page_text_rendered(url)
                    if len(rendered_text.strip()) >= 200 and rendered_text != text:
                        rendered_beers = parse_beers_with_llm(brewery.name, url, rendered_text)
                        llm_calls += 1
                        if rendered_beers:
                            beers = rendered_beers
                            text = rendered_text
                            content_hash = _content_hash(rendered_text)
                            rendered = True
                if page is None:
                    page = SourcePage(brewery_id=brewery.id, url=url)
                    db.add(page)
                    pages[url] = page
                page.content_hash = content_hash
                page.parsed_json = json.dumps([b.model_dump() for b in beers])
                page.needs_render = rendered
                page.fetched_at = utcnow()
                page.parsed_at = utcnow()

            for beer in beers:
                key = _norm_name(beer.name)
                if not key:
                    continue
                if key in parsed:
                    existing = parsed[key]
                    # Merge: keep first non-empty value for each field.
                    existing.style = existing.style or beer.style
                    existing.style_family = existing.style_family or beer.style_family
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
            errors.append(f"{url}: {friendly_error(exc)}")

    # Drop cache rows for URLs no longer configured on this brewery.
    for stale_url, page in list(pages.items()):
        if stale_url not in urls:
            db.delete(page)

    if errors and not parsed:
        log.status = "error"
        log.detail = " | ".join(errors)[:2000]
        _finish(db, brewery, log)
        return log

    existing_beers = db.query(Beer).filter(Beer.brewery_id == brewery.id).all()
    existing_by_key = {_norm_name(b.name): b for b in existing_beers}

    # Safety guard: a page that loads but yields zero beers (redesign, outage
    # page, JS-rendered menu) must never silently retire the whole list — and
    # a 0-beer result is always surfaced as a warning, never a quiet "ok".
    if not parsed:
        log.status = "warning"
        if renderer_available():
            hint = (
                "No beers found even after JavaScript rendering — the URL may not "
                "be the menu page; check it in a browser and update it."
            )
        else:
            hint = (
                "No beers found. The menu is likely JavaScript-rendered and this "
                "server has no browser fallback installed (deploy with the "
                "provided Dockerfile to enable JS rendering)."
            )
        if any(b.is_current for b in existing_beers):
            hint += " Kept the existing beer list untouched."
        log.detail = hint
        _finish(db, brewery, log)
        return log
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
                style_family=pb.style_family or classify_style_family(pb.style, pb.name),
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
            beer.style_family = pb.style_family or beer.style_family or classify_style_family(beer.style, beer.name)
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
    if errors:
        log.detail = " | ".join(errors)[:2000]
    elif cached_hits and not llm_calls:
        log.detail = f"page{'s' if cached_hits != 1 else ''} unchanged — reused cached parse, no API calls"
    elif cached_hits:
        log.detail = f"{llm_calls} page(s) re-parsed, {cached_hits} unchanged (cached)"
    else:
        log.detail = ""
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
    if log.status == "ok":
        base = f"ok ({log.beers_found} beers, {log.new_beers} new)"
        summary = f"{base} — {log.detail}" if log.detail else base
    else:
        summary = f"{log.status}: {log.detail}" if log.detail else log.status
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
