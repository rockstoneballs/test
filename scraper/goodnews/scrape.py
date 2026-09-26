"""Build feed.json: scrape feeds, keep only good news, add pets of the day.

Usage:
    python -m goodnews.scrape --out site --previous https://<you>.github.io/<repo>/feed.json

``--previous`` (a URL or a local path) is the last published feed. Stories in it
are carried forward so the feed doesn't shrink when a source's RSS rolls over,
already-classified stories aren't re-sent to Claude, and today's kitten and
puppy stay the same all day.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import logging
import re
import sys
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import feedparser
import requests

from . import keywords
from .classifier import ClaudeClassifier
from .pets import pets_for_today
from .sources import SOURCES, Source

log = logging.getLogger("goodnews")

FEED_VERSION = 1
USER_AGENT = "SunnysideGoodNewsBot/1.0 (+https://github.com/rockstoneballs/test)"
MAX_OG_IMAGE_LOOKUPS = 60
MIN_UPLIFT_MAINSTREAM = 6
MIN_UPLIFT_TRUSTED = 3

_TRACKING_PARAMS = re.compile(r"^(utm_|fbclid|gclid|mc_|ref$|cmpid|at_)", re.IGNORECASE)
_TAG_RX = re.compile(r"<[^>]+>")
_WS_RX = re.compile(r"\s+")
_WP_FOOTER_RX = re.compile(r"The post .{0,300}? appeared first on .{0,200}?\.?$", re.IGNORECASE | re.DOTALL)
_IMG_SRC_RX = re.compile(r"<img[^>]+src=[\"']([^\"']+)[\"']", re.IGNORECASE)
_OG_IMAGE_RX = re.compile(
    r"<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"']([^\"']+)[\"']"
    r"|<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"']",
    re.IGNORECASE,
)


# --------------------------------------------------------------------------- helpers

def canonical_url(url: str) -> str:
    parts = urlsplit(url.strip())
    query = urlencode([(k, v) for k, v in parse_qsl(parts.query) if not _TRACKING_PARAMS.match(k)])
    return urlunsplit((parts.scheme.lower() or "https", parts.netloc.lower(), parts.path, query, ""))


def story_id(url: str) -> str:
    return hashlib.sha1(canonical_url(url).encode()).hexdigest()[:16]


def clean_text(raw: str, limit: int = 400) -> str:
    text = html.unescape(_TAG_RX.sub(" ", raw or ""))
    text = _WS_RX.sub(" ", text).strip()
    text = _WP_FOOTER_RX.sub("", text).strip()
    text = re.sub(r"\s*(\[…\]|\[\.\.\.\]|Continue reading\.*|Read more\.*)$", "…", text, flags=re.IGNORECASE)
    if len(text) > limit:
        text = text[:limit].rsplit(" ", 1)[0].rstrip(",;:—-") + "…"
    return text


def title_key(title: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", title.lower())[:80]


def parse_time(entry) -> datetime | None:
    for key in ("published_parsed", "updated_parsed"):
        t = entry.get(key)
        if t:
            return datetime(*t[:6], tzinfo=timezone.utc)
    for key in ("published", "updated"):
        if entry.get(key):
            try:
                return parsedate_to_datetime(entry[key]).astimezone(timezone.utc)
            except (TypeError, ValueError):
                pass
    return None


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def find_image(entry) -> str | None:
    for media in entry.get("media_content", []) or []:
        url = media.get("url")
        if url and (media.get("medium") in (None, "image") or re.search(r"\.(jpe?g|png|webp)", url, re.I)):
            return url
    for thumb in entry.get("media_thumbnail", []) or []:
        if thumb.get("url"):
            return thumb["url"]
    for link in entry.get("links", []) or []:
        if link.get("rel") == "enclosure" and str(link.get("type", "")).startswith("image"):
            return link.get("href")
    for enc in entry.get("enclosures", []) or []:
        if str(enc.get("type", "")).startswith("image") and enc.get("href"):
            return enc["href"]
    blobs = [c.get("value", "") for c in entry.get("content", []) or []] + [entry.get("summary", "")]
    for blob in blobs:
        m = _IMG_SRC_RX.search(blob or "")
        if m and not m.group(1).startswith("data:"):
            return html.unescape(m.group(1))
    return None


def is_http_url(url: str | None) -> bool:
    return bool(url) and url.startswith(("https://", "http://"))


# --------------------------------------------------------------------------- scraping

def fetch_source(session: requests.Session, source: Source, fixtures: Path | None) -> list[dict]:
    try:
        if fixtures is not None:
            path = fixtures / (re.sub(r"[^a-z0-9]+", "_", source.name.lower()).strip("_") + ".xml")
            if not path.exists():
                return []
            parsed = feedparser.parse(path.read_bytes())
        else:
            r = session.get(source.url, timeout=25)
            r.raise_for_status()
            parsed = feedparser.parse(r.content)
    except requests.RequestException as e:
        log.warning("%s: fetch failed: %s", source.name, e)
        return []

    items = []
    for entry in parsed.entries:
        url = entry.get("link")
        title = clean_text(entry.get("title", ""), limit=220)
        if not is_http_url(url) or not title:
            continue
        summary_raw = entry.get("summary") or ""
        if not summary_raw and entry.get("content"):
            summary_raw = entry["content"][0].get("value", "")
        image = find_image(entry)
        items.append({
            "id": story_id(url),
            "title": title,
            "summary": clean_text(summary_raw),
            "url": url,
            "imageUrl": image if is_http_url(image) else None,
            "source": source.name,
            "sourceHomepage": source.homepage,
            "publishedAt": parse_time(entry),
            "_trusted": source.trusted,
        })
    log.info("%s: %d items", source.name, len(items))
    return items


def og_image(session: requests.Session, url: str) -> str | None:
    try:
        r = session.get(url, timeout=10, stream=True)
        r.raise_for_status()
        head = r.raw.read(200_000, decode_content=True).decode("utf-8", "ignore")
        r.close()
    except (requests.RequestException, OSError):
        return None
    m = _OG_IMAGE_RX.search(head)
    if not m:
        return None
    found = html.unescape(m.group(1) or m.group(2))
    return found if is_http_url(found) else None


def select_good_news(candidates: list[dict], use_claude: bool) -> list[dict]:
    """Filter candidates down to good news and tag category/region/uplift."""
    verdicts = {}
    if use_claude and candidates:
        log.info("Classifying %d new stories with Claude", len(candidates))
        verdicts = ClaudeClassifier().classify(candidates)
        log.info("Claude classified %d/%d", len(verdicts), len(candidates))

    selected = []
    for i, story in enumerate(candidates):
        trusted = story["_trusted"]
        if keywords.is_hard_blocked(story["title"]):
            continue
        verdict = verdicts.get(i)
        if verdict is not None:
            threshold = MIN_UPLIFT_TRUSTED if trusted else MIN_UPLIFT_MAINSTREAM
            if not verdict.good_news or verdict.uplift < threshold:
                continue
            story.update(category=verdict.category, region=verdict.region, uplift=verdict.uplift)
            if verdict.summary:
                story["summary"] = verdict.summary
        else:
            if not keywords.passes_keyword_filter(story["title"], story["summary"], trusted):
                continue
            story.update(
                category=keywords.guess_category(story["title"], story["summary"]),
                region=keywords.guess_region(story["title"], story["summary"]),
                uplift=max(3, min(10, 5 + keywords.positivity(story["title"], story["summary"]) // 2)),
            )
        selected.append(story)
    return selected


def load_previous(ref: str | None, session: requests.Session) -> dict:
    empty = {"stories": [], "pets": []}
    if not ref:
        return empty
    try:
        if ref.startswith(("http://", "https://")):
            r = session.get(ref, timeout=20)
            if r.status_code == 404:
                log.info("No previous feed published yet")
                return empty
            r.raise_for_status()
            data = r.json()
        else:
            path = Path(ref)
            if not path.exists():
                return empty
            data = json.loads(path.read_text())
    except (requests.RequestException, ValueError) as e:
        log.warning("Could not load previous feed (%s); starting fresh", e)
        return empty
    return {"stories": data.get("stories", []), "pets": data.get("pets", [])}


def build_feed(
    session: requests.Session,
    previous: dict,
    now: datetime,
    fixtures: Path | None = None,
    use_claude: bool = False,
    fetch_images: bool = True,
    fetch_pets: bool = True,
    max_age_days: int = 7,
    max_stories: int = 300,
) -> dict:
    with ThreadPoolExecutor(max_workers=8) as pool:
        batches = list(pool.map(lambda s: fetch_source(session, s, fixtures), SOURCES))
    scraped = [item for batch in batches for item in batch]

    cutoff = now - timedelta(days=max_age_days)
    prev_stories = [s for s in previous["stories"] if s.get("publishedAt", "") >= iso(cutoff)]
    seen_ids = {s["id"] for s in prev_stories}
    seen_titles = {title_key(s["title"]) for s in prev_stories}

    candidates = []
    for item in scraped:
        published = item["publishedAt"] or now
        if published < cutoff or published > now + timedelta(hours=6):
            continue
        key = title_key(item["title"])
        if item["id"] in seen_ids or key in seen_titles:
            continue
        seen_ids.add(item["id"])
        seen_titles.add(key)
        item["publishedAt"] = min(published, now)
        candidates.append(item)
    log.info("%d scraped, %d new candidates", len(scraped), len(candidates))

    fresh = select_good_news(candidates, use_claude)
    log.info("%d new good-news stories", len(fresh))

    if fetch_images:
        missing = [s for s in fresh if not s["imageUrl"]][:MAX_OG_IMAGE_LOOKUPS]
        with ThreadPoolExecutor(max_workers=8) as pool:
            for story, image in zip(missing, pool.map(lambda s: og_image(session, s["url"]), missing)):
                story["imageUrl"] = image

    new_stories = [{
        "id": s["id"],
        "title": s["title"],
        "summary": s["summary"],
        "url": s["url"],
        "imageUrl": s["imageUrl"],
        "source": s["source"],
        "sourceHomepage": s["sourceHomepage"],
        "publishedAt": iso(s["publishedAt"]),
        "category": s["category"],
        "region": s["region"],
        "uplift": s["uplift"],
    } for s in fresh]

    stories = new_stories + prev_stories
    stories.sort(key=lambda s: s["publishedAt"], reverse=True)
    stories = stories[:max_stories]

    pets = previous["pets"]
    if fetch_pets:
        pets = pets_for_today(session, now.date(), pets)

    return {
        "version": FEED_VERSION,
        "generatedAt": iso(now),
        "categories": keywords.CATEGORIES,
        "regions": keywords.REGIONS,
        "stories": stories,
        "pets": pets,
    }


INDEX_HTML = """<!doctype html>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sunnyside feed</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:40rem;margin:3rem auto;padding:0 1rem;color:#3b2f1e;background:#fffaf0}</style>
<h1>☀️ Sunnyside</h1>
<p>This is the data feed behind the Sunnyside good-news app. The app reads
<a href="feed.json">feed.json</a>, which is refreshed every couple of hours.</p>
"""


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="site", help="output directory (default: site)")
    ap.add_argument("--previous", help="URL or path of the last published feed.json")
    ap.add_argument("--fixtures", type=Path, help="read feeds from local XML files instead of the network")
    ap.add_argument("--no-claude", action="store_true", help="use keyword filtering even if an API key is set")
    ap.add_argument("--no-images", action="store_true", help="skip og:image lookups")
    ap.add_argument("--no-pets", action="store_true", help="skip kitten/puppy of the day")
    ap.add_argument("--max-age-days", type=int, default=7)
    ap.add_argument("--max-stories", type=int, default=300)
    args = ap.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    session = requests.Session()
    session.headers["User-Agent"] = USER_AGENT

    use_claude = not args.no_claude and ClaudeClassifier.available()
    log.info("Positivity filter: %s", "Claude" if use_claude else "keywords")

    feed = build_feed(
        session,
        load_previous(args.previous, session),
        datetime.now(timezone.utc).replace(microsecond=0),
        fixtures=args.fixtures,
        use_claude=use_claude,
        fetch_images=not args.no_images,
        fetch_pets=not args.no_pets,
        max_age_days=args.max_age_days,
        max_stories=args.max_stories,
    )

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "feed.json").write_text(json.dumps(feed, ensure_ascii=False, indent=1))
    (out / "index.html").write_text(INDEX_HTML)
    (out / ".nojekyll").write_text("")
    log.info("Wrote %s with %d stories and %d pets", out / "feed.json", len(feed["stories"]), len(feed["pets"]))
    if not feed["stories"]:
        log.error("Feed is empty — every source failed?")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
