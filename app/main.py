"""East Bay Beer Tracker — FastAPI application."""

from __future__ import annotations

import asyncio
import json
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import quote, urlencode

from fastapi import BackgroundTasks, Depends, FastAPI, Form, Request
from fastapi import Query as FastQuery
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from sqlalchemy import func, or_
from sqlalchemy.orm import Session, joinedload

from . import auth, config, scraper
from .db import Alert, Beer, Brewery, ScrapeLog, SessionLocal, init_db, seed_if_empty

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
logger = logging.getLogger("beer_tracker")

APP_DIR = Path(__file__).resolve().parent
templates = Jinja2Templates(directory=str(APP_DIR / "templates"))


def timeago(dt: datetime | None) -> str:
    """'12 minutes ago' for recent timestamps, a plain date for older ones."""
    if dt is None:
        return "never"
    if dt.tzinfo is None:  # SQLite drops tzinfo; stored values are UTC
        dt = dt.replace(tzinfo=timezone.utc)
    seconds = (datetime.now(timezone.utc) - dt).total_seconds()
    if seconds < 90:
        return "just now"
    if seconds < 3600:
        return f"{int(seconds // 60)} minutes ago"
    if seconds < 172800:  # under 2 days
        hours = int(seconds // 3600)
        return f"{hours} hour{'s' if hours != 1 else ''} ago"
    if seconds < 604800:  # under a week
        return f"{int(seconds // 86400)} days ago"
    return dt.strftime("%b %d, %Y")


def is_new(dt: datetime | None) -> bool:
    """True when a timestamp is within the last 7 days (for 'new' badges)."""
    if dt is None:
        return False
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return (datetime.now(timezone.utc) - dt) < timedelta(days=7)


templates.env.filters["timeago"] = timeago
templates.env.filters["is_new"] = is_new


async def daily_scrape_loop() -> None:
    """Sleep until the configured hour each day, then scrape everything."""
    while True:
        now = datetime.now()
        target = now.replace(hour=config.SCRAPE_HOUR, minute=0, second=0, microsecond=0)
        if target <= now:
            target += timedelta(days=1)
        wait = (target - now).total_seconds()
        logger.info("Next scheduled scrape at %s (%.0f s from now)", target, wait)
        await asyncio.sleep(wait)
        try:
            await asyncio.to_thread(scraper.scrape_all_breweries)
        except Exception:
            logger.exception("Scheduled scrape crashed")


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    seed_if_empty()
    task = asyncio.create_task(daily_scrape_loop())
    yield
    task.cancel()


app = FastAPI(title="East Bay Beer Tracker", lifespan=lifespan)
app.mount("/static", StaticFiles(directory=str(APP_DIR / "static")), name="static")


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def base_context(request: Request) -> dict:
    email = auth.read_session_email(request)
    return {
        "request": request,
        "user_email": email,
        "is_admin": auth.is_admin(email),
        "admin_password_enabled": auth.admin_password_enabled(),
        "msg": request.query_params.get("msg", ""),
        "error": request.query_params.get("error", ""),
    }


def redirect(path: str, msg: str = "", error: str = "") -> RedirectResponse:
    if msg:
        path += ("&" if "?" in path else "?") + "msg=" + quote(msg)
    if error:
        path += ("&" if "?" in path else "?") + "error=" + quote(error)
    return RedirectResponse(path, status_code=303)


# ---------------------------------------------------------------- public site


PREFS_COOKIE = "ebbt_prefs"

SORT_COLUMNS = {
    "name": Beer.name,
    "brewery": Brewery.name,
    "style": Beer.style,
    "abv": Beer.abv,
    "availability": Beer.availability,
    "added": Beer.first_seen,
}


def _index_url(state: dict, **overrides) -> str:
    """Build a beer-list URL from the current filter state with overrides."""
    params = {**state, **overrides}
    pairs = []
    for key, value in params.items():
        if isinstance(value, list):
            pairs.extend((key, item) for item in value)
        elif value:
            pairs.append((key, value))
    qs = urlencode(pairs)
    return "/?" + qs if qs else "/?clear=1"


@app.get("/", response_class=HTMLResponse)
def index(
    request: Request,
    db: Session = Depends(get_db),
    q: str = "",
    brewery_id: str = "",
    style: list[str] = FastQuery(default=[]),
    abv_min: str = "",
    abv_max: str = "",
    availability: str = "",
    show_retired: str = "",
    sort: str = "",
    dir: str = "",
    view: str = "",
    clear: str = "",
):
    # Remembered preferences (checked styles + view) live in a cookie the
    # server sets whenever filters are used. A bare visit to "/" restores
    # them; "/?clear=1" (the Reset link) wipes them.
    prefs = {}
    raw_prefs = request.cookies.get(PREFS_COOKIE)
    if raw_prefs:
        try:
            prefs = json.loads(raw_prefs)
        except ValueError:
            prefs = {}
    bare_visit = not request.url.query
    if bare_visit:
        style = [s for s in prefs.get("styles", []) if isinstance(s, str)]
    if not view:
        view = prefs.get("view", "tiles")
    if view not in ("tiles", "list"):
        view = "tiles"
    if clear:
        style = []

    query = db.query(Beer).options(joinedload(Beer.brewery)).join(Brewery)
    if not show_retired:
        query = query.filter(Beer.is_current.is_(True))
    if q.strip():
        like = f"%{q.strip()}%"
        query = query.filter(
            or_(Beer.name.ilike(like), Beer.style.ilike(like), Beer.description.ilike(like))
        )
    if brewery_id.isdigit():
        query = query.filter(Beer.brewery_id == int(brewery_id))
    style = [s for s in style if s.strip()]
    if style:
        query = query.filter(Beer.style.in_(style))
    try:
        if abv_min.strip():
            query = query.filter(Beer.abv >= float(abv_min))
        if abv_max.strip():
            query = query.filter(Beer.abv <= float(abv_max))
    except ValueError:
        pass
    if availability.strip():
        query = query.filter(Beer.availability.ilike(f"%{availability.strip()}%"))

    sort_col = SORT_COLUMNS.get(sort)
    direction = "desc" if dir == "desc" else "asc"
    if sort_col is not None:
        primary = sort_col.desc() if direction == "desc" else sort_col.asc()
        beers = query.order_by(primary, Brewery.name, Beer.name).all()
    else:
        beers = query.order_by(Brewery.name, Beer.name).all()

    breweries = db.query(Brewery).order_by(Brewery.name).all()
    style_counts = dict(
        db.query(Beer.style, func.count(Beer.id))
        .filter(Beer.is_current.is_(True), Beer.style != "")
        .group_by(Beer.style)
        .all()
    )
    styles = sorted({s for (s,) in db.query(Beer.style).distinct() if s})

    state = {
        "q": q.strip(),
        "brewery_id": brewery_id if brewery_id.isdigit() else "",
        "style": style,
        "abv_min": abv_min.strip(),
        "abv_max": abv_max.strip(),
        "availability": availability.strip(),
        "show_retired": show_retired,
        "sort": sort if sort_col is not None else "",
        "dir": direction if sort_col is not None else "",
        "view": view,
    }
    # Column-header links: clicking the active column flips direction.
    sort_urls, sort_marks = {}, {}
    for col in SORT_COLUMNS:
        if state["sort"] == col and direction == "asc":
            sort_urls[col] = _index_url(state, sort=col, dir="desc")
        else:
            sort_urls[col] = _index_url(state, sort=col, dir="asc")
        sort_marks[col] = (" ▲" if direction == "asc" else " ▼") if state["sort"] == col else ""
    view_urls = {
        "tiles": _index_url(state, view="tiles"),
        "list": _index_url(state, view="list"),
    }

    ctx = base_context(request)
    ctx.update(
        beers=beers,
        breweries=breweries,
        styles=styles,
        style_counts=style_counts,
        filters=state,
        view=view,
        sort_urls=sort_urls,
        sort_marks=sort_marks,
        view_urls=view_urls,
    )
    resp = templates.TemplateResponse(request, "index.html", ctx)
    if clear:
        resp.delete_cookie(PREFS_COOKIE)
    elif not bare_visit:
        resp.set_cookie(
            PREFS_COOKIE,
            json.dumps({"styles": style, "view": view}),
            max_age=180 * 24 * 3600,
            samesite="lax",
        )
    return resp


@app.get("/breweries", response_class=HTMLResponse)
def breweries_page(request: Request, db: Session = Depends(get_db)):
    breweries = db.query(Brewery).order_by(Brewery.name).all()
    current_counts = dict(
        db.query(Beer.brewery_id, func.count(Beer.id))
        .filter(Beer.is_current.is_(True))
        .group_by(Beer.brewery_id)
        .all()
    )
    ctx = base_context(request)
    ctx.update(breweries=breweries, current_counts=current_counts)
    return templates.TemplateResponse(request, "breweries.html", ctx)


# --------------------------------------------------------------------- auth


@app.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    ctx = base_context(request)
    ctx["next"] = request.query_params.get("next", "/")
    return templates.TemplateResponse(request, "login.html", ctx)


@app.post("/login/request", response_class=HTMLResponse)
def login_request(
    request: Request,
    db: Session = Depends(get_db),
    email: str = Form(...),
    next: str = Form("/"),
):
    email = auth.normalize_email(email)
    if not auth.valid_email(email):
        return redirect("/login", error="Please enter a valid email address.")
    try:
        auth.request_login_code(db, email)
    except Exception:
        logger.exception("Failed to send sign-in code to %s", email)
        return redirect(
            "/login",
            error="We couldn't send the sign-in code — the email service may be "
            "misconfigured. Please try again later or contact the site admin.",
        )
    ctx = base_context(request)
    ctx.update(email=email, next=next, code_sent=True)
    return templates.TemplateResponse(request, "login.html", ctx)


@app.post("/login/verify")
def login_verify(
    request: Request,
    db: Session = Depends(get_db),
    email: str = Form(...),
    code: str = Form(...),
    next: str = Form("/"),
):
    email = auth.normalize_email(email)
    if not auth.valid_email(email) or not auth.verify_login_code(db, email, code):
        return redirect("/login", error="That code didn't work. Request a new one.")
    auth.get_or_create_user(db, email)
    if not next.startswith("/") or next.startswith("//"):
        next = "/"
    resp = redirect(next, msg="Signed in as " + email)
    resp.set_cookie(
        auth.SESSION_COOKIE,
        auth.make_session_token(email),
        max_age=config.SESSION_MAX_AGE_SECONDS,
        httponly=True,
        samesite="lax",
    )
    return resp


@app.post("/logout")
def logout():
    resp = redirect("/", msg="Signed out.")
    resp.delete_cookie(auth.SESSION_COOKIE)
    return resp


@app.get("/admin/login", response_class=HTMLResponse)
def admin_login_page(request: Request):
    if not auth.admin_password_enabled():
        return redirect("/login", error="Admin password login is not enabled on this server.")
    return templates.TemplateResponse(request, "admin_login.html", base_context(request))


@app.post("/admin/login")
def admin_login(
    request: Request,
    db: Session = Depends(get_db),
    password: str = Form(...),
):
    if not auth.admin_password_enabled() or not auth.verify_admin_password(password):
        return redirect("/admin/login", error="Incorrect admin password.")
    auth.get_or_create_user(db, config.ADMIN_EMAIL)
    resp = redirect("/admin", msg="Signed in as admin.")
    resp.set_cookie(
        auth.SESSION_COOKIE,
        auth.make_session_token(config.ADMIN_EMAIL),
        max_age=config.SESSION_MAX_AGE_SECONDS,
        httponly=True,
        samesite="lax",
    )
    return resp


def require_user(request: Request, db: Session):
    email = auth.read_session_email(request)
    if not email:
        return None
    return auth.get_or_create_user(db, email)


# -------------------------------------------------------------------- alerts


@app.get("/alerts", response_class=HTMLResponse)
def alerts_page(request: Request, db: Session = Depends(get_db)):
    user = require_user(request, db)
    if user is None:
        return redirect("/login?next=/alerts")
    alerts = (
        db.query(Alert)
        .options(joinedload(Alert.brewery))
        .filter(Alert.user_id == user.id)
        .order_by(Alert.id.desc())
        .all()
    )
    breweries = db.query(Brewery).order_by(Brewery.name).all()
    ctx = base_context(request)
    ctx.update(alerts=alerts, breweries=breweries)
    return templates.TemplateResponse(request, "alerts.html", ctx)


@app.post("/alerts/create")
def alert_create(
    request: Request,
    db: Session = Depends(get_db),
    name: str = Form(""),
    keyword: str = Form(""),
    brewery_id: str = Form(""),
    style: str = Form(""),
    min_abv: str = Form(""),
    max_abv: str = Form(""),
):
    user = require_user(request, db)
    if user is None:
        return redirect("/login?next=/alerts")

    def parse_float(value: str):
        try:
            return float(value) if value.strip() else None
        except ValueError:
            return None

    alert = Alert(
        user_id=user.id,
        name=name.strip()[:200],
        keyword=keyword.strip()[:200],
        brewery_id=int(brewery_id) if brewery_id.isdigit() else None,
        style=style.strip()[:200],
        min_abv=parse_float(min_abv),
        max_abv=parse_float(max_abv),
    )
    if not (alert.keyword or alert.brewery_id or alert.style or alert.min_abv is not None or alert.max_abv is not None):
        return redirect("/alerts", error="Set at least one condition for the alert.")
    db.add(alert)
    db.commit()
    return redirect("/alerts", msg="Alert created. We'll email you when new beers match.")


@app.post("/alerts/{alert_id}/delete")
def alert_delete(alert_id: int, request: Request, db: Session = Depends(get_db)):
    user = require_user(request, db)
    if user is None:
        return redirect("/login?next=/alerts")
    alert = db.get(Alert, alert_id)
    if alert and alert.user_id == user.id:
        db.delete(alert)
        db.commit()
    return redirect("/alerts", msg="Alert deleted.")


@app.post("/alerts/{alert_id}/toggle")
def alert_toggle(alert_id: int, request: Request, db: Session = Depends(get_db)):
    user = require_user(request, db)
    if user is None:
        return redirect("/login?next=/alerts")
    alert = db.get(Alert, alert_id)
    if alert and alert.user_id == user.id:
        alert.is_active = not alert.is_active
        db.commit()
    return redirect("/alerts")


# --------------------------------------------------------------------- admin


def require_admin(request: Request):
    email = auth.read_session_email(request)
    return auth.is_admin(email)


@app.get("/admin", response_class=HTMLResponse)
def admin_page(request: Request, db: Session = Depends(get_db)):
    if not require_admin(request):
        target = "/admin/login" if auth.admin_password_enabled() else "/login?next=/admin"
        return redirect(target, error="Admin sign-in required.")
    breweries = db.query(Brewery).order_by(Brewery.name).all()
    logs = (
        db.query(ScrapeLog)
        .options(joinedload(ScrapeLog.brewery))
        .order_by(ScrapeLog.id.desc())
        .limit(30)
        .all()
    )
    ctx = base_context(request)
    ctx.update(breweries=breweries, logs=logs)
    return templates.TemplateResponse(request, "admin.html", ctx)


@app.post("/admin/breweries/create")
def brewery_create(
    request: Request,
    db: Session = Depends(get_db),
    name: str = Form(...),
    location: str = Form(""),
    website: str = Form(""),
    scrape_urls: str = Form(""),
):
    if not require_admin(request):
        return redirect("/login?next=/admin")
    name = name.strip()
    if not name:
        return redirect("/admin", error="Brewery name is required.")
    if db.query(Brewery).filter(Brewery.name == name).first():
        return redirect("/admin", error=f"'{name}' already exists.")
    db.add(
        Brewery(
            name=name,
            location=location.strip(),
            website=website.strip(),
            scrape_urls=scrape_urls.strip(),
        )
    )
    db.commit()
    return redirect("/admin", msg=f"Added {name}.")


@app.post("/admin/breweries/{brewery_id}/update")
def brewery_update(
    brewery_id: int,
    request: Request,
    db: Session = Depends(get_db),
    name: str = Form(...),
    location: str = Form(""),
    website: str = Form(""),
    scrape_urls: str = Form(""),
    is_active: str = Form(""),
):
    if not require_admin(request):
        return redirect("/login?next=/admin")
    brewery = db.get(Brewery, brewery_id)
    if brewery is None:
        return redirect("/admin", error="Brewery not found.")
    brewery.name = name.strip() or brewery.name
    brewery.location = location.strip()
    brewery.website = website.strip()
    brewery.scrape_urls = scrape_urls.strip()
    brewery.is_active = bool(is_active)
    db.commit()
    return redirect("/admin", msg=f"Saved {brewery.name}.")


@app.post("/admin/breweries/{brewery_id}/delete")
def brewery_delete(brewery_id: int, request: Request, db: Session = Depends(get_db)):
    if not require_admin(request):
        return redirect("/login?next=/admin")
    brewery = db.get(Brewery, brewery_id)
    if brewery is not None:
        db.query(ScrapeLog).filter(ScrapeLog.brewery_id == brewery_id).delete()
        db.delete(brewery)
        db.commit()
        return redirect("/admin", msg=f"Deleted {brewery.name} and its beers.")
    return redirect("/admin", error="Brewery not found.")


@app.post("/admin/breweries/{brewery_id}/scrape")
def brewery_scrape(
    brewery_id: int,
    request: Request,
    background: BackgroundTasks,
    db: Session = Depends(get_db),
):
    if not require_admin(request):
        return redirect("/login?next=/admin")
    brewery = db.get(Brewery, brewery_id)
    if brewery is None:
        return redirect("/admin", error="Brewery not found.")
    background.add_task(scraper.scrape_one_brewery, brewery_id)
    return redirect("/admin", msg=f"Scrape of {brewery.name} started — refresh in a minute.")


@app.post("/admin/scrape-all")
def scrape_all(request: Request, background: BackgroundTasks):
    if not require_admin(request):
        return redirect("/login?next=/admin")
    background.add_task(scraper.scrape_all_breweries)
    return redirect("/admin", msg="Full scrape started — refresh in a few minutes.")
