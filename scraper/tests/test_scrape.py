import shutil
from datetime import datetime, timezone
from email.utils import format_datetime
from pathlib import Path

import pytest
import requests

from goodnews import keywords
from goodnews.pets import _breed_from_dog_url, pets_for_today
from goodnews.scrape import build_feed, canonical_url, clean_text

FIXTURES = Path(__file__).parent / "fixtures"
NOW = datetime(2026, 9, 26, 12, 0, tzinfo=timezone.utc)


@pytest.fixture
def fixtures(tmp_path):
    recent = format_datetime(NOW.replace(hour=8))
    for f in FIXTURES.glob("*.xml"):
        (tmp_path / f.name).write_text(f.read_text().replace("{{RECENT}}", recent))
    return tmp_path


def test_build_feed_keeps_only_good_news(fixtures):
    feed = build_feed(requests.Session(), {"stories": [], "pets": []}, NOW,
                      fixtures=fixtures, fetch_images=False, fetch_pets=False)
    titles = [s["title"] for s in feed["stories"]]
    assert "Rescued Sea Turtles Return to the Ocean After Months of Rehab in Florida" in titles
    assert "Village in Kenya Gets Clean Water for the First Time Thanks to Solar Pumps" in titles
    assert "Endangered tigers make remarkable comeback in Nepal as numbers triple" in titles
    assert not any("Killed" in t or "Missile" in t or "interest rates" in t or "Ancient" in t for t in titles)

    by_title = {s["title"]: s for s in feed["stories"]}
    turtle = by_title["Rescued Sea Turtles Return to the Ocean After Months of Rehab in Florida"]
    assert turtle["imageUrl"] == "https://www.goodnewsnetwork.org/wp-content/uploads/turtle.jpg"
    assert "appeared first" not in turtle["summary"]
    assert turtle["url"].startswith("https://www.goodnewsnetwork.org/rescued-sea-turtles-return/")
    assert turtle["category"] == "Animals"
    assert turtle["region"] == "North America"

    kenya = by_title["Village in Kenya Gets Clean Water for the First Time Thanks to Solar Pumps"]
    assert kenya["imageUrl"] == "https://www.goodnewsnetwork.org/wp-content/uploads/kenya.jpg"
    assert kenya["region"] == "Africa"

    tiger = by_title["Endangered tigers make remarkable comeback in Nepal as numbers triple"]
    assert tiger["imageUrl"] == "https://ichef.bbci.co.uk/tiger.jpg"
    assert tiger["region"] == "Asia"


def test_previous_stories_are_carried_forward_and_not_duplicated(fixtures):
    first = build_feed(requests.Session(), {"stories": [], "pets": []}, NOW,
                       fixtures=fixtures, fetch_images=False, fetch_pets=False)
    second = build_feed(requests.Session(), first, NOW,
                        fixtures=fixtures, fetch_images=False, fetch_pets=False)
    assert [s["id"] for s in second["stories"]] == [s["id"] for s in first["stories"]]


def test_canonical_url_strips_tracking():
    assert canonical_url("https://Example.com/a?utm_source=x&id=3#frag") == "https://example.com/a?id=3"


def test_clean_text_truncates_on_word_boundary():
    out = clean_text("<p>" + "word " * 200 + "</p>", limit=50)
    assert out.endswith("…") and len(out) <= 51


def test_keyword_filter():
    assert keywords.passes_keyword_filter("Volunteers plant a million trees", "", trusted=True)
    assert not keywords.passes_keyword_filter("Two killed in crash", "", trusted=True)
    assert not keywords.passes_keyword_filter("Heartwarming rescue amid war", "", trusted=False)
    assert keywords.passes_keyword_filter(
        "Scientists celebrate breakthrough malaria vaccine", "A promising, lifesaving milestone.", trusted=False)


def test_breed_from_dog_url():
    assert _breed_from_dog_url("https://images.dog.ceo/breeds/hound-afghan/n02088094_1003.jpg") == "Afghan Hound"
    assert _breed_from_dog_url("https://images.dog.ceo/breeds/pug/x.jpg") == "Pug"


def test_pets_are_stable_within_a_day():
    history = [
        {"kind": "kitten", "date": "2026-09-26", "imageUrl": "https://a/cat.jpg", "name": "Mochi", "caption": "c", "breed": None},
        {"kind": "puppy", "date": "2026-09-26", "imageUrl": "https://a/dog.jpg", "name": "Gus", "caption": "c", "breed": "Pug"},
    ]

    class NoNetwork(requests.Session):
        def get(self, *a, **k):
            raise AssertionError("should not fetch when today's pets exist")

    assert pets_for_today(NoNetwork(), NOW.date(), history) == sorted(history, key=lambda p: p["kind"], reverse=True)


def test_celebrity_and_royal_news_is_off_topic():
    assert keywords.is_off_topic("Prince William visits new children's hospital")
    assert keywords.is_off_topic("King Charles plants a tree at Sandringham")
    assert keywords.is_off_topic("Taylor Swift donates to food banks on tour")
    assert keywords.is_off_topic("Hollywood actor surprises fans")
    assert not keywords.is_off_topic("Volunteers plant a million trees across Kenya")
    assert not keywords.is_off_topic("Scientists celebrate a malaria vaccine breakthrough")
    assert not keywords.is_off_topic("Prince Edward Island opens a new wind farm")


def test_sport_is_removed_entirely(fixtures):
    now_iso = "2026-09-26T10:00:00Z"
    base = {"kind": "article", "summary": "", "imageUrl": None, "source": "S", "sourceHomepage": "https://x",
            "author": "S", "publishedAt": now_iso, "region": "Europe", "uplift": 9, "score": None,
            "comments": None, "discussionUrl": None}
    previous = [
        dict(base, id="sp", title="Underdog team wins the cup", url="https://x/1", community="Sport", category="Sport"),
        dict(base, id="sp2", title="Marathon runner raises money for hospital", url="https://x/2",
             community="Community", category="Community"),
        dict(base, id="royal", title="Princess of Wales opens garden", url="https://x/3", community="Culture", category="Culture"),
        dict(base, id="de", title="Neue Solaranlage versorgt das ganze Dorf mit Strom", url="https://x/4",
             community="Environment", category="Environment"),
        dict(base, id="ok", title="Volunteers plant a million trees", url="https://x/5", community="Environment",
             category="Environment"),
    ]
    feed = build_feed(requests.Session(), {"stories": previous, "pets": []}, NOW,
                      fixtures=fixtures, fetch_images=False, fetch_pets=False)
    ids = {s["id"] for s in feed["stories"]}
    assert "ok" in ids
    assert not ids & {"sp", "sp2", "royal", "de"}


def test_english_only():
    assert keywords.is_english("Volunteers plant a million trees across Kenya")
    assert keywords.is_english("Meet Pickle, who has claimed the laundry basket")
    assert keywords.is_english("Cute dog")
    assert keywords.is_english("Beyoncé-free café reopens in the village")
    assert not keywords.is_english("Neue Solaranlage versorgt das ganze Dorf mit Strom")
    assert not keywords.is_english("Le village a enfin l'eau potable grâce aux pompes solaires")
    assert not keywords.is_english("Los voluntarios plantan un millón de árboles en la ciudad")
    assert not keywords.is_english("Il nuovo parco è aperto a tutti i bambini della città")
    assert not keywords.is_english("猫が救助されました")
    assert not keywords.is_english("Кошка спасена волонтёрами")


def test_rejected_stories_are_not_rechecked(fixtures, monkeypatch):
    from goodnews import scrape
    first = build_feed(requests.Session(), {"stories": [], "pets": []}, NOW,
                       fixtures=fixtures, fetch_images=False, fetch_pets=False)
    assert first["rejected"]  # e.g. the missile-attack headline
    seen = []
    real = scrape.select_good_news
    monkeypatch.setattr(scrape, "select_good_news", lambda c, u: seen.extend(c) or real(c, u))
    build_feed(requests.Session(), first, NOW, fixtures=fixtures, fetch_images=False, fetch_pets=False)
    assert seen == []


def test_feed_focuses_on_the_west(fixtures):
    from goodnews import scrape
    base = {"kind": "article", "summary": "", "imageUrl": None, "sourceHomepage": "https://x", "author": "S",
            "publishedAt": "2026-09-26T10:00:00Z", "community": "Community", "category": "Community",
            "score": None, "comments": None, "discussionUrl": None}
    western = [dict(base, id=f"w{i}", title=f"Volunteers restore village hall {i}", url=f"https://x/w{i}",
                    source="BBC News", region="Europe", uplift=6) for i in range(20)]
    indian = [dict(base, id=f"i{i}", title=f"Bengaluru volunteers plant trees {i}", url=f"https://x/i{i}",
                   source="Hindustan Times", region="Global", uplift=5 + i % 5) for i in range(10)]
    feed = build_feed(requests.Session(), {"stories": western + indian, "pets": []}, NOW,
                      fixtures=fixtures, fetch_images=False, fetch_pets=False)
    news = [s for s in feed["stories"] if s["kind"] == "article"]
    kept_indian = [s for s in news if s["id"].startswith("i")]
    assert len([s for s in news if s["id"].startswith("w")]) == 20
    assert 1 <= len(kept_indian) <= 3  # about 10% of the news at most
    assert all(s["region"] == "Asia" for s in kept_indian)  # outlet tells us the region
    assert all(s["uplift"] <= scrape.NON_WESTERN_MAX_UPLIFT for s in kept_indian)  # ranked low
    top = scrape.top_stories(feed["stories"], NOW, n=10)
    assert not any(s["id"].startswith("i") for s in top)  # never leads the feed
    dropped = {s["id"] for s in indian} - {s["id"] for s in kept_indian}
    assert dropped <= set(feed["rejected"])  # and not re-checked every run
    assert scrape.is_western({"region": "Global"}) and not scrape.is_western({"region": "Africa"})


def test_region_for_source():
    assert keywords.region_for_source("The Times of India") == "Asia"
    assert keywords.region_for_source("Premium Times") == "Africa"
    assert keywords.region_for_source("BBC News") is None
    assert keywords.guess_region("Kerala village celebrates new school", "") == "Asia"



# Headlines that actually reached the top of the live site (27 Sep 2026) and must not.
LIVE_BAD = [
    ("Ethiopians celebrate Meskel and call for peace amid fighting", "", "Al Jazeera", "https://www.aljazeera.com"),
    ("India's space sector: Slow burn, hard-won successes - and then liftoff", "", "Business Standard",
     "https://www.business-standard.com"),
    ("Modi spotlights Assam's wildlife conservation efforts in 'Mann Ki Baat'", "", "The Times of India",
     "https://timesofindia.indiatimes.com"),
    ("Ashutosh Ranka, CJP volunteers detained in Assam during peaceful meeting", "", "thehindu.com",
     "https://www.thehindu.com"),
    ("Good news for central government employees: DA hike announced", "", "Moneycontrol", "https://www.moneycontrol.com"),
    ("New York City Collects $131 Million From DoorDash for Delivery Workers Unfairly Paid After $13 Billion Profit",
     "", "Good News Network", "https://www.goodnewsnetwork.org"),
    ("They said there's no English rhyme for \u201csilver.\u201d Eminem just obliterated the challenge.", "", "Upworthy",
     "https://www.upworthy.com"),
]


def test_live_bad_headlines_never_lead(fixtures):
    from goodnews import scrape
    base = {"kind": "article", "summary": "", "imageUrl": None, "author": "S", "publishedAt": "2026-09-26T11:00:00Z",
            "community": "Community", "category": "Community", "region": "Global", "uplift": 8, "score": None,
            "comments": None, "discussionUrl": None}
    bad = [dict(base, id=f"bad{i}", title=t, summary=sm, url=f"https://x/bad{i}", source=src, sourceHomepage=home)
           for i, (t, sm, src, home) in enumerate(LIVE_BAD)]
    good = [dict(base, id=f"g{i}", title=f"Volunteers plant a thousand trees in Devon {i}", url=f"https://x/g{i}",
                 source="Good News Network", sourceHomepage="https://www.goodnewsnetwork.org", region="Europe")
            for i in range(10)]
    feed = build_feed(requests.Session(), {"stories": bad + good, "pets": []}, NOW,
                      fixtures=fixtures, fetch_images=False, fetch_pets=False)
    kept = {s["id"]: s for s in feed["stories"]}
    # Politics, conflict, detentions, money stories and dropped sources are gone entirely...
    for i in (0, 2, 3, 4, 5, 6):
        assert f"bad{i}" not in kept, LIVE_BAD[i][0]
    # ...and anything non-Western that's left is ranked well below the good stories.
    top = scrape.top_stories(feed["stories"], NOW, n=5)
    assert all(s["id"].startswith("g") or s["kind"] != "article" for s in top)


def test_keyword_uplift_favours_good_news_outlets():
    from goodnews import scrape
    assert scrape.keyword_uplift("Rescued turtles return to the sea", "", trusted=True) >= 6
    assert scrape.keyword_uplift("Rescued turtles return to the sea after a remarkable recovery", "", trusted=False) <= 5


def test_politics_is_unwanted():
    from goodnews import scrape
    story = {"kind": "article", "title": "Prime minister opens new park", "summary": ""}
    assert scrape.unwanted(story)
    assert not scrape.unwanted({"kind": "article", "title": "Volunteers open new park", "summary": ""})
    assert scrape.unwanted({"kind": "image", "title": "Ugly truths of everyday sufferings", "summary": ""})


def test_charity_headlines_are_not_mistaken_for_money_news():
    from goodnews import scrape
    story = {"kind": "article", "title": "Nonprofit gives free bikes to 500 kids", "summary": "", "community": "Community"}
    assert not scrape.unwanted(story)
    assert not scrape.unwanted(dict(story, title="Non-profit café trains young people for their first jobs"))
    assert scrape.unwanted(dict(story, title="Shareholders cheer record profits at Nike"))
