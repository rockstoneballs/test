"""Share pages: one small Sunnyside page per post, at ``s/<id>/``.

Shared links point here rather than at the publisher, so people who tap a shared story
land on Sunnyside. Each page has Open Graph tags, so messaging apps and social networks
show a proper preview card (headline, picture, source), plus the story's opening lines,
a credit and link to the original, and a way into the rest of Sunnyside.

Posts leave the feed after about a week, but a shared link should keep working for a
while longer. ``shares.json`` keeps what the pages need for SHARE_DAYS; each run loads
the published copy, adds the current feed and writes every page again.
"""

from __future__ import annotations

import html
import json
import logging
import re
from datetime import datetime, timedelta
from pathlib import Path

import requests

log = logging.getLogger("goodnews")

SHARE_DAYS = 30
MAX_SHARES = 6000
EXCERPT_WORDS = 80
ARCHIVE_NAME = "shares.json"
_ID_RX = re.compile(r"^[0-9a-f]{16}$")


def _parse(ts: str) -> datetime | None:
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except (AttributeError, ValueError):
        return None


def _excerpt(body: str, limit: int = EXCERPT_WORDS) -> str:
    """The first paragraph or two, up to ``limit`` words, ending at a sentence if possible."""
    out: list[str] = []
    words = 0
    for para in (p.strip() for p in (body or "").split("\n\n")):
        if not para:
            continue
        n = len(para.split())
        if words + n > limit:
            if not out:  # a long first paragraph: cut it at a sentence end
                cut = ""
                for sentence in re.split(r"(?<=[.!?])\s+", para):
                    if len((cut + " " + sentence).split()) > limit:
                        break
                    cut = (cut + " " + sentence).strip()
                out.append(cut or " ".join(para.split()[:limit]) + "…")
            break
        out.append(para)
        words += n
    return "\n\n".join(out)


def record(story: dict) -> dict:
    """What a share page needs from a feed post."""
    return {
        "id": story["id"],
        "kind": story.get("kind", "article"),
        "title": story["title"],
        "summary": story.get("summary") or "",
        "excerpt": _excerpt(story.get("body") or ""),
        "url": story["url"],
        "imageUrl": story.get("imageUrl"),
        "source": story["source"],
        "community": story.get("community") or "",
        "publishedAt": story["publishedAt"],
    }


def archive_url(previous_feed: str | None) -> str | None:
    """shares.json sits next to the published feed.json."""
    if not previous_feed or not previous_feed.endswith("feed.json"):
        return None
    return previous_feed[: -len("feed.json")] + ARCHIVE_NAME


def load_archive(ref: str | None, session: requests.Session) -> list[dict]:
    if not ref:
        return []
    try:
        if ref.startswith(("http://", "https://")):
            r = session.get(ref, timeout=30)
            if r.status_code == 404:
                return []
            r.raise_for_status()
            data = r.json()
        else:
            path = Path(ref)
            data = json.loads(path.read_text()) if path.exists() else {}
    except (requests.RequestException, ValueError) as e:
        log.warning("Could not load previous share pages (%s); keeping only the current feed", e)
        return []
    return [r for r in data.get("shares", []) if isinstance(r, dict) and _ID_RX.match(str(r.get("id", "")))]


def merge(previous: list[dict], stories: list[dict], now: datetime, rejected: set[str] | None = None) -> list[dict]:
    """Current posts plus earlier ones from the last SHARE_DAYS, newest first. Posts the
    filters have since turned down (the feed's ``rejected`` ids) lose their page too."""
    rejected = rejected or set()
    by_id = {r["id"]: r for r in previous if r["id"] not in rejected}
    for s in stories:
        if _ID_RX.match(s.get("id", "")):
            by_id[s["id"]] = record(s)
    cutoff = now - timedelta(days=SHARE_DAYS)
    keep = [r for r in by_id.values() if (_parse(r.get("publishedAt", "")) or now) >= cutoff]
    keep.sort(key=lambda r: r.get("publishedAt", ""), reverse=True)
    return keep[:MAX_SHARES]


# --------------------------------------------------------------------------- pages

_PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} · Sunnyside</title>
<meta name="description" content="{description}">
<meta name="theme-color" content="#F76707">
<meta name="referrer" content="no-referrer">
<link rel="canonical" href="{page_url}">
<meta property="og:type" content="article">
<meta property="og:site_name" content="Sunnyside: only good news">
<meta property="og:title" content="{title}">
<meta property="og:description" content="{description}">
<meta property="og:url" content="{page_url}">
{og_image}<meta name="twitter:card" content="{card}">
<link rel="icon" href="../../icon.svg" type="image/svg+xml">
<link rel="stylesheet" href="../../style.css">
<script>try{{const t=localStorage.getItem("sunnyside.theme");if(t)document.documentElement.dataset.theme=JSON.parse(t)}}catch(e){{}}</script>
</head>
<body>
<header class="topbar">
  <a class="brand" href="../../" aria-label="Sunnyside home"><img src="../../icon.svg" alt="" width="32" height="32"><span>sunnyside</span></a>
  <div class="top-actions"><a class="btn btn-primary get-app" href="{app_url}">Get the app</a></div>
</header>
<main class="share-page">
  <article class="post detail">
    <div class="post-head"><span class="source">{source}</span><span class="dot"></span><time datetime="{published}">{published_label}</time></div>
    <h1 class="post-title">{title}</h1>
{image}{summary}{excerpt}    <p class="share-credit">This story was published by <strong>{source}</strong>.</p>
    <div class="share-actions">
      <a class="btn" href="{url}" rel="noopener">Read the full story on {source} →</a>
    </div>
  </article>
  <section class="card share-cta">
    <div class="card-body">
      <h2>☀️ Only good news</h2>
      <p>Sunnyside collects the world's good news, with no doom, no politics and no clickbait. Plus a cat or dog every few stories.</p>
      <a class="btn btn-primary btn-block" href="../../">More good news</a>
      <a class="btn btn-block" href="{app_url}">📱 Get the Android app</a>
    </div>
  </section>
</main>
</body>
</html>
"""


def _esc(text: str) -> str:
    return html.escape(text or "", quote=True)


def _paragraphs(text: str, css: str) -> str:
    return "".join(f'    <p class="{css}">{_esc(p)}</p>\n' for p in text.split("\n\n") if p.strip())


def render(r: dict, site_url: str, app_url: str) -> str:
    page_url = f"{site_url}s/{r['id']}/"
    description = (r.get("summary") or r.get("excerpt") or r["title"]).replace("\n\n", " ")
    description = description[:297] + "…" if len(description) > 300 else description
    image = r.get("imageUrl") or ""
    if not image.startswith(("https://", "http://")):
        image = ""
    published = _parse(r.get("publishedAt", ""))
    summary = r.get("summary") or ""
    excerpt = r.get("excerpt") or ""
    if summary and excerpt.startswith(summary[:60]):
        summary = ""  # the excerpt already opens with it
    return _PAGE.format(
        title=_esc(r["title"]),
        description=_esc(description),
        page_url=_esc(page_url),
        og_image=f'<meta property="og:image" content="{_esc(image)}">\n' if image else "",
        card="summary_large_image" if image else "summary",
        app_url=_esc(app_url),
        source=_esc(r["source"]),
        published=_esc(r.get("publishedAt", "")),
        published_label=published.strftime("%-d %B %Y") if published else "",
        image=f'    <img class="share-image" src="{_esc(image)}" alt="" loading="lazy">\n' if image else "",
        summary=_paragraphs(summary, "post-summary"),
        excerpt=_paragraphs(excerpt, "share-excerpt"),
        url=_esc(r["url"]),
    )


def write(out: Path, shares: list[dict], site_url: str, app_url: str) -> int:
    """Write ``shares.json`` and a page per post. Returns the number of pages."""
    (out / ARCHIVE_NAME).write_text(json.dumps({"shares": shares}, ensure_ascii=False, separators=(",", ":")))
    for r in shares:
        page = out / "s" / r["id"]
        page.mkdir(parents=True, exist_ok=True)
        (page / "index.html").write_text(render(r, site_url, app_url))
    return len(shares)


def site_url_from(previous_feed: str | None) -> str:
    if previous_feed and previous_feed.startswith(("http://", "https://")) and previous_feed.endswith("feed.json"):
        return previous_feed[: -len("feed.json")]
    return "https://rockstoneballs.github.io/test/"

