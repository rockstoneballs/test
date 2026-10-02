"""Cat and dog photos, sprinkled through the feed.

Each run adds a random cat (The Cat API, with cataas as a fallback) and a random dog
(Dog CEO), both free and keyless, as ordinary feed posts under the "Pets" topic. The
website and app place one every few posts. Names and captions are picked from the
photo's address, so a photo keeps its name.
"""

from __future__ import annotations

import hashlib
import logging
from datetime import datetime

import requests

from .sources import PETS

log = logging.getLogger(__name__)

CAT_API = "https://api.thecatapi.com/v1/images/search?mime_types=jpg,png"
CATAAS_API = "https://cataas.com/cat?json=true"
DOG_API = "https://dog.ceo/api/breeds/image/random"

CAT_NAMES = [
    "Biscuit", "Mochi", "Pumpkin", "Pickles", "Waffles", "Noodle", "Clementine", "Pepper",
    "Marshmallow", "Toffee", "Ziggy", "Olive", "Button", "Luna", "Miso", "Pudding",
    "Sprout", "Tofu", "Nutmeg", "Beans", "Poppy", "Muffin", "Juniper", "Pistachio",
]
DOG_NAMES = [
    "Barnaby", "Waffles", "Pretzel", "Maple", "Rolo", "Bear", "Hazel", "Scout",
    "Nugget", "Daisy", "Otis", "Peanut", "Pancake", "Rosie", "Gus", "Honey",
    "Teddy", "Bingo", "Fudge", "Winnie", "Chip", "Dumpling", "Ollie", "Bramble",
]
CAT_CAPTIONS = [
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
DOG_CAPTIONS = [
    "Thinks you're doing an amazing job. Genuinely.",
    "Has been a very good dog today. Possibly the best.",
    "Has mastered 'sit' and would like everyone to know.",
    "Believes every day is the best day ever, and might be right.",
    "Sends you a wag and a slightly soggy tennis ball.",
    "Ready for walkies, belly rubs and whatever else you've got.",
    "Tail currently set to maximum wag.",
    "Has a big heart and an even bigger appetite for belly rubs.",
    "Would like you to take a break and go outside today.",
    "Heard you were having a day, brought you this face.",
]


def _pick(options: list[str], key: str, salt: str) -> str:
    digest = hashlib.sha256(f"{key}:{salt}".encode()).digest()
    return options[int.from_bytes(digest[:4], "big") % len(options)]


def _breed_from_dog_url(url: str) -> str | None:
    # https://images.dog.ceo/breeds/hound-afghan/n02088094_1003.jpg -> "Afghan Hound"
    try:
        slug = url.split("/breeds/")[1].split("/")[0]
    except IndexError:
        return None
    parts = slug.split("-")
    return " ".join(p.capitalize() for p in reversed(parts))


def _post(*, image_url: str, animal: str, source: str, homepage: str, now: datetime,
          breed: str | None = None, width: int | None = None, height: int | None = None) -> dict:
    names, captions, emoji = (CAT_NAMES, CAT_CAPTIONS, "🐱") if animal == "cat" else (DOG_NAMES, DOG_CAPTIONS, "🐶")
    name = _pick(names, image_url, "name")
    title = f"Meet {name}, {'a' if breed[0].lower() not in 'aeiou' else 'an'} {breed} {emoji}" if breed else f"Meet {name} {emoji}"
    return {
        "title": title,
        "summary": _pick(captions, image_url, "caption"),
        "url": image_url,
        "imageUrl": image_url,
        "imageWidth": width,
        "imageHeight": height,
        "source": source,
        "sourceHomepage": homepage,
        "publishedAt": now,
        "kind": "image",
        "community": PETS,
        "author": source,
    }


def fetch_cat(session: requests.Session, now: datetime) -> dict | None:
    try:
        r = session.get(CAT_API, timeout=15)
        r.raise_for_status()
        cat = r.json()[0]
        return _post(image_url=cat["url"], animal="cat", source="The Cat API", homepage="https://thecatapi.com",
                     now=now, width=cat.get("width"), height=cat.get("height"))
    except (requests.RequestException, ValueError, LookupError) as e:
        log.warning("The Cat API failed (%s), trying cataas", e)
    try:
        r = session.get(CATAAS_API, timeout=15)
        r.raise_for_status()
        data = r.json()
        cat_id = data.get("id") or data.get("_id")
        if not cat_id:
            return None
        return _post(image_url=f"https://cataas.com/cat/{cat_id}", animal="cat", source="Cataas",
                     homepage="https://cataas.com", now=now)
    except (requests.RequestException, ValueError) as e:
        log.warning("cataas failed too: %s", e)
        return None


def fetch_dog(session: requests.Session, now: datetime) -> dict | None:
    try:
        r = session.get(DOG_API, timeout=15)
        r.raise_for_status()
        image_url = r.json()["message"]
    except (requests.RequestException, ValueError, LookupError) as e:
        log.warning("Dog CEO API failed: %s", e)
        return None
    if not str(image_url).startswith("https://"):
        return None
    return _post(image_url=image_url, animal="dog", source="Dog CEO", homepage="https://dog.ceo",
                 now=now, breed=_breed_from_dog_url(image_url))


def fetch_pet_posts(session: requests.Session, now: datetime, each: int = 1) -> list[dict]:
    """``each`` fresh cats and dogs for this run (fewer if an API is down)."""
    posts = [p for _ in range(each) for p in (fetch_cat(session, now), fetch_dog(session, now)) if p]
    return list({p["imageUrl"]: p for p in posts}.values())  # the APIs occasionally repeat a photo
