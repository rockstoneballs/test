"""Reddit and Lemmy: wholesome memes, cute animals, and good-news link posts.

Reddit:
  * With REDDIT_CLIENT_ID / REDDIT_CLIENT_SECRET set (a free "script" app from
    https://www.reddit.com/prefs/apps), we use the official OAuth API. This is
    the reliable option: Reddit often blocks anonymous requests from cloud
    servers like GitHub Actions.
  * Without credentials we try the public JSON endpoints and stop at the first
    block, so we never hammer Reddit.

Lemmy: open API, no key needed.

9GAG: no official API; we read the same JSON its website loads for tag pages
(e.g. /tag/wholesome). NSFW posts are skipped. If 9GAG blocks us it's skipped.

Imgur: official API, needs a free Client-ID (IMGUR_CLIENT_ID from
https://api.imgur.com/oauth2/addclient). Skipped without one.
"""

from __future__ import annotations

import html
import logging
import os
import re
from datetime import datetime, timezone
from urllib.parse import urlsplit

import requests

from .sources import SocialSource

log = logging.getLogger(__name__)

REDDIT_UA = "python:sunnyside-goodnews:v1.1 (good-news aggregator; +https://github.com/rockstoneballs/test)"
_IMAGE_EXT = re.compile(r"\.(jpe?g|png|gif|webp)(\?.*)?$", re.IGNORECASE)
_IMAGE_HOSTS = {"i.redd.it", "i.imgur.com", "i.imgflip.com", "preview.redd.it"}


def _image_like(url: str | None) -> bool:
    return bool(url) and (bool(_IMAGE_EXT.search(url)) or urlsplit(url).netloc in _IMAGE_HOSTS)


def _is_self_or_social(url: str) -> bool:
    host = urlsplit(url).netloc.lower()
    return host.endswith("reddit.com") or host.endswith("redd.it") or host.startswith("lemmy.")


class RedditClient:
    def __init__(self, session: requests.Session):
        self._session = session
        self._token: str | None = None
        self._blocked = False
        cid, secret = os.environ.get("REDDIT_CLIENT_ID"), os.environ.get("REDDIT_CLIENT_SECRET")
        if cid and secret:
            try:
                r = session.post(
                    "https://www.reddit.com/api/v1/access_token",
                    auth=(cid, secret),
                    data={"grant_type": "client_credentials"},
                    headers={"User-Agent": REDDIT_UA},
                    timeout=20,
                )
                r.raise_for_status()
                self._token = r.json()["access_token"]
                log.info("Reddit: using OAuth API")
            except (requests.RequestException, ValueError, KeyError) as e:
                log.warning("Reddit OAuth failed (%s); trying public endpoints", e)

    def listing(self, subreddit: str) -> list[dict]:
        if self._blocked:
            return []
        if self._token:
            url = f"https://oauth.reddit.com/r/{subreddit}/hot?limit=50&raw_json=1"
            headers = {"User-Agent": REDDIT_UA, "Authorization": f"bearer {self._token}"}
        else:
            url = f"https://www.reddit.com/r/{subreddit}/hot.json?limit=50&raw_json=1"
            headers = {"User-Agent": REDDIT_UA}
        try:
            r = self._session.get(url, headers=headers, timeout=20)
        except requests.RequestException as e:
            log.warning("r/%s: %s", subreddit, e)
            return []
        if r.status_code in (401, 403, 429):
            log.warning("Reddit answered %s for r/%s; skipping Reddit for this run "
                        "(set REDDIT_CLIENT_ID/REDDIT_CLIENT_SECRET for reliable access)", r.status_code, subreddit)
            self._blocked = True
            return []
        if not r.ok:
            log.warning("r/%s: HTTP %s", subreddit, r.status_code)
            return []
        try:
            return [c["data"] for c in r.json()["data"]["children"]]
        except (ValueError, KeyError, TypeError):
            return []


def _reddit_media(d: dict) -> tuple[str | None, int | None, int | None, str]:
    """Best image for a post, its size, and kind ("image" | "video" | "link")."""
    width = height = None
    preview_url = None
    try:
        src = d["preview"]["images"][0]["source"]
        preview_url, width, height = src["url"], src.get("width"), src.get("height")
    except (KeyError, IndexError, TypeError):
        pass

    if d.get("is_gallery") and d.get("media_metadata"):
        try:
            first = d["gallery_data"]["items"][0]["media_id"]
            s = d["media_metadata"][first]["s"]
            return s.get("u") or s.get("gif"), s.get("x"), s.get("y"), "image"
        except (KeyError, IndexError, TypeError):
            pass

    if d.get("is_video") or d.get("post_hint") in ("hosted:video", "rich:video"):
        return preview_url, width, height, "video"

    url = d.get("url_overridden_by_dest") or d.get("url") or ""
    if d.get("post_hint") == "image" or (_image_like(url) and not url.endswith(".gifv")):
        return url, width, height, "image"
    if url.endswith(".gifv"):
        return preview_url, width, height, "video"
    return preview_url, width, height, "link"


def _reddit_video(d: dict) -> str | None:
    """A directly playable MP4 for a Reddit video or GIF post, if there is one."""
    for media in (d.get("secure_media"), d.get("media")):
        fallback = ((media or {}).get("reddit_video") or {}).get("fallback_url")
        if fallback:
            return fallback
    try:
        return d["preview"]["reddit_video_preview"]["fallback_url"]
    except (KeyError, TypeError):
        pass
    try:
        return d["preview"]["images"][0]["variants"]["mp4"]["source"]["url"]
    except (KeyError, IndexError, TypeError):
        return None


def fetch_reddit(client: RedditClient, src: SocialSource) -> list[dict]:
    items = []
    for d in client.listing(src.name):
        if d.get("over_18") or d.get("spoiler") or d.get("stickied") or d.get("score", 0) < src.min_score:
            continue
        title = (d.get("title") or "").strip()
        if not title:
            continue
        discussion = "https://www.reddit.com" + d.get("permalink", "")
        image, width, height, kind = _reddit_media(d)
        link = d.get("url_overridden_by_dest") or d.get("url") or ""

        if src.community is None:
            # Link-post community: we want the article, not the thread.
            if kind != "link" or not link.startswith("http") or _is_self_or_social(link):
                continue
            item_kind, url = "article", link
        else:
            if kind == "link" or not image:
                continue
            item_kind, url = kind, discussion
        video = _reddit_video(d) if item_kind == "video" else None
        if item_kind == "video" and not video:
            continue  # e.g. YouTube embeds: nothing we can play inline

        items.append({
            "title": title,
            "summary": "",
            "url": url,
            "imageUrl": image if image and image.startswith("https://") else None,
            "imageWidth": width,
            "imageHeight": height,
            "source": f"r/{src.name}",
            "sourceHomepage": f"https://www.reddit.com/r/{src.name}",
            "publishedAt": datetime.fromtimestamp(d.get("created_utc", 0), tz=timezone.utc),
            "kind": item_kind,
            "videoUrl": video,
            "community": src.community,
            "author": f"u/{d.get('author', 'unknown')}",
            "score": int(d.get("score", 0)),
            "comments": int(d.get("num_comments", 0)),
            "discussionUrl": discussion,
        })
    items.sort(key=lambda i: i["score"], reverse=True)
    return items[:src.limit]


def _parse_lemmy_time(value: str) -> datetime:
    value = value.rstrip("Z")
    try:
        dt = datetime.fromisoformat(value)
    except ValueError:
        return datetime.now(timezone.utc)
    return dt.replace(tzinfo=timezone.utc) if dt.tzinfo is None else dt.astimezone(timezone.utc)


def fetch_lemmy(session: requests.Session, src: SocialSource) -> list[dict]:
    community, _, instance = src.name.partition("@")
    try:
        r = session.get(
            f"https://{instance}/api/v3/post/list",
            # TopWeek rather than Hot: some communities are quiet, and Hot can surface old posts.
            params={"community_name": community, "sort": src.sort, "limit": 50, "type_": "All"},
            timeout=20,
        )
        r.raise_for_status()
        posts = r.json().get("posts", [])
    except (requests.RequestException, ValueError) as e:
        log.warning("lemmy %s: %s", src.name, e)
        return []

    items = []
    for p in posts:
        post, counts = p.get("post", {}), p.get("counts", {})
        if post.get("nsfw") or post.get("deleted") or post.get("removed") or counts.get("score", 0) < src.min_score:
            continue
        title = (post.get("name") or "").strip()
        link = post.get("url") or ""
        if not title:
            continue
        discussion = f"https://{instance}/post/{post.get('id')}"
        if src.community is None:
            if not link.startswith("http") or _is_self_or_social(link) or _image_like(link):
                continue
            kind, url, image = "article", link, post.get("thumbnail_url")
        else:
            image = link if _image_like(link) else post.get("thumbnail_url")
            if not image:
                continue
            kind, url = "image", discussion
        items.append({
            "title": title,
            "summary": "",
            "url": url,
            "imageUrl": image if image and image.startswith("https://") else None,
            "imageWidth": None,
            "imageHeight": None,
            "source": f"Lemmy · {community}",
            "sourceHomepage": f"https://{instance}/c/{community}",
            "publishedAt": _parse_lemmy_time(post.get("published", "")),
            "kind": kind,
            "community": src.community,
            "author": f"@{p.get('creator', {}).get('name', 'unknown')}",
            "score": int(counts.get("score", 0)),
            "comments": int(counts.get("comments", 0)),
            "discussionUrl": discussion,
        })
    items.sort(key=lambda i: i["score"], reverse=True)
    return items[:src.limit]



BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"


def fetch_ninegag(session: requests.Session, src: SocialSource) -> list[dict]:
    """``src.name`` is a 9GAG tag, e.g. "wholesome"."""
    try:
        r = session.get(
            f"https://9gag.com/v1/tag-posts/tag/{src.name}/type/hot",
            headers={"User-Agent": BROWSER_UA, "Accept": "application/json"},
            timeout=20,
        )
        r.raise_for_status()
        posts = r.json()["data"]["posts"]
    except (requests.RequestException, ValueError, KeyError, TypeError) as e:
        log.warning("9gag #%s: %s", src.name, e)
        return []

    items = []
    for p in posts:
        if p.get("nsfw") or p.get("type") not in ("Photo", "Animated", "Video"):
            continue
        score = int(p.get("upVoteCount", 0))
        if score < src.min_score:
            continue
        images = p.get("images") or {}
        img = images.get("image700") or images.get("image460") or {}
        # Animated posts come as MP4 (H.264) at 460px; the JPEG above is their poster frame.
        video = (images.get("image460sv") or {}).get("url")
        video = video if str(video or "").startswith("https://") else None
        if not str(img.get("url", "")).startswith("https://"):
            continue
        title = html.unescape(p.get("title") or "").strip()
        if not title:
            continue
        url = p.get("url") or f"https://9gag.com/gag/{p.get('id')}"
        items.append({
            "title": title,
            "summary": "",
            "url": url,
            "imageUrl": img["url"],
            "imageWidth": img.get("width"),
            "imageHeight": img.get("height"),
            "source": f"9GAG · {src.name}",
            "sourceHomepage": f"https://9gag.com/tag/{src.name}",
            "publishedAt": datetime.fromtimestamp(int(p.get("creationTs", 0)), tz=timezone.utc),
            "kind": "video" if video else "image",
            "videoUrl": video,
            "community": src.community,
            "author": "9GAG",
            "score": score,
            "comments": int(p.get("commentsCount", 0)),
            "discussionUrl": url,
        })
    items.sort(key=lambda i: i["score"], reverse=True)
    return items[:src.limit]


def fetch_imgur(session: requests.Session, src: SocialSource) -> list[dict]:
    """``src.name`` is an Imgur gallery tag, e.g. "wholesome". Needs IMGUR_CLIENT_ID."""
    client_id = os.environ.get("IMGUR_CLIENT_ID")
    if not client_id:
        return []
    try:
        r = session.get(
            f"https://api.imgur.com/3/gallery/t/{src.name}/viral/week/0",
            headers={"Authorization": f"Client-ID {client_id}"},
            timeout=20,
        )
        r.raise_for_status()
        entries = r.json()["data"]["items"]
    except (requests.RequestException, ValueError, KeyError, TypeError) as e:
        log.warning("imgur #%s: %s", src.name, e)
        return []

    items = []
    for e in entries:
        if e.get("nsfw"):
            continue
        score = int(e.get("points") or e.get("ups") or 0)
        if score < src.min_score:
            continue
        image = (e.get("images") or [e])[0] if e.get("is_album") else e
        link = image.get("link") or ""
        animated = bool(image.get("animated"))
        video = image.get("mp4") or (link if link.endswith(".mp4") else None)
        if animated:
            # Use a still frame for GIF/MP4 posts; the post link plays it.
            link = f"https://i.imgur.com/{image.get('id')}h.jpg" if image.get("id") else ""
        if not link.startswith("https://") or not _image_like(link):
            continue
        title = (e.get("title") or "").strip()
        if not title:
            continue
        url = e.get("link") if e.get("is_album") else f"https://imgur.com/gallery/{e.get('id')}"
        items.append({
            "title": title,
            "summary": "",
            "url": url,
            "imageUrl": link,
            "imageWidth": image.get("width"),
            "imageHeight": image.get("height"),
            "source": f"Imgur · {src.name}",
            "sourceHomepage": f"https://imgur.com/t/{src.name}",
            "publishedAt": datetime.fromtimestamp(int(e.get("datetime", 0)), tz=timezone.utc),
            "kind": "video" if animated and video else "image",
            "videoUrl": video if animated else None,
            "community": src.community,
            "author": e.get("account_url") or "Imgur",
            "score": score,
            "comments": int(e.get("comment_count") or 0),
            "discussionUrl": url,
        })
    items.sort(key=lambda i: i["score"], reverse=True)
    return items[:src.limit]
