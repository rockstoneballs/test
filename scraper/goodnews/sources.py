"""Everything we scrape.

Three kinds of source:

* RSS feeds. ``trusted`` outlets publish only constructive / solutions / good
  news, so everything they post is a candidate (minus a small hard-block
  list). Mainstream outlets and Google News searches are scraped too, but each
  story must pass the positivity filter (Claude if ANTHROPIC_API_KEY is set,
  keyword scoring otherwise). This is what gives the feed global coverage.
* Reddit communities: good-news link posts (r/UpliftingNews) plus wholesome
  memes, cute animals and "made me smile" posts. These carry real upvote and
  comment counts, which drive the Hot/Top sorting in the apps.
* Lemmy communities and Mastodon hashtags: the fediverse equivalents, with
  open APIs. They keep memes and cute animals flowing if Reddit blocks us.

A dead or blocked source is logged and skipped; it never fails the run.
"""

from dataclasses import dataclass
from urllib.parse import quote_plus

# Community names shown in the apps as s/<name>. News topics come from the
# classifier; the social communities below are fixed per source.
MEMES = "WholesomeMemes"
AWW = "Aww"
SMILES = "MadeMeSmile"
SOCIAL_COMMUNITIES = [MEMES, AWW, SMILES]


@dataclass(frozen=True)
class Source:
    name: str
    url: str
    trusted: bool
    homepage: str


@dataclass(frozen=True)
class SocialSource:
    """A Reddit subreddit or Lemmy community.

    ``community`` is None for link-post communities (their posts are news
    articles, classified like any other story); otherwise it's the fixed
    community image posts are filed under.
    """
    platform: str  # "reddit" | "lemmy" | "mastodon"
    name: str  # subreddit; community@instance for Lemmy; hashtag@instance for Mastodon
    community: str | None
    min_score: int = 50
    limit: int = 15


def _google_news(query: str) -> Source:
    url = f"https://news.google.com/rss/search?q={quote_plus(query)}+when:1d&hl=en&gl=US&ceid=US:en"
    return Source(f"Google News: {query}", url, False, "https://news.google.com")


SOURCES: list[Source] = [
    # Dedicated good-news / solutions-journalism outlets.
    Source("Good News Network", "https://www.goodnewsnetwork.org/feed/", True, "https://www.goodnewsnetwork.org"),
    Source("Positive News", "https://www.positive.news/feed/", True, "https://www.positive.news"),
    Source("Reasons to be Cheerful", "https://reasonstobecheerful.world/feed/", True, "https://reasonstobecheerful.world"),
    Source("The Optimist Daily", "https://www.optimistdaily.com/feed/", True, "https://www.optimistdaily.com"),
    Source("YES! Magazine", "https://www.yesmagazine.org/feed", True, "https://www.yesmagazine.org"),
    Source("The Guardian — The Upside", "https://www.theguardian.com/world/series/the-upside/rss", True,
           "https://www.theguardian.com/world/series/the-upside"),
    Source("Good Good Good", "https://www.goodgoodgood.co/articles/rss.xml", True, "https://www.goodgoodgood.co"),
    Source("Nice News", "https://nicenews.com/feed/", True, "https://nicenews.com"),
    Source("Inspire More", "https://www.inspiremore.com/feed/", True, "https://www.inspiremore.com"),
    Source("Squirrel News", "https://squirrel-news.net/feed/", True, "https://squirrel-news.net"),
    Source("The Better India", "https://thebetterindia.com/feed/", True, "https://thebetterindia.com"),
    Source("Sunny Skyz", "https://www.sunnyskyz.com/rss_tebow.php", True, "https://www.sunnyskyz.com"),
    # Mainstream, regional and science outlets, filtered for positivity.
    Source("BBC News", "https://feeds.bbci.co.uk/news/world/rss.xml", False, "https://www.bbc.co.uk/news"),
    Source("BBC Science", "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", False,
           "https://www.bbc.co.uk/news/science_and_environment"),
    Source("NPR", "https://feeds.npr.org/1001/rss.xml", False, "https://www.npr.org"),
    Source("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", False, "https://www.aljazeera.com"),
    Source("The Guardian Environment", "https://www.theguardian.com/environment/rss", False,
           "https://www.theguardian.com/environment"),
    Source("The Guardian Science", "https://www.theguardian.com/science/rss", False, "https://www.theguardian.com/science"),
    Source("DW", "https://rss.dw.com/rdf/rss-en-all", False, "https://www.dw.com"),
    Source("France 24", "https://www.france24.com/en/rss", False, "https://www.france24.com"),
    Source("CBC", "https://www.cbc.ca/webfeed/rss/rss-world", False, "https://www.cbc.ca/news"),
    Source("ABC News (Australia)", "https://www.abc.net.au/news/feed/51120/rss.xml", False, "https://www.abc.net.au/news"),
    Source("AllAfrica", "https://allafrica.com/tools/headlines/rdf/latest/headlines.rdf", False, "https://allafrica.com"),
    Source("ScienceDaily", "https://www.sciencedaily.com/rss/top.xml", False, "https://www.sciencedaily.com"),
    Source("Phys.org", "https://phys.org/rss-feed/", False, "https://phys.org"),
    Source("ScienceAlert", "https://www.sciencealert.com/feed", False, "https://www.sciencealert.com"),
    Source("New Atlas", "https://newatlas.com/index.rss", False, "https://newatlas.com"),
    Source("NASA", "https://www.nasa.gov/news-release/feed/", False, "https://www.nasa.gov"),
    Source("Smithsonian", "https://www.smithsonianmag.com/rss/latest_articles/", False, "https://www.smithsonianmag.com"),
    Source("Mongabay", "https://news.mongabay.com/feed/", False, "https://news.mongabay.com"),
    # Google News searches: a wide net across thousands of outlets worldwide.
    _google_news('"good news"'),
    _google_news("heartwarming"),
    _google_news("scientists breakthrough"),
    _google_news("conservation success"),
    _google_news("rescued animal"),
    _google_news("record renewable energy"),
]

SOCIAL_SOURCES: list[SocialSource] = [
    # Good-news link posts: the linked article is the story, the thread is the discussion.
    SocialSource("reddit", "UpliftingNews", None, min_score=100, limit=25),
    SocialSource("reddit", "goodnews", None, min_score=20, limit=15),
    # Wholesome memes.
    SocialSource("reddit", "wholesomememes", MEMES, min_score=200, limit=25),
    SocialSource("reddit", "wholesome", MEMES, min_score=100, limit=10),
    # Cute animals.
    SocialSource("reddit", "aww", AWW, min_score=300, limit=20),
    SocialSource("reddit", "Eyebleach", AWW, min_score=200, limit=15),
    SocialSource("reddit", "rarepuppers", AWW, min_score=100, limit=10),
    SocialSource("reddit", "IllegallySmolCats", AWW, min_score=100, limit=10),
    # People (and animals) being lovely.
    SocialSource("reddit", "MadeMeSmile", SMILES, min_score=300, limit=20),
    SocialSource("reddit", "HumansBeingBros", SMILES, min_score=200, limit=15),
    SocialSource("reddit", "AnimalsBeingBros", SMILES, min_score=200, limit=10),
    # Lemmy (open API, no key needed).
    SocialSource("lemmy", "wholesomememes@lemmy.world", MEMES, min_score=20, limit=15),
    SocialSource("lemmy", "aww@lemmy.world", AWW, min_score=20, limit=10),
    SocialSource("lemmy", "mademesmile@lemmy.world", SMILES, min_score=20, limit=10),
    SocialSource("lemmy", "upliftingnews@lemmy.world", None, min_score=10, limit=10),
    SocialSource("lemmy", "cats@lemmy.world", AWW, min_score=20, limit=8),
    SocialSource("lemmy", "dogs@lemmy.world", AWW, min_score=20, limit=8),
    SocialSource("lemmy", "wholesome@lemmy.world", SMILES, min_score=10, limit=8),
    # Mastodon hashtags (open API). Score = favourites + boosts.
    SocialSource("mastodon", "CatsOfMastodon@mastodon.social", AWW, min_score=30, limit=10),
    SocialSource("mastodon", "DogsOfMastodon@mastodon.social", AWW, min_score=30, limit=10),
    SocialSource("mastodon", "Caturday@mastodon.social", AWW, min_score=30, limit=6),
    SocialSource("mastodon", "wholesome@mastodon.social", SMILES, min_score=10, limit=6),
]
