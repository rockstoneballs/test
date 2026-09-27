from datetime import datetime, timezone

import requests

from goodnews import scrape
from goodnews.social import RedditClient, fetch_lemmy, fetch_reddit
from goodnews.sources import AWW, MEMES, SocialSource

NOW = datetime(2026, 9, 26, 12, 0, tzinfo=timezone.utc)
TS = NOW.timestamp() - 3600


class FakeResponse:
    def __init__(self, data, status=200):
        self._data, self.status_code = data, status
        self.ok = status < 400

    def json(self):
        return self._data

    def raise_for_status(self):
        if not self.ok:
            raise requests.HTTPError(str(self.status_code))


class FakeSession(requests.Session):
    def __init__(self, routes):
        super().__init__()
        self.routes, self.calls = routes, []

    def get(self, url, **kwargs):
        self.calls.append(url)
        for fragment, response in self.routes.items():
            if fragment in url:
                return response
        return FakeResponse({}, 404)


def reddit_post(**overrides):
    post = {
        "title": "My grandma learned to use video calls so she could see her new great-grandkid",
        "permalink": "/r/wholesomememes/comments/abc/grandma/",
        "url": "https://i.redd.it/abc.jpg",
        "post_hint": "image",
        "score": 5400,
        "num_comments": 120,
        "author": "sunbeam",
        "created_utc": TS,
        "preview": {"images": [{"source": {"url": "https://preview.redd.it/abc.jpg?s=1", "width": 1080, "height": 1350}}]},
    }
    post.update(overrides)
    return {"data": post}


def listing(*posts):
    return FakeResponse({"data": {"children": list(posts)}})


def test_reddit_image_posts_become_meme_items():
    session = FakeSession({"/r/wholesomememes/": listing(
        reddit_post(),
        reddit_post(title="NSFW thing", over_18=True, permalink="/r/x/2"),
        reddit_post(title="Too few votes", score=3, permalink="/r/x/3"),
        reddit_post(title="Mod announcement", stickied=True, permalink="/r/x/4"),
        reddit_post(title="A text post", post_hint="self", url="https://www.reddit.com/r/x/5", preview={}, permalink="/r/x/5"),
    )})
    items = fetch_reddit(RedditClient(session), SocialSource("reddit", "wholesomememes", MEMES, min_score=100))
    assert len(items) == 1
    meme = items[0]
    assert meme["kind"] == "image" and meme["community"] == MEMES
    assert meme["imageUrl"] == "https://i.redd.it/abc.jpg"
    assert (meme["imageWidth"], meme["imageHeight"]) == (1080, 1350)
    assert meme["score"] == 5400 and meme["comments"] == 120
    assert meme["discussionUrl"] == "https://www.reddit.com/r/wholesomememes/comments/abc/grandma/"
    assert meme["url"] == meme["discussionUrl"]
    assert meme["author"] == "u/sunbeam"


def test_reddit_videos_use_preview_and_link_posts_become_articles():
    session = FakeSession({
        "/r/aww/": listing(reddit_post(is_video=True, post_hint="hosted:video", url="https://v.redd.it/xyz")),
        "/r/UpliftingNews/": listing(reddit_post(
            title="Whales return to the Thames", post_hint="link", url="https://news.example.com/whales")),
    })
    client = RedditClient(session)
    video = fetch_reddit(client, SocialSource("reddit", "aww", AWW, min_score=10))[0]
    assert video["kind"] == "video" and video["imageUrl"].startswith("https://preview.redd.it/")
    article = fetch_reddit(client, SocialSource("reddit", "UpliftingNews", None, min_score=10))[0]
    assert article["kind"] == "article" and article["url"] == "https://news.example.com/whales"
    assert article["community"] is None


def test_reddit_block_stops_further_requests():
    session = FakeSession({"reddit.com": FakeResponse({}, 403)})
    client = RedditClient(session)
    assert fetch_reddit(client, SocialSource("reddit", "aww", AWW)) == []
    assert fetch_reddit(client, SocialSource("reddit", "Eyebleach", AWW)) == []
    assert len(session.calls) == 1


def test_lemmy_posts():
    session = FakeSession({"lemmy.world/api/v3/post/list": FakeResponse({"posts": [
        {"post": {"id": 7, "name": "Wholesome cat meme", "url": "https://lemmy.world/pictrs/image/cat.png",
                  "published": "2026-09-26T11:00:00.000000Z", "nsfw": False},
         "counts": {"score": 250, "comments": 9}, "creator": {"name": "kitty"}},
        {"post": {"id": 8, "name": "nsfw", "url": "https://x/y.png", "published": "2026-09-26T11:00:00", "nsfw": True},
         "counts": {"score": 999, "comments": 0}, "creator": {"name": "x"}},
    ]})})
    items = fetch_lemmy(session, SocialSource("lemmy", "wholesomememes@lemmy.world", MEMES, min_score=10))
    assert len(items) == 1
    assert items[0]["discussionUrl"] == "https://lemmy.world/post/7"
    assert items[0]["publishedAt"] == datetime(2026, 9, 26, 11, 0, tzinfo=timezone.utc)


def _social(**kw):
    base = dict(title="Cute dog", url="https://www.reddit.com/r/aww/1", source="r/aww",
                sourceHomepage="https://www.reddit.com/r/aww", publishedAt=NOW, trusted=True,
                kind="image", community=AWW, imageUrl="https://i.redd.it/dog.jpg", score=100, comments=5,
                discussionUrl="https://www.reddit.com/r/aww/1")
    base.update(kw)
    return scrape._item(**base)


def test_votes_refresh_and_reddit_copy_merges_into_rss_article(monkeypatch):
    rss = scrape._item(title="Whales return to the Thames", url="https://news.example.com/whales",
                       source="Good News Network", sourceHomepage="https://gnn", publishedAt=NOW,
                       trusted=True, summary="Great news for whales.")
    reddit_copy = scrape._item(title="Whales return to the Thames", url="https://news.example.com/whales",
                               source="r/UpliftingNews", sourceHomepage="https://reddit", publishedAt=NOW,
                               trusted=True, score=900, comments=40, discussionUrl="https://www.reddit.com/r/Up/1",
                               author="u/x")
    dog = _social()
    monkeypatch.setattr(scrape, "SOURCES", [])
    monkeypatch.setattr(scrape, "fetch_social", lambda session: [reddit_copy, dog])
    monkeypatch.setattr(scrape, "fetch_source", lambda *a: [])

    def build(previous, extra=()):
        monkeypatch.setattr(scrape, "fetch_social", lambda session: [dict(reddit_copy), dict(dog), *extra])
        return scrape.build_feed(requests.Session(), previous, NOW, fetch_images=False, fetch_pets=False)

    # First run: the article arrives only via Reddit, and the dog photo is accepted as-is.
    first = build({"stories": [], "pets": []})
    by_title = {s["title"]: s for s in first["stories"]}
    assert by_title["Cute dog"]["community"] == AWW and by_title["Cute dog"]["score"] == 100
    whales = by_title["Whales return to the Thames"]
    assert whales["score"] == 900 and whales["discussionUrl"] == "https://www.reddit.com/r/Up/1"

    # Second run: scores went up; they're refreshed without duplicating posts.
    dog["score"], reddit_copy["score"] = 250, 1500
    second = build(first)
    assert len(second["stories"]) == len(first["stories"])
    by_title = {s["title"]: s for s in second["stories"]}
    assert by_title["Cute dog"]["score"] == 250
    assert by_title["Whales return to the Thames"]["score"] == 1500


def test_social_communities_are_capped(monkeypatch):
    monkeypatch.setattr(scrape, "MAX_PER_SOCIAL_COMMUNITY", 3)
    monkeypatch.setattr(scrape, "fetch_source", lambda *a: [])
    posts = [_social(title=f"Dog {i}", url=f"https://r/{i}", discussionUrl=f"https://r/{i}") for i in range(6)]
    monkeypatch.setattr(scrape, "fetch_social", lambda session: posts)
    feed = scrape.build_feed(requests.Session(), {"stories": [], "pets": []}, NOW, fetch_images=False, fetch_pets=False)
    assert len(feed["stories"]) == 3


def test_ninegag_tag_posts():
    from goodnews.social import fetch_ninegag
    session = FakeSession({"9gag.com/v1/tag-posts/tag/wholesome": FakeResponse({"data": {"posts": [
        {"id": "a1", "url": "https://9gag.com/gag/a1", "title": "Grandpa learned to text &amp; now sends 40 emojis a day",
         "type": "Photo", "nsfw": 0, "upVoteCount": 5400, "commentsCount": 120, "creationTs": int(TS),
         "images": {"image700": {"url": "https://img-9gag-fun.9cache.com/photo/a1_700b.jpg", "width": 700, "height": 900}}},
        {"id": "a2", "title": "nsfw", "type": "Photo", "nsfw": 1, "upVoteCount": 9999, "creationTs": int(TS),
         "images": {"image700": {"url": "https://img/a2.jpg"}}},
        {"id": "a3", "title": "Too few upvotes", "type": "Photo", "nsfw": 0, "upVoteCount": 3, "creationTs": int(TS),
         "images": {"image700": {"url": "https://img/a3.jpg"}}},
    ]}})})
    items = fetch_ninegag(session, SocialSource("9gag", "wholesome", MEMES, min_score=100))
    assert len(items) == 1
    meme = items[0]
    assert meme["title"] == "Grandpa learned to text & now sends 40 emojis a day"
    assert meme["imageUrl"].endswith("a1_700b.jpg") and (meme["imageWidth"], meme["imageHeight"]) == (700, 900)
    assert meme["url"] == "https://9gag.com/gag/a1" and meme["source"] == "9GAG · wholesome"


def test_ninegag_block_is_harmless():
    from goodnews.social import fetch_ninegag
    session = FakeSession({"9gag.com": FakeResponse({}, 403)})
    assert fetch_ninegag(session, SocialSource("9gag", "wholesome", MEMES)) == []


def test_imgur_needs_client_id_and_parses_gallery(monkeypatch):
    from goodnews.social import fetch_imgur
    route = {"api.imgur.com/3/gallery/t/aww": FakeResponse({"data": {"items": [
        {"id": "g1", "title": "My cat supervising the laundry", "is_album": False, "nsfw": False, "points": 800,
         "comment_count": 12, "datetime": int(TS), "link": "https://i.imgur.com/g1.jpg", "width": 900, "height": 1200},
        {"id": "g2", "title": "Album of puppies", "is_album": True, "nsfw": False, "points": 500, "comment_count": 3,
         "datetime": int(TS), "link": "https://imgur.com/a/g2",
         "images": [{"id": "p1", "link": "https://i.imgur.com/p1.mp4", "animated": True, "width": 640, "height": 640}]},
    ]}})}
    monkeypatch.delenv("IMGUR_CLIENT_ID", raising=False)
    assert fetch_imgur(FakeSession(route), SocialSource("imgur", "aww", AWW, min_score=100)) == []
    monkeypatch.setenv("IMGUR_CLIENT_ID", "abc")
    items = fetch_imgur(FakeSession(route), SocialSource("imgur", "aww", AWW, min_score=100))
    by_title = {i["title"]: i for i in items}
    assert by_title["My cat supervising the laundry"]["imageUrl"] == "https://i.imgur.com/g1.jpg"
    puppies = by_title["Album of puppies"]
    assert puppies["kind"] == "video" and puppies["imageUrl"] == "https://i.imgur.com/p1h.jpg"
    assert puppies["url"] == "https://imgur.com/a/g2"


def test_old_mastodon_posts_are_removed(monkeypatch):
    monkeypatch.setattr(scrape, "fetch_source", lambda *a: [])
    monkeypatch.setattr(scrape, "fetch_social", lambda session: [])
    toot = {"id": "t1", "kind": "image", "title": "Cat in a box", "summary": "", "url": "https://mastodon.social/@x/1",
            "imageUrl": "https://files/cat.jpg", "source": "#CatsOfMastodon", "sourceHomepage": "https://m",
            "author": "@x", "publishedAt": "2026-09-26T10:00:00Z", "community": AWW, "category": AWW,
            "region": "Global", "uplift": 7, "score": 50, "comments": 1, "discussionUrl": "https://mastodon.social/@x/1"}
    feed = scrape.build_feed(requests.Session(), {"stories": [toot], "pets": []}, NOW, fetch_images=False, fetch_pets=False)
    assert feed["stories"] == []
