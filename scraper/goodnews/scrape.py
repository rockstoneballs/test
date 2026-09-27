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
from collections import Counter
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
from .social import RedditClient, fetch_imgur, fetch_lemmy, fetch_ninegag, fetch_reddit
from .sources import MAX_AGE_DAYS, SOCIAL_COMMUNITIES, SOCIAL_SOURCES, SOURCES, Source

log = logging.getLogger("goodnews")

FEED_VERSION = 2
USER_AGENT = "SunnysideGoodNewsBot/1.1 (+https://github.com/rockstoneballs/test)"
MAX_OG_IMAGE_LOOKUPS = 80
SOCIAL_UPLIFT = 7
MAX_PER_SOCIAL_COMMUNITY = 150
MAX_REJECTED_IDS = 5000
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

    language = str(parsed.feed.get("language", "")).lower()
    if language and not language.startswith("en"):
        log.info("%s: skipped, feed language is %s", source.name, language)
        return []
    is_google = "news.google.com" in source.url
    items = []
    for entry in parsed.entries:
        url = entry.get("link")
        title = clean_text(entry.get("title", ""), limit=220)
        if not is_http_url(url) or not title:
            continue
        name = source.name
        if is_google:
            # Google News titles look like "Headline - Publisher"; credit the publisher.
            publisher = (entry.get("source") or {}).get("title")
            if publisher:
                name = publisher
                title = re.sub(r"\s+[-–—]\s+" + re.escape(publisher) + r"$", "", title)
        summary_raw = entry.get("summary") or ""
        if not summary_raw and entry.get("content"):
            summary_raw = entry["content"][0].get("value", "")
        image = find_image(entry)
        items.append(_item(
            title=title,
            summary="" if is_google else clean_text(summary_raw),
            url=url,
            imageUrl=image if is_http_url(image) else None,
            source=name,
            sourceHomepage=source.homepage,
            publishedAt=parse_time(entry),
            trusted=source.trusted,
        ))
    log.info("%s: %d items", source.name, len(items))
    return items


def _item(*, title, url, source, sourceHomepage, publishedAt, trusted, summary="", imageUrl=None,
          kind="article", community=None, author=None, score=None, comments=None, discussionUrl=None,
          imageWidth=None, imageHeight=None, videoUrl=None) -> dict:
    return {
        "id": story_id(discussionUrl if kind != "article" and discussionUrl else url),
        "title": title,
        "summary": summary,
        "url": url,
        "imageUrl": imageUrl,
        "imageWidth": imageWidth,
        "imageHeight": imageHeight,
        "videoUrl": videoUrl,
        "source": source,
        "sourceHomepage": sourceHomepage,
        "publishedAt": publishedAt,
        "kind": kind,
        "community": community,
        "author": author or source,
        "score": score,
        "comments": comments,
        "discussionUrl": discussionUrl,
        "_trusted": trusted,
    }


def fetch_social(session: requests.Session) -> list[dict]:
    reddit = RedditClient(session)
    results: list[dict] = []
    # Reddit sequentially (polite, and lets us stop at the first block); Lemmy in parallel.
    for src in (s for s in SOCIAL_SOURCES if s.platform == "reddit"):
        got = fetch_reddit(reddit, src)
        log.info("r/%s: %d posts", src.name, len(got))
        results += got
    fetchers = {"lemmy": fetch_lemmy, "9gag": fetch_ninegag, "imgur": fetch_imgur}
    open_apis = [s for s in SOCIAL_SOURCES if s.platform in fetchers]
    with ThreadPoolExecutor(max_workers=6) as pool:
        for src, got in zip(open_apis, pool.map(lambda s: fetchers[s.platform](session, s), open_apis)):
            newest = max((g["publishedAt"] for g in got), default=None)
            log.info("%s %s: %d posts (newest %s)", src.platform, src.name, len(got), newest and iso(newest))
            results += got
    return [_item(trusted=True, **r) for r in results]


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


def unwanted(story: dict) -> bool:
    """Content Sunnyside leaves out whatever its tone: non-English posts, celebrity and
    royalty news, and sport. Memes and animal photos are only checked by their title."""
    title, summary = story["title"], story.get("summary") or ""
    article = story.get("kind", "article") == "article"
    text = f"{title}\n{summary}" if article else title
    if not keywords.is_english(text) or keywords.is_off_topic(title, summary if article else ""):
        return True
    return article and (story.get("community") == "Sport" or keywords.is_sport(title, summary))


def select_good_news(candidates: list[dict], use_claude: bool) -> list[dict]:
    """Filter candidates down to good news and tag community/region/uplift.

    Posts from the fixed social communities (memes, cute animals) are already
    curated by their communities; they only get the hard-block check. Articles
    go through Claude or the keyword filter.
    """
    candidates = [c for c in candidates if not unwanted(c)]
    articles = [c for c in candidates if c["community"] is None]
    verdicts = {}
    if use_claude and articles:
        log.info("Classifying %d new stories with Claude", len(articles))
        verdicts = ClaudeClassifier().classify(articles)
        log.info("Claude classified %d/%d", len(verdicts), len(articles))

    selected = []
    for story in candidates:
        if keywords.is_hard_blocked(story["title"]):
            if story["community"] is not None:
                log.info("Blocked %s post: %s", story["source"], story["title"][:80])
            continue
        if story["community"] is not None:
            story.update(region="Global", uplift=SOCIAL_UPLIFT)
            selected.append(story)

    for i, story in enumerate(articles):
        trusted = story["_trusted"]
        if keywords.is_hard_blocked(story["title"]):
            continue
        verdict = verdicts.get(i)
        if verdict is not None:
            threshold = MIN_UPLIFT_TRUSTED if trusted else MIN_UPLIFT_MAINSTREAM
            if not verdict.good_news or verdict.uplift < threshold:
                continue
            story.update(community=verdict.category, region=verdict.region, uplift=verdict.uplift)
            if verdict.summary:
                story["summary"] = verdict.summary
        else:
            if not keywords.passes_keyword_filter(story["title"], story["summary"], trusted):
                continue
            story.update(
                community=keywords.guess_category(story["title"], story["summary"]),
                region=keywords.guess_region(story["title"], story["summary"]),
                uplift=max(3, min(10, 5 + keywords.positivity(story["title"], story["summary"]) // 2)),
            )
        if story["community"] == "Sport":
            continue  # no sport on Sunnyside
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
    stories = data.get("stories", [])
    for s in stories:  # upgrade v1 feeds
        s.setdefault("community", s.get("category", "Community"))
        s.setdefault("kind", "article")
    return {"stories": stories, "pets": data.get("pets", []), "rejected": data.get("rejected", [])}


def _merge_social_fields(target: dict, other: dict) -> None:
    """Copy votes/comments/discussion from a Reddit/Lemmy copy of the same story."""
    if other.get("videoUrl") and not target.get("videoUrl"):
        target["videoUrl"] = other["videoUrl"]
    if other.get("score") is not None and (target.get("score") or 0) < other["score"]:
        for key in ("score", "comments", "discussionUrl"):
            target[key] = other[key]


def _output(s: dict) -> dict:
    return {
        "id": s["id"],
        "kind": s["kind"],
        "title": s["title"],
        "summary": s["summary"],
        "url": s["url"],
        "imageUrl": s["imageUrl"],
        "imageWidth": s.get("imageWidth"),
        "imageHeight": s.get("imageHeight"),
        "videoUrl": s.get("videoUrl"),
        "source": s["source"],
        "sourceHomepage": s["sourceHomepage"],
        "author": s.get("author") or s["source"],
        "publishedAt": iso(s["publishedAt"]),
        "community": s["community"],
        "category": s["community"],  # v1 apps read this field
        "region": s["region"],
        "uplift": s["uplift"],
        "score": s.get("score"),
        "comments": s.get("comments"),
        "discussionUrl": s.get("discussionUrl"),
    }


def build_feed(
    session: requests.Session,
    previous: dict,
    now: datetime,
    fixtures: Path | None = None,
    use_claude: bool = False,
    fetch_images: bool = True,
    fetch_pets: bool = True,
    fetch_social_posts: bool = True,
    max_age_days: int = 7,
    max_stories: int = 900,
) -> dict:
    with ThreadPoolExecutor(max_workers=12) as pool:
        batches = list(pool.map(lambda s: fetch_source(session, s, fixtures), SOURCES))
    scraped = [item for batch in batches for item in batch]
    if fetch_social_posts and fixtures is None:
        # Social posts first, so an article shared on Reddit keeps its RSS copy's summary
        # but picks up the Reddit votes (see _merge_social_fields).
        scraped = fetch_social(session) + scraped

    cutoff = now - timedelta(days=max_age_days)

    def cutoff_for(community: str | None) -> datetime:
        return now - timedelta(days=MAX_AGE_DAYS.get(community, max_age_days))

    prev_stories = [
        s for s in previous["stories"]
        if s.get("publishedAt", "") >= iso(cutoff_for(s.get("community")))
        # Filters added later also clean up posts that were published before them.
        and not unwanted(s)
        # Mastodon was dropped as a source; its old posts go too.
        and not s.get("source", "").startswith("#")
        # Clips saved before we kept their video file can't play; they come back if still popular.
        and not (s.get("kind") == "video" and not s.get("videoUrl"))
    ]
    by_id = {s["id"]: s for s in prev_stories}
    by_title = {title_key(s["title"]): s for s in prev_stories}

    candidates: list[dict] = []
    cand_by_id: dict[str, dict] = {}
    cand_by_title: dict[str, dict] = {}
    dropped: Counter[str] = Counter()
    for item in scraped:
        published = item["publishedAt"] or now
        if published < cutoff_for(item["community"]) or published > now + timedelta(hours=6):
            dropped[f"{item['source']}: too old"] += 1
            continue
        key = title_key(item["title"])
        existing = by_id.get(item["id"]) or by_title.get(key)
        if existing is not None:
            _merge_social_fields(existing, item)  # keeps vote counts fresh on every run
            continue
        dup = cand_by_id.get(item["id"]) or cand_by_title.get(key)
        if dup is not None:
            if dup["kind"] == "article" and item["kind"] == "article":
                # Prefer the copy with a summary/image, keep the social stats from either.
                if not dup["summary"] and item["summary"]:
                    for field in ("summary", "source", "sourceHomepage", "author"):
                        dup[field] = item[field]
                    dup["_trusted"] = dup["_trusted"] or item["_trusted"]
                if not dup["imageUrl"]:
                    dup["imageUrl"] = item["imageUrl"]
                _merge_social_fields(dup, item)
            continue
        item["publishedAt"] = min(published, now)
        candidates.append(item)
        cand_by_id[item["id"]] = item
        cand_by_title[key] = item
    log.info("%d scraped, %d new candidates", len(scraped), len(candidates))
    social_drops = {k: v for k, v in dropped.items() if k.startswith(("r/", "Lemmy", "#"))}
    if social_drops:
        log.info("Dropped social posts: %s", ", ".join(f"{k} ×{v}" for k, v in sorted(social_drops.items())))

    # Stories that already failed the filter aren't checked again (saves Claude calls).
    rejected_before = set(previous.get("rejected", []))
    candidates = [c for c in candidates if c["id"] not in rejected_before]
    fresh = select_good_news(candidates, use_claude)
    kept_ids = {s["id"] for s in fresh}
    rejected = [c["id"] for c in candidates if c["id"] not in kept_ids] + list(previous.get("rejected", []))
    log.info("%d new posts (%s)", len(fresh), ", ".join(
        f"{c}: {n}" for c, n in sorted(Counter(s["community"] for s in fresh).items())))

    if fetch_images:
        missing = [
            s for s in fresh
            if not s["imageUrl"] and s["kind"] == "article" and "news.google.com" not in s["url"]
        ][:MAX_OG_IMAGE_LOOKUPS]
        with ThreadPoolExecutor(max_workers=8) as pool:
            for story, image in zip(missing, pool.map(lambda s: og_image(session, s["url"]), missing)):
                story["imageUrl"] = image

    stories = [_output(s) for s in fresh] + prev_stories
    stories.sort(key=lambda s: s["publishedAt"], reverse=True)

    # Keep each social community from crowding out the news.
    per_community: Counter[str] = Counter()
    kept = []
    for s in stories:
        if s["kind"] != "article":
            per_community[s["community"]] += 1
            if per_community[s["community"]] > MAX_PER_SOCIAL_COMMUNITY:
                continue
        kept.append(s)
    stories = kept[:max_stories]

    pets = previous["pets"]
    if fetch_pets:
        pets = pets_for_today(session, now.date(), pets)

    return {
        "version": FEED_VERSION,
        "generatedAt": iso(now),
        "categories": keywords.CATEGORIES,
        "communities": keywords.CATEGORIES + SOCIAL_COMMUNITIES,
        "regions": keywords.REGIONS,
        "stories": stories,
        "pets": pets,
        # IDs of recently rejected stories, so they aren't re-checked every run.
        "rejected": list(dict.fromkeys(rejected))[:MAX_REJECTED_IDS],
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="site", help="output directory (default: site)")
    ap.add_argument("--previous", help="URL or path of the last published feed.json")
    ap.add_argument("--fixtures", type=Path, help="read feeds from local XML files instead of the network")
    ap.add_argument("--no-claude", action="store_true", help="use keyword filtering even if an API key is set")
    ap.add_argument("--no-images", action="store_true", help="skip og:image lookups")
    ap.add_argument("--no-pets", action="store_true", help="skip kitten/puppy of the day")
    ap.add_argument("--no-social", action="store_true", help="skip Reddit and Lemmy")
    ap.add_argument("--max-age-days", type=int, default=7)
    ap.add_argument("--max-stories", type=int, default=900)
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
        fetch_social_posts=not args.no_social,
        max_age_days=args.max_age_days,
        max_stories=args.max_stories,
    )

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "feed.json").write_text(json.dumps(feed, ensure_ascii=False, separators=(",", ":")))
    (out / ".nojekyll").write_text("")
    counts = Counter(s["community"] for s in feed["stories"])
    log.info("Wrote %s with %d posts and %d pets", out / "feed.json", len(feed["stories"]), len(feed["pets"]))
    log.info("Posts per community: %s", ", ".join(f"{c}: {n}" for c, n in sorted(counts.items())))
    if not feed["stories"]:
        log.error("Feed is empty — every source failed?")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
