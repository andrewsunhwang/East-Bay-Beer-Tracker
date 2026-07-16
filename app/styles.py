"""Canonical beer style families.

The LLM assigns one of these at scrape time; `classify_style_family` is the
keyword fallback used to backfill existing beers and to cover the rare case
where the LLM leaves the field empty.
"""

from __future__ import annotations

import re
from typing import Literal, get_args

StyleFamily = Literal[
    "IPA",
    "Pale Ale",
    "Lager & Pilsner",
    "Stout & Porter",
    "Sour & Wild",
    "Belgian & Farmhouse",
    "Wheat",
    "Amber, Red & Brown",
    "Strong & Barleywine",
    "Cider, Mead & Seltzer",
    "Other",
]

FAMILIES: tuple[str, ...] = get_args(StyleFamily)

# Checked in order — more specific families first.
_RULES: list[tuple[str, str]] = [
    (r"cider|mead|seltzer|kombucha|radler", "Cider, Mead & Seltzer"),
    (r"barley\s*wine|barleywine|old ale|wee heavy|strong ale|wheat\s*wine", "Strong & Barleywine"),
    (r"stout|porter", "Stout & Porter"),
    (r"sour|gose|berliner|lambic|wild|brett|kettle", "Sour & Wild"),
    (r"saison|farmhouse|belgian|tripel|dubbel|quad|abbey|witbier|\bwit\b|biere de garde|grisette", "Belgian & Farmhouse"),
    (r"hefeweizen|weizen|weisse|wheat", "Wheat"),
    (r"\bipa\b|india pale|hazy|neipa|dipa|tipa|cold ipa", "IPA"),
    (r"pale ale|\bxpa\b|\bapa\b|blonde|golden ale|cream ale", "Pale Ale"),
    (
        r"pilsner|\bpils\b|lager|helles|k[oö]lsch|m[aä]rzen|oktoberfest|festbier|"
        r"\bbock\b|schwarz|vienna|czech|dortmund|rauchbier|steam",
        "Lager & Pilsner",
    ),
    (r"amber|red ale|\bred\b|brown|\balt\b|\besb\b|bitter|scotch|scottish|irish", "Amber, Red & Brown"),
]


def classify_style_family(style: str | None, name: str = "") -> str:
    """Best-effort keyword classification of a free-text style string."""
    haystack = f"{style or ''} {name}".lower()
    if not haystack.strip():
        return "Other"
    for pattern, family in _RULES:
        if re.search(pattern, haystack):
            return family
    return "Other"
