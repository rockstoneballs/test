"""The opening of each story, so readers get the gist on Sunnyside before clicking out.

For every news story we keep a short excerpt (``body``): the first few paragraphs,
capped at EXCERPT_WORDS words, always credited and linked to the full article. It
comes from the feed's own full text when the outlet publishes one, otherwise from the
article page. Pages that ask search engines not to show snippets (``nosnippet`` or
``max-snippet``) are respected: we store nothing from them.

Google News links are redirects, so they're first decoded to the publisher's URL.
"""

from __future__ import annotations

import html
import json
import logging
import re
from dataclasses import dataclass
from urllib.parse import quote, urlsplit

import requests

log = logging.getLogger("goodnews")

EXCERPT_WORDS = 200
MAX_PARAGRAPHS = 5
MIN_PARAGRAPH_CHARS = 60
MAX_PAGE_BYTES = 2_000_000

_P_SPLIT_RX = re.compile(r"</p\s*>|<br\s*/?>\s*<br\s*/?>|</h[1-6]\s*>|</li\s*>|</blockquote\s*>", re.IGNORECASE)
_TAG_RX = re.compile(r"<[^>]+>")
_WS_RX = re.compile(r"\s+")
# Scripts, styles and figure captions aren't story text.
_DROP_BLOCKS_RX = re.compile(r"<(script|style|figure|figcaption|noscript)\b.*?</\1\s*>", re.IGNORECASE | re.DOTALL)
_BOILERPLATE_RX = re.compile(
    r"^(advertisement|related:|read more|read next|sign up|subscribe|share this|click here|photo:|image:|"
    r"credit:|watch:|listen:|follow us|the post .* appeared first on)"
    r"|\b(originally|first) (published|written|appeared|ran|posted)\b|\brepublished\b|"
    r"(sign up|subscribe) (for|to) .{0,40}newsletter|we use cookies|accept (all )?cookies|"
    r"enable javascript|your browser (is|does)|all rights reserved|©|subscribe (now|today)|"
    r"support (our|independent) journalism|become a (member|subscriber)|appeared first on|"
    r"\bphotograph: |\bphoto(graph)? (credit|by|courtesy)\b|getty images|shutterstock|\bap photo\b",
    re.IGNORECASE,
)
_BULLET_RX = re.compile(r"^[-•*–]\s+")
_ROBOTS_RX = re.compile(r"<meta[^>]+name=[\"'](?:robots|googlebot)[\"'][^>]*>", re.IGNORECASE)
_NOSNIPPET_RX = re.compile(r"nosnippet|max-snippet\s*:\s*0\b", re.IGNORECASE)
_OG_IMAGE_RX = re.compile(
    r"<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"']([^\"']+)[\"']"
    r"|<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"']",
    re.IGNORECASE,
)


@dataclass
class Article:
    url: str
    body: str  # paragraphs separated by blank lines; "" if nothing usable
    image: str | None


def _clean(text: str) -> str:
    return _WS_RX.sub(" ", html.unescape(_TAG_RX.sub(" ", text))).strip()


def usable(paragraph: str) -> bool:
    return len(paragraph) >= MIN_PARAGRAPH_CHARS and not _BOILERPLATE_RX.search(paragraph)


def _key(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", text.lower())


def excerpt(paragraphs: list[str], max_words: int = EXCERPT_WORDS, title: str = "") -> str:
    """The first few real paragraphs, stopping at ``max_words`` (a paragraph that would
    go over is cut at a sentence end, or left out). A paragraph that just repeats the
    headline is skipped."""
    out: list[str] = []
    words = 0
    title_key = _key(title)
    for p in (_BULLET_RX.sub("", p.strip()) for p in paragraphs):
        if not usable(p) or (title_key and _key(p) == title_key):
            continue
        n = len(p.split())
        if words + n > max_words:
            room = max_words - words
            sentences = re.split(r"(?<=[.!?])\s+", p)
            cut = []
            for s in sentences:
                if len(" ".join(cut + [s]).split()) > room:
                    break
                cut.append(s)
            if cut and len(" ".join(cut)) >= MIN_PARAGRAPH_CHARS:
                out.append(" ".join(cut))
            break
        out.append(p)
        words += n
        if len(out) >= MAX_PARAGRAPHS:
            break
    return "\n\n".join(out)


def paragraphs_from_html(fragment: str) -> list[str]:
    """Split an HTML fragment (a feed's full-text content) into plain-text paragraphs."""
    fragment = _DROP_BLOCKS_RX.sub(" ", fragment or "")
    return [p for p in (_clean(chunk) for chunk in _P_SPLIT_RX.split(fragment)) if p]


def blocks_snippets(page: str) -> bool:
    """True if the page asks not to be shown as a snippet (robots nosnippet / max-snippet:0)."""
    return any(_NOSNIPPET_RX.search(tag) for tag in _ROBOTS_RX.findall(page[:200_000]))


def og_image_in(page: str) -> str | None:
    m = _OG_IMAGE_RX.search(page)
    if not m:
        return None
    found = html.unescape(m.group(1) or m.group(2))
    return found if found.startswith(("https://", "http://")) else None


def extract_page(page: str, title: str = "") -> str:
    """The story's opening paragraphs from a full article page ("" if none)."""
    if blocks_snippets(page):
        return ""
    import trafilatura  # imported lazily: only needed when pages are fetched

    text = trafilatura.extract(
        page, include_comments=False, include_tables=False, include_images=False,
        include_links=False, favor_precision=True, deduplicate=True,
    ) or ""
    return excerpt(text.split("\n"), title=title)


def _get(session: requests.Session, url: str, timeout: float = 12) -> requests.Response | None:
    try:
        r = session.get(url, timeout=timeout, stream=True)
        r.raise_for_status()
        if "html" not in r.headers.get("Content-Type", "html"):
            r.close()
            return None
        r._content = r.raw.read(MAX_PAGE_BYTES, decode_content=True)
        r.close()
        return r
    except (requests.RequestException, OSError):
        return None


# --------------------------------------------------------------------------- Google News

_GN_ID_RX = re.compile(r"news\.google\.com/(?:rss/)?(?:articles|read)/([A-Za-z0-9_-]+)")
_GN_SIG_RX = re.compile(r"data-n-a-sg=\"([^\"]+)\"")
_GN_TS_RX = re.compile(r"data-n-a-ts=\"([^\"]+)\"")


def is_google_news(url: str) -> bool:
    return urlsplit(url).netloc.endswith("news.google.com")


def decode_google_news(session: requests.Session, url: str) -> str | None:
    """The publisher's URL behind a Google News redirect link, or None.

    Google News article pages carry a signature and timestamp; posting them to the
    page's own batchexecute endpoint returns the real URL (the same call its
    JavaScript makes when you click through)."""
    m = _GN_ID_RX.search(url)
    if not m:
        return None
    article_id = m.group(1)
    try:
        page = session.get(f"https://news.google.com/rss/articles/{article_id}", timeout=10).text
        sig, ts = _GN_SIG_RX.search(page), _GN_TS_RX.search(page)
        if not (sig and ts):
            return None
        inner = (
            '["garturlreq",[["X","X",["X","X"],null,null,1,1,"US:en",null,1,null,null,null,null,null,0,1],'
            f'"X","X",1,[1,1,1],1,1,null,0,0,null,0],"{article_id}",{ts.group(1)},"{sig.group(1)}"]'
        )
        r = session.post(
            "https://news.google.com/_/DotsSplashUi/data/batchexecute",
            data="f.req=" + quote(json.dumps([[["Fbv4je", inner]]])),
            headers={"Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"},
            timeout=10,
        )
        r.raise_for_status()
        rows = json.loads(r.text.split("\n\n", 1)[1])
        decoded = json.loads(rows[0][2])[1]
    except (requests.RequestException, ValueError, IndexError, KeyError, TypeError):
        return None
    return decoded if isinstance(decoded, str) and decoded.startswith(("https://", "http://")) else None


def decode_page(raw: bytes, declared: str | None) -> str:
    """Page bytes to text. Most pages are UTF-8 even when the server doesn't say so
    (requests then guesses Latin-1, which garbles curly quotes), so try UTF-8 first."""
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode(declared or "cp1252", "replace")


def fetch_article(session: requests.Session, url: str, title: str = "") -> Article | None:
    """Resolve Google News links, then read the page's opening paragraphs and image.
    None if the page couldn't be reached."""
    if is_google_news(url):
        real = decode_google_news(session, url)
        if real is None:
            return None
        url = real
    r = _get(session, url)
    if r is None:
        return None
    page = decode_page(r.content, r.encoding)
    try:
        body = extract_page(page, title)
    except Exception as e:  # noqa: BLE001 - a bad page must never break a run
        log.debug("Could not extract %s: %s", url, e)
        body = ""
    return Article(url=r.url or url, body=body, image=og_image_in(page[:300_000]))
