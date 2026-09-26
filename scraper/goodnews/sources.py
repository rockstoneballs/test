"""The feeds we scrape.

Two kinds of source:

* ``trusted`` outlets publish only constructive / solutions / good news, so
  everything they post is a candidate (minus a small hard-block list).
* Mainstream outlets are scraped too, but each story must pass the positivity
  filter (Claude if ANTHROPIC_API_KEY is set, keyword scoring otherwise) before
  it makes it into the feed. This is what gives the app global coverage.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class Source:
    name: str
    url: str
    trusted: bool
    homepage: str


SOURCES: list[Source] = [
    # Dedicated good-news / solutions-journalism outlets.
    Source("Good News Network", "https://www.goodnewsnetwork.org/feed/", True,
           "https://www.goodnewsnetwork.org"),
    Source("Positive News", "https://www.positive.news/feed/", True,
           "https://www.positive.news"),
    Source("Reasons to be Cheerful", "https://reasonstobecheerful.world/feed/", True,
           "https://reasonstobecheerful.world"),
    Source("The Optimist Daily", "https://www.optimistdaily.com/feed/", True,
           "https://www.optimistdaily.com"),
    Source("YES! Magazine", "https://www.yesmagazine.org/feed", True,
           "https://www.yesmagazine.org"),
    Source("The Guardian — The Upside", "https://www.theguardian.com/world/series/the-upside/rss", True,
           "https://www.theguardian.com/world/series/the-upside"),
    Source("Good Good Good", "https://www.goodgoodgood.co/articles/rss.xml", True,
           "https://www.goodgoodgood.co"),
    # Mainstream / science outlets, filtered for positivity.
    Source("BBC News", "https://feeds.bbci.co.uk/news/world/rss.xml", False,
           "https://www.bbc.co.uk/news"),
    Source("BBC Science", "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", False,
           "https://www.bbc.co.uk/news/science_and_environment"),
    Source("NPR", "https://feeds.npr.org/1001/rss.xml", False, "https://www.npr.org"),
    Source("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", False,
           "https://www.aljazeera.com"),
    Source("ScienceDaily", "https://www.sciencedaily.com/rss/top.xml", False,
           "https://www.sciencedaily.com"),
    Source("Mongabay", "https://news.mongabay.com/feed/", False, "https://news.mongabay.com"),
]
