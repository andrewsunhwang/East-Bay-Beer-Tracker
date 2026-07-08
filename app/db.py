"""Database models and session helpers (SQLite via SQLAlchemy)."""

from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import (
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    Text,
    create_engine,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship, sessionmaker

from . import config


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    pass


class Brewery(Base):
    __tablename__ = "breweries"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(200), unique=True)
    location: Mapped[str] = mapped_column(String(200), default="")
    website: Mapped[str] = mapped_column(String(500), default="")
    # The specific page(s) scraped daily for the beer list. One URL per line.
    scrape_urls: Mapped[str] = mapped_column(Text, default="")
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    last_scraped_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_scrape_status: Mapped[str] = mapped_column(String(500), default="")

    beers: Mapped[list["Beer"]] = relationship(back_populates="brewery", cascade="all, delete-orphan")

    def url_list(self) -> list[str]:
        return [u.strip() for u in self.scrape_urls.splitlines() if u.strip()]


class Beer(Base):
    __tablename__ = "beers"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    brewery_id: Mapped[int] = mapped_column(ForeignKey("breweries.id"))
    name: Mapped[str] = mapped_column(String(300))
    style: Mapped[str] = mapped_column(String(200), default="")
    abv: Mapped[float | None] = mapped_column(Float, nullable=True)
    description: Mapped[str] = mapped_column(Text, default="")
    availability: Mapped[str] = mapped_column(String(100), default="")
    first_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    last_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    # False once the beer disappears from the brewery's page.
    is_current: Mapped[bool] = mapped_column(Boolean, default=True)

    brewery: Mapped[Brewery] = relationship(back_populates="beers")


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    email: Mapped[str] = mapped_column(String(320), unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    alerts: Mapped[list["Alert"]] = relationship(back_populates="user", cascade="all, delete-orphan")


class LoginCode(Base):
    __tablename__ = "login_codes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    email: Mapped[str] = mapped_column(String(320), index=True)
    code_hash: Mapped[str] = mapped_column(String(128))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    used: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Alert(Base):
    __tablename__ = "alerts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    name: Mapped[str] = mapped_column(String(200), default="")
    # All non-empty criteria must match a *new* beer for the alert to fire.
    keyword: Mapped[str] = mapped_column(String(200), default="")
    brewery_id: Mapped[int | None] = mapped_column(ForeignKey("breweries.id"), nullable=True)
    style: Mapped[str] = mapped_column(String(200), default="")
    min_abv: Mapped[float | None] = mapped_column(Float, nullable=True)
    max_abv: Mapped[float | None] = mapped_column(Float, nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    user: Mapped[User] = relationship(back_populates="alerts")
    brewery: Mapped[Brewery | None] = relationship()


class AlertNotification(Base):
    """Records which (alert, beer) pairs have already been emailed."""

    __tablename__ = "alert_notifications"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    alert_id: Mapped[int] = mapped_column(ForeignKey("alerts.id"))
    beer_id: Mapped[int] = mapped_column(ForeignKey("beers.id"))
    sent_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class ScrapeLog(Base):
    __tablename__ = "scrape_logs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    brewery_id: Mapped[int | None] = mapped_column(ForeignKey("breweries.id"), nullable=True)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    status: Mapped[str] = mapped_column(String(50), default="ok")  # ok | error
    detail: Mapped[str] = mapped_column(Text, default="")
    beers_found: Mapped[int] = mapped_column(Integer, default=0)
    new_beers: Mapped[int] = mapped_column(Integer, default=0)

    brewery: Mapped[Brewery | None] = relationship()


engine = create_engine(
    f"sqlite:///{config.DB_PATH}",
    connect_args={"check_same_thread": False},
)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def init_db() -> None:
    Base.metadata.create_all(engine)


SEED_BREWERIES = [
    ("Fieldwork Brewing Company", "Berkeley", "https://fieldworkbrewing.com", "https://fieldworkbrewing.com/menus/"),
    ("Temescal Brewing", "Oakland", "https://www.temescalbrewing.com", "https://www.temescalbrewing.com/whats-pouring"),
    ("Ghost Town Brewing", "Oakland", "https://ghosttownbrewing.com", "https://ghosttownbrewing.com/collections/beer"),
    ("Original Pattern Brewing", "Oakland", "https://www.originalpattern.com", "https://www.originalpattern.com/beer"),
    ("Drake's Brewing Company", "San Leandro", "https://drinkdrakes.com", "https://drinkdrakes.com/beers/"),
    ("East Brother Beer Co.", "Richmond", "https://eastbrotherbeer.com", "https://eastbrotherbeer.com/beer/"),
    ("Ale Industries", "Oakland", "https://aleindustries.com", "https://aleindustries.com/collections/beer"),
    ("Novel Brewing Company", "Oakland", "https://www.novelbrewing.com", "https://www.novelbrewing.com/on-tap"),
]


def seed_if_empty() -> None:
    """Seed a starter set of East Bay breweries on first boot. The admin panel
    is the source of truth for these URLs afterwards."""
    with SessionLocal() as db:
        if db.query(Brewery).count() > 0:
            return
        for name, location, website, scrape_url in SEED_BREWERIES:
            db.add(Brewery(name=name, location=location, website=website, scrape_urls=scrape_url))
        db.commit()
