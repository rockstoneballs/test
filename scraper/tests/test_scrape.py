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
