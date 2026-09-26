"""Kitten of the Day and Puppy of the Day.

Photos come from The Cat API and the Dog CEO API (both free, no key needed).
A pet is chosen once per UTC day and then kept for the rest of that day, so
everyone sees the same kitten and puppy no matter how often the scraper runs.
Names and captions are picked deterministically from the date.
"""

from __future__ import annotations

import hashlib
import logging
from datetime import date

import requests

log = logging.getLogger(__name__)

CAT_API = "https://api.thecatapi.com/v1/images/search?mime_types=jpg,png"
CATAAS_API = "https://cataas.com/cat?json=true"
DOG_API = "https://dog.ceo/api/breeds/image/random"

KITTEN_NAMES = [
    "Biscuit", "Mochi", "Pumpkin", "Pickles", "Waffles", "Noodle", "Clementine", "Pepper",
    "Marshmallow", "Toffee", "Ziggy", "Olive", "Button", "Luna", "Miso", "Pudding",
    "Sprout", "Tofu", "Nutmeg", "Beans", "Poppy", "Muffin", "Juniper", "Pistachio",
]
PUPPY_NAMES = [
    "Barnaby", "Waffles", "Pretzel", "Maple", "Rolo", "Bear", "Hazel", "Scout",
    "Nugget", "Daisy", "Otis", "Peanut", "Pancake", "Rosie", "Gus", "Honey",
    "Teddy", "Bingo", "Fudge", "Winnie", "Chip", "Dumpling", "Ollie", "Bramble",
]
KITTEN_CAPTIONS = [
    "Has officially approved your plans for today.",
    "Professional napper, part-time sunbeam inspector.",
    "Would like to remind you to stretch before starting your day.",
    "Currently accepting chin scratches. No appointment needed.",
    "Believes today is going to be a purr-fectly good one.",
    "Knocked something off a table earlier and regrets nothing.",
    "Spent the morning supervising a very important cardboard box.",
    "Sends you one slow blink, which in cat means 'I love you'.",
    "Ready to pounce on whatever today throws at you.",
    "Tiny paws, enormous confidence.",
]
PUPPY_CAPTIONS = [
    "Thinks you're doing an amazing job. Genuinely.",
    "Has been a very good dog today. Possibly the best.",
    "Just learned 'sit' and would like everyone to know.",
    "Believes every day is the best day ever, and might be right.",
    "Sends you a wag and a slightly soggy tennis ball.",
    "Ready for walkies, belly rubs and whatever else you've got.",
    "Tail currently set to maximum wag.",
    "Has a big heart and even bigger paws to grow into.",
    "Would like you to take a break and go outside today.",
    "Heard you were having a day, brought you this face.",
]


def _pick(options: list[str], day: date, salt: str) -> str:
    digest = hashlib.sha256(f"{day.isoformat()}:{salt}".encode()).digest()
    return options[int.from_bytes(digest[:4], "big") % len(options)]


def _breed_from_dog_url(url: str) -> str | None:
    # https://images.dog.ceo/breeds/hound-afghan/n02088094_1003.jpg -> "Afghan Hound"
    try:
        slug = url.split("/breeds/")[1].split("/")[0]
    except IndexError:
        return None
    parts = slug.split("-")
    return " ".join(p.capitalize() for p in reversed(parts))


def fetch_kitten(session: requests.Session, day: date) -> dict | None:
    image_url = None
    try:
        r = session.get(CAT_API, timeout=15)
        r.raise_for_status()
        image_url = r.json()[0]["url"]
    except (requests.RequestException, ValueError, LookupError) as e:
        log.warning("The Cat API failed (%s), trying cataas", e)
        try:
            r = session.get(CATAAS_API, timeout=15)
            r.raise_for_status()
            data = r.json()
            cat_id = data.get("id") or data.get("_id")
            image_url = data.get("url") if str(data.get("url", "")).startswith("http") else None
            if not image_url and cat_id:
                image_url = f"https://cataas.com/cat/{cat_id}"
        except (requests.RequestException, ValueError) as e2:
            log.warning("cataas failed too: %s", e2)
    if not image_url:
        return None
    return {
        "kind": "kitten",
        "date": day.isoformat(),
        "imageUrl": image_url,
        "name": _pick(KITTEN_NAMES, day, "kitten-name"),
        "caption": _pick(KITTEN_CAPTIONS, day, "kitten-caption"),
        "breed": None,
    }


def fetch_puppy(session: requests.Session, day: date) -> dict | None:
    try:
        r = session.get(DOG_API, timeout=15)
        r.raise_for_status()
        image_url = r.json()["message"]
    except (requests.RequestException, ValueError, LookupError) as e:
        log.warning("Dog CEO API failed: %s", e)
        return None
    return {
        "kind": "puppy",
        "date": day.isoformat(),
        "imageUrl": image_url,
        "name": _pick(PUPPY_NAMES, day, "puppy-name"),
        "caption": _pick(PUPPY_CAPTIONS, day, "puppy-caption"),
        "breed": _breed_from_dog_url(image_url),
    }


def pets_for_today(session: requests.Session, day: date, history: list[dict], keep_days: int = 14) -> list[dict]:
    """Return the pet history (newest first) with today's kitten and puppy present.

    Existing entries for ``day`` are kept as-is so the pet of the day is stable.
    """
    have = {(p["date"], p["kind"]) for p in history}
    fresh: list[dict] = []
    if (day.isoformat(), "kitten") not in have:
        if kitten := fetch_kitten(session, day):
            fresh.append(kitten)
    if (day.isoformat(), "puppy") not in have:
        if puppy := fetch_puppy(session, day):
            fresh.append(puppy)
    merged = fresh + history
    merged.sort(key=lambda p: (p["date"], p["kind"]), reverse=True)
    dates = sorted({p["date"] for p in merged}, reverse=True)[:keep_days]
    return [p for p in merged if p["date"] in dates]
