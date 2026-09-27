import json

import requests

from goodnews import articles, keywords
from goodnews.scrape import add_article_text, unwanted


def test_excerpt_keeps_the_opening_paragraphs_up_to_the_word_cap():
    paras = [f"Paragraph {i} " + "word " * 60 for i in range(6)]
    text = articles.excerpt(paras, max_words=150)
    assert text.startswith("Paragraph 0")
    assert len(text.split()) <= 150
    assert text.count("\n\n") == 1  # two whole paragraphs fit


def test_excerpt_skips_boilerplate_and_scraps():
    paras = ["Advertisement", "Short.", "Sign up for our newsletter to get good news in your inbox every week, free.",
             "Volunteers in Devon planted ten thousand trees on Saturday, restoring a stretch of ancient woodland."]
    assert articles.excerpt(paras) == paras[-1]


def test_paragraphs_from_feed_html():
    html = ("<p>First paragraph of the story, which is long enough to keep around.</p>"
            "<figure><img src='x.jpg'><figcaption>Photo: someone</figcaption></figure>"
            "<p>Second &amp; last paragraph.</p><script>var x = 1;</script>")
    assert articles.paragraphs_from_html(html) == [
        "First paragraph of the story, which is long enough to keep around.", "Second & last paragraph."]


PAGE = """<html><head><title>Otters return</title>
<meta property="og:image" content="https://example.org/otter.jpg">{robots}</head><body>
<nav>Home News Sport</nav><article><h1>Otters return to every county in England</h1>
<p>Otters have now returned to every county in England, forty years after they were close to vanishing
from the country's rivers, according to a survey published on Tuesday.</p>
<p>The recovery follows a ban on the pesticides that poisoned them and decades of work to clean up
rivers, conservationists said.</p>
<p>Volunteers spent two years looking for otter droppings and footprints along more than 3,000 sites.</p>
</article><footer>All rights reserved</footer></body></html>"""


def test_extract_page_reads_the_article_not_the_chrome():
    body = articles.extract_page(PAGE.format(robots=""))
    assert body.startswith("Otters have now returned to every county in England")
    assert "Volunteers spent two years" in body
    assert "Home News" not in body and "All rights reserved" not in body
    assert articles.og_image_in(PAGE) == "https://example.org/otter.jpg"


def test_pages_that_forbid_snippets_are_respected():
    for robots in ('<meta name="robots" content="index, nosnippet">', '<meta name="googlebot" content="max-snippet:0">'):
        assert articles.extract_page(PAGE.format(robots=robots)) == ""


class FakeResponse:
    def __init__(self, text, url="", content_type="text/html; charset=utf-8"):
        self.text, self.url, self.encoding = text, url, "utf-8"
        self.content = text.encode()
        self.headers = {"Content-Type": content_type}
        self.raw = self

    def read(self, n, decode_content=True):
        return self.content[:n]

    def raise_for_status(self):
        pass

    def close(self):
        pass


class FakeSession:
    """Google News article page -> batchexecute -> the publisher's page."""
    def __init__(self):
        self.posted = None

    def get(self, url, **kw):
        if url.startswith("https://news.google.com/rss/articles/"):
            return FakeResponse('<c-wiz><div jscontroller="x" data-n-a-sg="SIG123" data-n-a-ts="1790000000"></div></c-wiz>')
        assert url == "https://www.bbc.co.uk/news/otters"
        return FakeResponse(PAGE.format(robots=""), url=url)

    def post(self, url, data, **kw):
        self.posted = data
        inner = json.dumps(["garturlres", "https://www.bbc.co.uk/news/otters", 1])
        return FakeResponse(")]}'\n\n" + json.dumps([["wrb.fr", "Fbv4je", inner, None, None, None, "generic"], ["di", 42]]))


def test_google_news_links_are_decoded_and_read():
    session = FakeSession()
    story = {"kind": "article", "url": "https://news.google.com/rss/articles/CBMiABC123?oc=5", "summary": "",
             "imageUrl": None, "source": "BBC News"}
    add_article_text(session, [story])
    assert "SIG123" in session.posted and "CBMiABC123" in session.posted
    assert story["url"] == "https://www.bbc.co.uk/news/otters"
    assert story["body"].startswith("Otters have now returned")
    assert story["summary"].startswith("Otters have now returned")  # cards get a summary too
    assert story["imageUrl"] == "https://example.org/otter.jpg"


def test_unreachable_pages_are_retried_later():
    class Down:
        def get(self, *a, **kw):
            raise requests.ConnectionError("down")
    story = {"kind": "article", "url": "https://example.org/a", "summary": "", "imageUrl": None}
    add_article_text(Down(), [story])
    assert "body" not in story


CLICKBAIT = [
    "Listen, Understand, Validate: How to Care for Loved Ones With Anxiety or Depression",
    "They said there's no English rhyme for “silver.” Eminem just obliterated the challenge.",
    "Stanislaus National Forest Seeks Volunteer Groups That Are Interested In Helping",
    "This Dad's Reaction Will Melt Your Heart",
    "10 Things That Made Us Smile This Week",
    "Could this new battery change everything?",
    "Heartwarming moment toddler meets his baby sister goes viral",
    "The internet is loving this grandma's garden",
    "You won't believe what this farmer found",
    "Volunteers needed to help deliver life-saving training",
    "From fire starters to compost: what to do with dryer lint",
    "Good News in History September 24",
    "Maryland's Natural Beauty Showcased in State Calendar Photo Contest: See the Winning Images",
]
REAL_NEWS = [
    "Dog rescued from cliff ledge at landmark waterfall",
    "Volunteers turn out for successful East Laurinburg community cleanup",
    "Nike Founder Phil Knight Gives Away a Billion Dollars to a Public University",
    "Australia's main grid hits 80% renewables on consecutive days, rooftop solar tops half of demand",
    "400 volunteers plant 10,000 trees in Devon",
    "Rare white kiwi hatches at Pukaha",
    "Woman, 102, finally gets her high school diploma",
    "Why farmers are pairing crops with solar panels",
]


def test_clickbait_headlines():
    for t in CLICKBAIT:
        assert keywords.is_clickbait(t), t
    for t in REAL_NEWS:
        assert not keywords.is_clickbait(t), t


def test_clickbait_and_tabloids_are_unwanted():
    base = {"kind": "article", "summary": "", "community": "Community", "source": "BBC News",
            "sourceHomepage": "https://www.bbc.co.uk"}
    assert unwanted(dict(base, title=CLICKBAIT[3]))
    assert unwanted(dict(base, title="Otters return to every county in England", source="Daily Mail",
                         sourceHomepage="https://www.dailymail.co.uk"))
    assert not unwanted(dict(base, title="Otters return to every county in England"))
    # Memes are allowed to be silly.
    assert not unwanted(dict(base, kind="image", title="You won't believe this cat"))


def test_gloomy_stories_found_in_the_live_feed_are_caught():
    from goodnews.scrape import grim_inside
    base = {"kind": "article", "summary": "", "community": "Community", "sourceHomepage": ""}
    assert unwanted(dict(base, title="Radio signals heard from planet beyond Earth's solar system", source="the-sun.com",
                         sourceHomepage="https://www.the-sun.com"))
    assert unwanted(dict(base, title="Governor urges graduates to become job creators", source="The Hindu"))
    assert unwanted(dict(base, title="Landlords quitting 'in droves' since Renters' Rights Act", source="Lemmy"))
    for t in ["All seven baby rabbits rescued in Singapore's Seletar die",
              "Global coral reefs get less time to recover as oceans heat up, report finds"]:
        assert not keywords.passes_keyword_filter(t, "", trusted=False), t
    story = {"source": "KSWO", "checkedBy": "keywords",
             "body": "Wildfire smoke forced volunteers to cut the clean-up short.\n\nThey will return next week."}
    assert grim_inside(story)


def test_excerpts_drop_repeated_headlines_syndication_notes_and_bullets():
    paras = ["Stem cells reverse stroke damage in mice",
             "This story was originally published by Grist. Sign up for Grist's weekly newsletter here.",
             "This article was originally written by Kate Pounds for SWNS, the UK's largest news agency.",
             "- Stem cell transplants helped regenerate stroke-damaged brain tissue in mice, researchers said."]
    assert articles.excerpt(paras, title="Stem cells reverse stroke damage in mice") == (
        "Stem cell transplants helped regenerate stroke-damaged brain tissue in mice, researchers said.")


def test_pages_without_a_declared_charset_are_read_as_utf8():
    raw = "The Solar System\u2019s first solid bodies".encode("utf-8")
    assert articles.decode_page(raw, "ISO-8859-1") == "The Solar System\u2019s first solid bodies"
    assert articles.decode_page("caf\xe9".encode("cp1252"), None) == "caf\xe9"


def test_photo_credits_and_sad_turns():
    from goodnews.scrape import grim_inside
    assert articles.excerpt(["A leopardus cat, the first new wild feline species in 100 years. Photograph: Fernando Faciole/Reuters"]) == ""
    assert grim_inside({"source": "Good Good Good", "checkedBy": "keywords",
                        "body": "Rangers woke up to devastating news: their colleague had passed away."})
