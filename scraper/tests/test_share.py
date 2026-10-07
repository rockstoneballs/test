from datetime import datetime, timedelta, timezone

from goodnews import share

NOW = datetime(2026, 10, 7, 12, tzinfo=timezone.utc)


def story(i: int, days_old: float = 0, **extra) -> dict:
    return {
        "id": f"{i:016x}",
        "kind": "article",
        "title": f"Volunteers plant {i} trees <in> town & country",
        "summary": "A town came together.",
        "body": "First paragraph about the trees.\n\nSecond paragraph.",
        "url": f"https://example.org/story/{i}?a=1&b=2",
        "imageUrl": "https://example.org/pic.jpg",
        "source": "Example News",
        "community": "Environment",
        "publishedAt": (NOW - timedelta(days=days_old)).isoformat().replace("+00:00", "Z"),
        **extra,
    }


def test_merge_keeps_recent_posts_and_drops_old_and_rejected():
    previous = [share.record(story(1, days_old=10)), share.record(story(2, days_old=40)), share.record(story(3, days_old=5))]
    merged = share.merge(previous, [story(4)], NOW, rejected={story(3)["id"]})
    assert [r["id"] for r in merged] == [story(4)["id"], story(1)["id"]]


def test_current_feed_overrides_archived_copy():
    old = share.record(story(1, days_old=2))
    merged = share.merge([old], [story(1, days_old=2, title="Updated headline here")], NOW)
    assert merged[0]["title"] == "Updated headline here"


def test_merge_ignores_bad_ids():
    assert share.merge([], [story(1, id="../../etc")], NOW) == []


def test_excerpt_is_short_and_ends_at_a_sentence():
    long_para = " ".join(["This is a sentence of eight words here."] * 20)
    text = share._excerpt(long_para)
    assert len(text.split()) <= share.EXCERPT_WORDS
    assert text.endswith(".")
    assert share._excerpt("One.\n\nTwo.") == "One.\n\nTwo."


def test_page_has_preview_tags_and_escapes_text():
    page = share.render(share.record(story(7)), "https://example.github.io/test/", "https://example.org/app.apk")
    assert '<meta property="og:title" content="Volunteers plant 7 trees &lt;in&gt; town &amp; country">' in page
    assert '<meta property="og:image" content="https://example.org/pic.jpg">' in page
    assert '<meta property="og:url" content="https://example.github.io/test/s/0000000000000007/">' in page
    assert 'href="https://example.org/story/7?a=1&amp;b=2"' in page
    assert "summary_large_image" in page
    assert "<in>" not in page


def test_page_without_image_uses_small_card():
    page = share.render(share.record(story(8, imageUrl=None)), "https://x/", "https://y/")
    assert "og:image" not in page and 'content="summary"' in page


def test_write_creates_pages_and_archive(tmp_path):
    shares = share.merge([], [story(1), story(2)], NOW)
    assert share.write(tmp_path, shares, "https://x/", "https://y/") == 2
    assert (tmp_path / "s" / story(1)["id"] / "index.html").exists()
    assert len(share.load_archive(str(tmp_path / "shares.json"), None)) == 2


def test_archive_and_site_urls_come_from_the_feed_url():
    assert share.archive_url("https://a.github.io/test/feed.json") == "https://a.github.io/test/shares.json"
    assert share.site_url_from("https://a.github.io/test/feed.json") == "https://a.github.io/test/"
    assert share.archive_url(None) is None
