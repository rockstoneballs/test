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
* Lemmy communities (open API), 9GAG tags and Imgur tags: more memes and cute
  animals, which keep things flowing if Reddit blocks us.

A dead or blocked source is logged and skipped; it never fails the run.
"""

from dataclasses import dataclass
from urllib.parse import quote_plus

# Topic keys (the feed's "community" field; the apps show them as flair tags).
# News topics come from the classifier; these social topics are fixed per source.
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
    # Set for feeds that only cover one region (e.g. BBC Scotland): their stories are
    # tagged with it unless the story itself names somewhere else.
    region: str | None = None


UK_IE = "UK & Ireland"


@dataclass(frozen=True)
class SocialSource:
    """A Reddit subreddit or Lemmy community.

    ``community`` is None for link-post communities (their posts are news
    articles, classified like any other story); otherwise it's the fixed
    community image posts are filed under.
    """
    platform: str  # "reddit" | "lemmy" | "9gag" | "imgur"
    name: str  # subreddit; community@instance for Lemmy; tag for 9GAG and Imgur
    community: str | None
    min_score: int = 50
    limit: int = 15
    sort: str = "TopWeek"  # Lemmy only


def _google_news(query: str, country: str = "US") -> Source:
    """A Google News search in one country's English edition (US, GB, CA, AU, NZ, IE)."""
    lang = "en-US" if country == "US" else f"en-{country}"
    url = (f"https://news.google.com/rss/search?q={quote_plus(query)}+when:1d"
           f"&hl={lang}&gl={country}&ceid={country}:en")
    return Source(f"Google News ({country}): {query}", url, False, "https://news.google.com")


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
    Source("Squirrel News", "https://squirrel-news.net/feed/", True, "https://squirrel-news.net"),
    # Mainstream, regional and science outlets, filtered for positivity.
    Source("BBC News", "https://feeds.bbci.co.uk/news/world/rss.xml", False, "https://www.bbc.co.uk/news"),
    Source("BBC Science", "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", False,
           "https://www.bbc.co.uk/news/science_and_environment"),
    Source("NPR", "https://feeds.npr.org/1001/rss.xml", False, "https://www.npr.org"),
    Source("The Guardian Environment", "https://www.theguardian.com/environment/rss", False,
           "https://www.theguardian.com/environment"),
    Source("The Guardian Science", "https://www.theguardian.com/science/rss", False, "https://www.theguardian.com/science"),
    Source("DW", "https://rss.dw.com/rdf/rss-en-all", False, "https://www.dw.com"),
    Source("France 24", "https://www.france24.com/en/rss", False, "https://www.france24.com"),
    Source("CBC", "https://www.cbc.ca/webfeed/rss/rss-world", False, "https://www.cbc.ca/news"),
    Source("ABC News (Australia)", "https://www.abc.net.au/news/feed/51120/rss.xml", False, "https://www.abc.net.au/news"),
    Source("BBC England", "https://feeds.bbci.co.uk/news/england/rss.xml", False, "https://www.bbc.co.uk/news/england", UK_IE),
    Source("The Guardian UK", "https://www.theguardian.com/uk-news/rss", False, "https://www.theguardian.com/uk-news", UK_IE),
    Source("Sky News UK", "https://feeds.skynews.com/feeds/rss/uk.xml", False, "https://news.sky.com/uk", UK_IE),
    Source("BBC Scotland", "https://feeds.bbci.co.uk/news/scotland/rss.xml", False, "https://www.bbc.co.uk/news/scotland", UK_IE),
    Source("BBC Wales", "https://feeds.bbci.co.uk/news/wales/rss.xml", False, "https://www.bbc.co.uk/news/wales", UK_IE),
    Source("BBC Northern Ireland", "https://feeds.bbci.co.uk/news/northern_ireland/rss.xml", False,
           "https://www.bbc.co.uk/news/northern_ireland", UK_IE),
    Source("BBC Newsround", "https://feeds.bbci.co.uk/newsround/rss.xml", False, "https://www.bbc.co.uk/newsround"),
    Source("The Guardian Scotland", "https://www.theguardian.com/uk/scotland/rss", False,
           "https://www.theguardian.com/uk/scotland", UK_IE),
    Source("The Guardian Wales", "https://www.theguardian.com/uk/wales/rss", False, "https://www.theguardian.com/uk/wales", UK_IE),
    Source("TheJournal.ie", "https://www.thejournal.ie/feed/", False, "https://www.thejournal.ie", UK_IE),
    Source("BreakingNews.ie", "https://feeds.breakingnews.ie/bnireland", False, "https://www.breakingnews.ie", UK_IE),
    Source("Irish Examiner", "https://www.irishexaminer.com/feed/35-top_news.xml", False, "https://www.irishexaminer.com", UK_IE),
    Source("RNZ", "https://www.rnz.co.nz/rss/national.xml", False, "https://www.rnz.co.nz"),
    Source("RTÉ", "https://www.rte.ie/feeds/rss/?index=/news/", False, "https://www.rte.ie/news", UK_IE),
    Source("ScienceDaily", "https://www.sciencedaily.com/rss/top.xml", False, "https://www.sciencedaily.com"),
    Source("Phys.org", "https://phys.org/rss-feed/", False, "https://phys.org"),
    Source("ScienceAlert", "https://www.sciencealert.com/feed", False, "https://www.sciencealert.com"),
    Source("New Atlas", "https://newatlas.com/index.rss", False, "https://newatlas.com"),
    Source("NASA", "https://www.nasa.gov/news-release/feed/", False, "https://www.nasa.gov"),
    Source("Smithsonian", "https://www.smithsonianmag.com/rss/latest_articles/", False, "https://www.smithsonianmag.com"),
    Source("Mongabay", "https://news.mongabay.com/feed/", False, "https://news.mongabay.com"),
    # Google News searches: a wide net across thousands of outlets worldwide.
    # Specific, newsy searches only: "good news" or "heartwarming" searches mostly find
    # clickbait ("good news for pensioners…", "heartwarming moment…").
    _google_news("scientists breakthrough"),
    _google_news("conservation success"),
    _google_news("new species discovered"),
    _google_news("record renewable energy"),
    _google_news("volunteers restore"),
    _google_news("charity raises"),
    _google_news("volunteers", "GB"),
    _google_news("charity raises", "GB"),
    _google_news("conservation success", "GB"),
    _google_news("wildlife returns", "GB"),
    _google_news("rewilding", "GB"),
    _google_news("animal rescue", "GB"),
    _google_news("raises money for", "GB"),
    _google_news("community garden", "GB"),
    _google_news("new species", "GB"),
    _google_news("volunteers", "CA"),
    _google_news("volunteers", "AU"),
    _google_news("conservation success", "AU"),
    _google_news("volunteers", "IE"),
    _google_news("charity", "IE"),
    _google_news("animal rescue", "IE"),
    _google_news("community", "IE"),
    _google_news("conservation", "IE"),
    _google_news("volunteers", "NZ"),
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
    SocialSource("lemmy", "wholesomememes@lemmy.world", MEMES, min_score=5, limit=15, sort="TopMonth"),
    SocialSource("lemmy", "aww@lemmy.world", AWW, min_score=20, limit=10),
    SocialSource("lemmy", "mademesmile@lemmy.world", SMILES, min_score=5, limit=10, sort="TopMonth"),
    SocialSource("lemmy", "upliftingnews@lemmy.world", None, min_score=10, limit=10),
    SocialSource("lemmy", "cats@lemmy.world", AWW, min_score=5, limit=8),
    SocialSource("lemmy", "dogs@lemmy.world", AWW, min_score=20, limit=8),
    # 9GAG tags (the JSON its site uses; skipped if blocked).
    SocialSource("9gag", "wholesome", MEMES, min_score=200, limit=20),
    SocialSource("9gag", "wholesome-memes", MEMES, min_score=100, limit=10),
    SocialSource("9gag", "cute", AWW, min_score=200, limit=12),
    SocialSource("9gag", "aww", AWW, min_score=100, limit=10),
    SocialSource("9gag", "dogs", AWW, min_score=200, limit=8),
    SocialSource("9gag", "cats", AWW, min_score=200, limit=8),
    # Imgur tags (official API; needs IMGUR_CLIENT_ID).
    SocialSource("imgur", "wholesome", MEMES, min_score=100, limit=15),
    SocialSource("imgur", "aww", AWW, min_score=100, limit=12),
    SocialSource("imgur", "cats", AWW, min_score=100, limit=8),
    SocialSource("imgur", "dogs", AWW, min_score=100, limit=8),
]

# How long posts stay. Memes are evergreen, so a meme community that posts slowly still fills up.
MAX_AGE_DAYS = {MEMES: 60, AWW: 3, SMILES: 7}
