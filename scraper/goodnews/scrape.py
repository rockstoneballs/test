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
import math
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

from . import articles, keywords
from .classifier import ClaudeClassifier
from .pets import pets_for_today
from .social import RedditClient, fetch_imgur, fetch_lemmy, fetch_ninegag, fetch_reddit, vet_social
from .sources import MAX_AGE_DAYS, SOCIAL_COMMUNITIES, SOCIAL_SOURCES, SOURCES, Source

log = logging.getLogger("goodnews")

FEED_VERSION = 2
USER_AGENT = "SunnysideGoodNewsBot/1.1 (+https://github.com/rockstoneballs/test)"
# Article pages read per run for excerpts, images and Google News links (newest first).
MAX_ARTICLE_FETCHES = 120
SOCIAL_UPLIFT = 7
MAX_PER_SOCIAL_COMMUNITY = 150
# "Top stories" nudges UK & Ireland stories up (worth six hours of freshness), as the
# website and app do.
HOME_BONUS = 1.0
# Sunnyside focuses on the West: at most this share of news stories may come from
# Asia, Africa, Latin America or the Middle East (the most uplifting ones are kept).
MAX_NON_WESTERN_SHARE = 0.10
MIN_NON_WESTERN = 2
NON_WESTERN_MAX_UPLIFT = 3
MAX_REJECTED_IDS = 5000
MIN_UPLIFT_MAINSTREAM = 6
MIN_UPLIFT_TRUSTED = 3

_TRACKING_PARAMS = re.compile(r"^(utm_|fbclid|gclid|mc_|ref$|cmpid|at_)", re.IGNORECASE)
_TAG_RX = re.compile(r"<[^>]+>")
_WS_RX = re.compile(r"\s+")
_WP_FOOTER_RX = re.compile(r"The post .{0,300}? appeared first on .{0,200}?\.?$", re.IGNORECASE | re.DOTALL)
_IMG_SRC_RX = re.compile(r"<img[^>]+src=[\"']([^\"']+)[\"']", re.IGNORECASE)


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
        homepage = source.homepage
        if is_google:
            # Google News titles look like "Headline - Publisher"; credit the publisher.
            publisher = (entry.get("source") or {}).get("title")
            homepage = (entry.get("source") or {}).get("href") or homepage
            if publisher:
                name = publisher
                title = re.sub(r"\s+[-–—]\s+" + re.escape(publisher) + r"$", "", title)
        summary_raw = entry.get("summary") or ""
        full_text = (entry.get("content") or [{}])[0].get("value", "")
        if not summary_raw:
            summary_raw = full_text
        image = find_image(entry)
        items.append(_item(
            title=title,
            summary="" if is_google else clean_text(summary_raw),
            url=url,
            imageUrl=image if is_http_url(image) else None,
            source=name,
            sourceHomepage=homepage,
            publishedAt=parse_time(entry),
            trusted=source.trusted,
        ))
        # Many outlets put the whole article in their feed: keep its opening as the excerpt.
        body = articles.excerpt(articles.paragraphs_from_html(full_text), title=title) if not is_google else ""
        if body and ("\n\n" in body or len(body) > len(items[-1]["summary"]) + 80):
            items[-1]["body"] = body
    log.info("%s: %d items", source.name, len(items))
    return items


def _item(*, title, url, source, sourceHomepage, publishedAt, trusted, summary="", imageUrl=None,
          kind="article", community=None, author=None, score=None, comments=None, discussionUrl=None,
          imageWidth=None, imageHeight=None, videoUrl=None, tags=None) -> dict:
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
        "tags": tags,
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


def top_stories(stories: list[dict], now: datetime, n: int = 15) -> list[dict]:
    """The first ``n`` posts under "Top stories", as the website and app rank them
    (web/app.js hotScore + blend). Used to log what readers see first."""
    def hot(s: dict) -> float:
        age_h = (now - datetime.fromisoformat(s["publishedAt"].replace("Z", "+00:00"))).total_seconds() / 3600
        popular = min(3.0, 0.75 * math.log10(1 + max(s.get("score") or 0, 0)))
        home = HOME_BONUS if s.get("region") == keywords.HOME_REGION else 0
        return (s.get("uplift") or 5) + popular + (0.5 if s.get("imageUrl") else 0) + home - age_h / 6

    news = sorted((s for s in stories if s.get("kind", "article") == "article"), key=hot, reverse=True)
    social = sorted((s for s in stories if s.get("kind", "article") != "article"), key=hot, reverse=True)
    out: list[dict] = []
    while (news or social) and len(out) < n:
        out += news[:3]
        news = news[3:]
        if social:
            out.append(social.pop(0))
    return out[:n]


def keyword_uplift(title: str, summary: str, trusted: bool) -> int:
    """Uplift score (0-10) when Claude isn't judging. Dedicated good-news outlets score 6-9;
    mainstream stories that only passed the keyword check top out at 5, so they never
    lead the feed."""
    pos = keywords.positivity(title, summary)
    score = max(6, min(9, 6 + pos // 3)) if trusted else max(3, min(5, 3 + pos // 3))
    if keywords.is_progress(title) or keywords.is_ai_for_good(title, summary):
        # Progress on hard problems (disease, climate) and AI helping people: the big stuff.
        score = max(score, 7 if trusted else 6)
    return score


TRUSTED_NAMES = {s.name for s in SOURCES if s.trusted}
# Sources we've dropped; their old posts are cleared from the feed too.
REMOVED_SOURCES = {"Upworthy", "The Better India", "AllAfrica", "Al Jazeera", "Inspire More", "Sunny Skyz"}


def still_good(story: dict) -> bool:
    """Re-check a previously published article against today's keyword rules (filters get
    stricter over time). Stories Claude approved are left alone."""
    if story.get("source") in REMOVED_SOURCES:
        return False
    if story.get("kind", "article") != "article" or story.get("checkedBy") == "claude":
        return True
    source = story.get("source", "")
    trusted = source in TRUSTED_NAMES or source.startswith(("r/", "Lemmy"))
    title, summary = story["title"], story.get("summary") or ""
    if not keywords.passes_keyword_filter(title, summary, trusted, min_positivity(story)):
        return False
    story["uplift"] = keyword_uplift(title, summary, trusted)  # re-scored with today's rules
    story["community"] = story["category"] = keywords.guess_category(title, summary)
    if story.get("region") in (None, "Global"):
        story["region"] = keywords.guess_region(title, summary)
    return True


SOURCE_REGION = {s.name: s.region for s in SOURCES if s.region}
SOURCE_NAMES = {s.name for s in SOURCES}


def refine_region(story: dict) -> None:
    """Outlets tell us the region even when the headline doesn't: an Indian newspaper,
    BBC Scotland, or (for Google News results) a .uk or .ie website."""
    if story.get("kind", "article") != "article":
        return
    source, homepage = story.get("source", ""), story.get("sourceHomepage", "")
    if story.get("checkedBy") != "claude" and story.get("body"):
        # The article's opening says where it happened more reliably than a feed summary.
        opening = " ".join(story["body"].split("\n\n")[:2])
        story["region"] = keywords.guess_region(story["title"], opening)
    region = story.get("region")
    outlet_region = keywords.region_for_source(source, homepage)
    if outlet_region and region in keywords.WESTERN_REGIONS | {"Global", None}:
        story["region"] = outlet_region
        return
    home = keywords.HOME_REGION
    if region in (None, "Global", "Europe") and keywords.guess_region(story["title"], story.get("summary") or "") == home:
        story["region"] = home
    elif region in (None, "Global") and (
        SOURCE_REGION.get(source) == home or (source not in SOURCE_NAMES and keywords.is_uk_ie_site(homepage))
    ):
        story["region"] = home


# Headlines from UK and Irish news need only one clearly positive word (elsewhere, two),
# so more home news gets in. The gloom, politics and clickbait checks are the same.
HOME_MIN_POSITIVITY = 2


def is_home_story(story: dict) -> bool:
    source, homepage = story.get("source", ""), story.get("sourceHomepage", "")
    return (
        SOURCE_REGION.get(source) == keywords.HOME_REGION
        or (source not in SOURCE_NAMES and keywords.is_uk_ie_site(homepage))
        or keywords.guess_region(story["title"], story.get("summary") or "") == keywords.HOME_REGION
    )


def min_positivity(story: dict) -> int:
    return HOME_MIN_POSITIVITY if is_home_story(story) else 3


def is_western(story: dict) -> bool:
    """Europe, North America, Oceania, or not tied to a place ("Global")."""
    return story.get("region", "Global") in keywords.WESTERN_REGIONS | {"Global"}


def unwanted(story: dict) -> bool:
    """Content Sunnyside leaves out whatever its tone: non-English posts, celebrity and
    royalty news, politics, money and markets, sport, and
    clickbait (teaser headlines, listicles, advice pieces, tabloids). Memes and animal photos are checked by their title."""
    title, summary = story["title"], story.get("summary") or ""
    article = story.get("kind", "article") == "article"
    text = f"{title}\n{summary}" if article else title
    if not keywords.is_english(text) or keywords.is_off_topic(title, summary if article else ""):
        return True
    if keywords.POLITICS.search(text) or keywords.MONEY.search(title) or keywords.PROFANITY.search(title):
        return True
    if article and (keywords.is_clickbait(title) or keywords.is_tabloid(story.get("source", ""), story.get("sourceHomepage", ""))):
        return True
    if article and len(title.split()) < 4:
        return True  # "Cancer Treatments": a section name, not a story
    if not article:
        # Meme and animal titles: nothing sad or grim either.
        return bool(keywords.DOOM.search(title))
    return story.get("community") == "Sport" or keywords.is_sport(title, summary)


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
            story.update(community=verdict.category, region=verdict.region, uplift=verdict.uplift, checkedBy="claude")
            if verdict.summary:
                story["summary"] = verdict.summary
        else:
            if not keywords.passes_keyword_filter(story["title"], story["summary"], trusted, min_positivity(story)):
                continue
            story.update(
                community=keywords.guess_category(story["title"], story["summary"]),
                region=keywords.guess_region(story["title"], story["summary"]),
                uplift=keyword_uplift(story["title"], story["summary"], trusted),
                checkedBy="keywords",
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
        "checkedBy": s.get("checkedBy"),
        "score": s.get("score"),
        "comments": s.get("comments"),
        "discussionUrl": s.get("discussionUrl"),
        **({"body": s["body"]} if "body" in s else {}),
        **({"tags": s["tags"]} if s.get("tags") is not None else {}),
    }


def add_article_text(session: requests.Session, stories: list[dict]) -> None:
    """Give news stories the opening of the article (``body``), a picture if they lack
    one, and the publisher's own link instead of a Google News redirect.

    Stories that already have a ``body`` (from their feed, or an earlier run; "" means
    the page had nothing usable) aren't fetched again. Failed fetches are retried on
    later runs."""
    todo = [s for s in stories if s.get("kind", "article") == "article" and "body" not in s][:MAX_ARTICLE_FETCHES]
    if not todo:
        return
    stats: Counter[str] = Counter()
    with ThreadPoolExecutor(max_workers=8) as pool:
        for story, art in zip(todo, pool.map(lambda s: articles.fetch_article(session, s["url"], s.get("title", "")), todo)):
            if art is None:
                stats["unreachable" if not articles.is_google_news(story["url"]) else "google link not decoded"] += 1
                continue
            story["url"] = art.url
            story["body"] = art.body
            if art.body and not story.get("summary"):
                story["summary"] = clean_text(art.body.split("\n\n", 1)[0], limit=300)
            if not story.get("imageUrl") and art.image:
                story["imageUrl"] = art.image
            stats["with excerpt" if art.body else "no usable text"] += 1
    log.info("Article pages: %d read (%s)", len(todo), ", ".join(f"{k}: {v}" for k, v in stats.most_common()))


_WORD_RX = re.compile(r"[A-Za-z0-9]+")
_DUP_STOP = {"the", "a", "an", "of", "to", "in", "on", "for", "and", "at", "by", "with", "as", "is", "from", "after",
             "its", "their", "this", "that", "new", "be", "are", "was"}


def _title_words(title: str) -> dict[str, bool]:
    """Word stems (first five letters) -> whether it's a name or place (capitalised, not first)."""
    words = _WORD_RX.findall(title)
    return {w.lower()[:5]: i > 0 and w[0].isupper() for i, w in enumerate(words) if w.lower() not in _DUP_STOP}


def _same_story(a: dict[str, bool], b: dict[str, bool]) -> bool:
    if not a or not b:
        return False
    only_a, only_b = a.keys() - b.keys(), b.keys() - a.keys()
    if any(a[w] for w in only_a) and any(b[w] for w in only_b):
        return False  # each names somewhere or someone the other doesn't: Exeter vs Truro
    shared = len(a.keys() & b.keys())
    return shared / len(a.keys() | b.keys()) > 0.65 or (min(len(a), len(b)) >= 5 and shared / min(len(a), len(b)) >= 0.7)


def drop_near_duplicates(stories: list[dict]) -> list[dict]:
    """The same story from several outlets (or re-worded by one): keep the first copy
    (stories arrive newest first, so the earlier report is dropped only if it's the
    duplicate of a fuller one), preferring one with an excerpt."""
    kept: list[dict] = []
    words: list[dict[str, bool]] = []
    for s in stories:
        if s.get("kind", "article") != "article":
            kept.append(s)
            words.append({})
            continue
        w = _title_words(s["title"])
        dup = next((i for i, o in enumerate(words) if _same_story(w, o)), None)
        if dup is None:
            kept.append(s)
            words.append(w)
        elif s.get("body") and not kept[dup].get("body"):
            kept[dup], words[dup] = s, w
    return kept


def grim_inside(story: dict) -> bool:
    """For stories that only passed the keyword filter on their headline: is the article
    itself about something grim or political? (Violence or a named politician anywhere in
    the excerpt; for outlets that aren't dedicated to good news, a gloomy opening too.)"""
    if story.get("checkedBy") == "claude":
        return False
    body = story.get("body") or ""
    title, summary = story.get("title", ""), story.get("summary") or ""
    # Stories about progress on disease or climate naturally talk about deaths and decline.
    progress = keywords.is_progress(title) or keywords.is_ai_for_good(title, summary)
    hard, doom = ((keywords.HARD_BLOCK_FOR_PROGRESS, keywords.DOOM_FOR_PROGRESS) if progress
                  else (keywords.HARD_BLOCK, keywords.DOOM))
    if hard.search(body) or keywords.POLITICIANS.search(body) or keywords.GRIM_TEXT.search(body):
        return True
    if keywords.is_sport("", " ".join(body.split("\n\n")[:2])):
        return True  # a sports report behind a vague headline
    return story.get("source") not in TRUSTED_NAMES and bool(doom.search(body.split("\n\n", 1)[0]))


def build_feed(
    session: requests.Session,
    previous: dict,
    now: datetime,
    fixtures: Path | None = None,
    use_claude: bool = False,
    fetch_pages: bool = True,
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
        and not unwanted(s) and still_good(s)
        # Mastodon was dropped as a source; its old posts go too.
        and not s.get("source", "").startswith("#")
        # 9GAG and Imgur posts are re-checked against today's tag rules; ones saved before
        # we kept their tags are dropped (they come back if they pass).
        and not (s.get("source", "").startswith(("9GAG", "Imgur")) and vet_social(s.get("tags") or [], s.get("community")))
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
                if item.get("body") and len(item["body"]) > len(dup.get("body", "")):
                    dup["body"] = item["body"]
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
    unseen = [c for c in candidates if c["id"] not in rejected_before]
    log.info("%d already rejected on an earlier run, %d to check", len(candidates) - len(unseen), len(unseen))
    candidates = unseen
    if use_claude and fetch_pages:
        # Claude judges and summarises better with the article in front of it.
        add_article_text(session, [c for c in candidates if c["kind"] == "article" and not unwanted(c)])
    fresh = select_good_news(candidates, use_claude)
    kept_ids = {s["id"] for s in fresh}
    rejected = [c["id"] for c in candidates if c["id"] not in kept_ids] + list(previous.get("rejected", []))
    log.info("%d new posts (%s)", len(fresh), ", ".join(
        f"{c}: {n}" for c, n in sorted(Counter(s["community"] for s in fresh).items())))

    stories = [_output(s) for s in fresh] + prev_stories
    stories.sort(key=lambda s: s["publishedAt"], reverse=True)
    if fetch_pages:
        add_article_text(session, stories)
    grim = [s for s in stories if grim_inside(s)]
    if grim:
        log.info("Dropped %d stories whose article turned out grim", len(grim))
        rejected += [s["id"] for s in grim]
        stories = [s for s in stories if not grim_inside(s)]

    # Focus on the West: only a small share of non-Western news, and it never leads the
    # feed (its uplift is capped, which is what "Top stories" ranks by).
    for s in stories:
        refine_region(s)
        if s["kind"] == "article" and not is_western(s):
            s["uplift"] = min(s.get("uplift", 5), NON_WESTERN_MAX_UPLIFT)
    articles = [s for s in stories if s["kind"] == "article"]
    non_western = [s for s in articles if not is_western(s)]
    quota = max(MIN_NON_WESTERN, round(MAX_NON_WESTERN_SHARE * (len(articles) - len(non_western)) / (1 - MAX_NON_WESTERN_SHARE)))
    keep_non_western = {s["id"] for s in sorted(non_western, key=lambda s: s["publishedAt"], reverse=True)[:quota]}

    # Keep each social community from crowding out the news.
    per_community: Counter[str] = Counter()
    kept = []
    for s in stories:
        if s["kind"] == "article" and not is_western(s) and s["id"] not in keep_non_western:
            rejected.append(s["id"])  # over the quota: don't re-check it every run
            continue
        if s["kind"] != "article":
            per_community[s["community"]] += 1
            if per_community[s["community"]] > MAX_PER_SOCIAL_COMMUNITY:
                continue
        kept.append(s)
    stories = drop_near_duplicates(kept)[:max_stories]

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
    ap.add_argument("--no-pages", "--no-images", dest="no_pages", action="store_true",
                    help="don't read article pages (excerpts, images, Google News links)")
    ap.add_argument("--no-pets", action="store_true", help="skip kitten/puppy of the day")
    ap.add_argument("--no-social", action="store_true", help="skip Reddit and Lemmy")
    ap.add_argument("--list-news", action="store_true", help="log every news headline (for reviewing a dry run)")
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
        fetch_pages=not args.no_pages,
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
    log.info("Top stories right now:")
    for i, st in enumerate(top_stories(feed["stories"], datetime.now(timezone.utc)), 1):
        log.info("  %2d. [%s | %s | uplift %s] %s", i, st["source"], st.get("region"), st.get("uplift"), st["title"][:110])
    news = [s for s in feed["stories"] if s.get("kind") == "article"]
    log.info("News with an excerpt: %d of %d", sum(1 for s in news if s.get("body")), len(news))
    if args.list_news:
        for st in news:
            words = len((st.get("body") or "").split())
            log.info("  - [%s | %s | %d words] %s", st["source"], st.get("region"), words, st["title"][:120])
            if st.get("body"):
                log.info("      %s", st["body"][:160].replace("\n\n", " ¶ "))
    if args.list_news:
        for st in feed["stories"]:
            if st.get("source", "").startswith(("9GAG", "Imgur")):
                log.info("  ~ [%s | %s] %s  tags=%s", st["source"], st["kind"], st["title"][:70], st.get("tags"))
    regions = Counter(s.get("region", "Global") for s in feed["stories"] if s.get("kind") == "article")
    log.info("News by region: %s", ", ".join(f"{r}: {n}" for r, n in regions.most_common()))
    clips = [s for s in feed["stories"] if s.get("kind") == "video"]
    log.info("Clips: %d (%d playable). Example: %s", len(clips), sum(1 for s in clips if s.get("videoUrl")),
             next((s["videoUrl"] for s in clips if s.get("videoUrl")), "none"))
    if not feed["stories"]:
        log.error("Feed is empty — every source failed?")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
