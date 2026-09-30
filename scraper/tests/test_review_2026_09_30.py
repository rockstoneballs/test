"""Headlines from the live feed on 30 Sep 2026 that weren't good news, and the two new
kinds of story Sunnyside now looks for: AI for good, and progress on hard problems."""
import pytest

from goodnews import keywords
from goodnews.scrape import drop_near_duplicates, keyword_uplift, refine_region, unwanted

BASE = {"kind": "article", "summary": "", "community": "Community", "sourceHomepage": ""}

NOT_GOOD_NEWS = [
    ("Elk head discovered in Courtenay River prompts report to BC Conservation Service", "Comox Valley Record"),
    ("Newborn baby's body found on shoreline", "Sky News UK"),
    ("Rare snow leopard cub with incurable bone condition put to sleep", "BBC Scotland"),
    ("Tributes paid to Drogheda Animal Rescue's much-loved mascot Holly Bolly", "Irish Independent"),
    ("Anger as October graduation ceremonies at Limerick college pushed to next spring", "TheJournal.ie"),
    ("'Despicable': Limerick volunteers forced to sift through soiled clothes", "Limerick Leader"),
    ("Bishop's Stortford charities counting the cost of 'donations' dumped on their doorsteps", "Bishop's Stortford Independent"),
    ("An Asian hornet has been spotted for the first time this year (but luckily it's been captured)", "TheJournal.ie"),
    ("North East urged to save water as reservoirs remain below average despite rain", "Hexham Courant"),
    ("Fitzmaurice leading candidate to succeed Joyce in Galway", "RTÉ"),
    ("Dale Vince plans London-wide news operation to take on 'rightwing bias' in the media", "The Guardian UK"),
    ("Praise and thanks for volunteers as Timberscombe's Old Dairy Centre shuts down", "West Somerset Free Press"),
    ("Council objects to new balcony railings in The Beacon conservation area", "exmouthjournal.co.uk"),
    ("Couple apologise over cat rescue poster confusion but say they acted 'in good faith'", "The Western Telegraph"),
    ("More damaged trees mean fewer ecosystem benefits for low-income residents", "Phys.org"),
    ("Toppled trees keep volunteer crews busy in NE Oregon", "East Oregonian"),
    ("Mithril Minerals Targets Low-Impact Robotic Nodule Recovery With Breakthrough Energy", "Ocean News & Technology"),
    ("NASA Awards Orbital Safety Analysis Support Services Contract", "NASA"),
    ("British Museum Tender for £9m Reading Room Roof Conservation", "roofingtoday.co.uk"),
    ("BD Celebrates 70 Years of Manufacturing, Innovation and Community Partnership in Sandy", "BD Newsroom"),
    ("NetApp awards IUCN for conservation data infrastructure", "Investing.com"),
    ("TeraOpenScience Launches Global Innovation Contest", "Business Wire"),
    ("How U.S. adults support charities: Donations, volunteering and future giving", "yougov.com"),
    ("An Official Journal Of The NRA | Endowment Expands Black Bear Wildlife Conservation Research", "American Hunter"),
    # Appeals, notices, shortlists and adoption listings.
    ("Run for the Cure needs volunteers", "EverythingGP"),
    ("Give a little time and make a big difference – St Vincent de Paul appeals for new volunteers", "NorthernIrelandWorld"),
    ("Campaign launched to recruit volunteer board members in Donegal", "Donegal Live"),
    ("South Western Ambulance Charity launches £400,000 appeal to upgrade life-saving defibrillators", "Salisbury Radio"),
    ("Meet the 2026 Kerry Community Awards finalists shortlisted in the Inclusive Community category", "Irish Independent"),
    ("Shortlist announced for Wandsworth Civic Awards 2026", "Wandsworth Borough Council"),
    ("Two Donegal projects awarded €73,000 through Community Safety Fund", "Highland Radio"),
    ("Wood Soil & Water Conservation District to host free native pollinator and wildlife habitat workshop", "BG Independent News"),
    ("Children's water festival returns to C.M. Wilson Conservation Area Oct. 6 to 8", "The Sydenham Current"),
    ("Energetic rescue dog Hugo searching for a new running partner", "Evening Mail"),
    ("Five dogs looking for their forever home from Many Tears Rescue", "South Wales Argus"),
    ("Interview: Lafayette County Animal Shelter highlights rescue program, dog available for adoption", "WTVA"),
    ("Ready for a rescue? Adoption fees waived through Sept. 30", "Corpus Christi Caller-Times"),
    ("[Photo Gallery] Volunteers gather along San Vicente Boulevard to care for historic coral trees", "westsidetoday.com"),
]


@pytest.mark.parametrize("title,source", NOT_GOOD_NEWS)
def test_live_feed_headlines_that_were_not_good_news(title, source):
    story = dict(BASE, title=title, source=source)
    home = 2 if source in {"RTÉ", "TheJournal.ie"} else 3
    assert unwanted(story) or not keywords.passes_keyword_filter(title, "", False, home), title


STILL_GOOD = [
    "Wildcat conservationists hail breeding success in Highlands as conservation strategy reaches milestone",
    "Barn owl rescued by British Army 70 miles out at sea returns to wild in Suffolk",
    "UK sets fresh renewables record as solar installations hit 14-year high",
    "Colorado's largest wildlife overpass cut I-25 collisions by 91 percent",
    "Volunteers race to plant 600 trees across Elkhart",
]


@pytest.mark.parametrize("title", STILL_GOOD)
def test_good_stories_survive(title):
    assert not unwanted(dict(BASE, title=title, source="X"))
    assert keywords.passes_keyword_filter(title, "", False, 2)


AI_FOR_GOOD = [
    "AI helps doctors spot breast cancer missed by radiologists",
    "AI structure prediction speeds discovery of 'molecular glues' to treat disease",
    "Anthropic Says Its A.I. Discovered a New Enzyme System That Resembles the Revolutionary Gene-Editing Tool CRISPR",
    "DeepMind's AlphaFold predicts structures of nearly every known protein",
    "Machine learning helps rangers track endangered rhinos",
]
AI_NOT_GOOD = [
    "AI could replace 300 million jobs, report warns",
    "OpenAI raises $40 billion in funding round",
    "AI chatbot linked to teen's suicide",
    "AI data centres' electricity demand threatens climate goals",
    "Deepfake scam costs firm millions",
    "Nvidia unveils new AI chip",
]


@pytest.mark.parametrize("title", AI_FOR_GOOD)
def test_ai_for_good(title):
    assert keywords.passes_keyword_filter(title, "", False)
    assert keywords.guess_category(title, "") == "AI"
    assert keyword_uplift(title, "", trusted=False) >= 6  # ranks with the best


@pytest.mark.parametrize("title", AI_NOT_GOOD)
def test_ai_industry_and_risks_are_left_out(title):
    assert not keywords.passes_keyword_filter(title, "", False)
    assert not keywords.passes_keyword_filter(title, "", True)  # even from good-news outlets


PROGRESS = [
    ("New drug slows Alzheimer's decline in landmark trial", "Health"),
    ("Breakthrough blood test could cut cancer deaths by detecting tumours early", "Health"),
    ("Gene therapy reverses blindness in children with rare disease", "Health"),
    ("Cancer survival rates rise to record high thanks to new therapies", "Health"),
    ("Dementia risk cut by a third with new blood pressure treatment", "Health"),
    ("UK emissions fall to lowest level since 1872 as coal is phased out", "Environment"),
]
WORSENING = [
    "Cancer deaths rise despite new treatments",
    "Emissions hit record high despite solar boom",
    "Global coral reefs get less time to recover as oceans heat up, report finds",
    "Drug trial for dementia fails",
]


@pytest.mark.parametrize("title,topic", PROGRESS)
def test_progress_on_hard_problems(title, topic):
    assert keywords.passes_keyword_filter(title, "", False)
    assert keywords.guess_category(title, "") == topic
    assert keyword_uplift(title, "", trusted=False) >= 6


@pytest.mark.parametrize("title", WORSENING)
def test_hard_problems_getting_worse_are_left_out(title):
    assert not keywords.passes_keyword_filter(title, "", False)
    assert not keywords.passes_keyword_filter(title, "", True)


def test_progress_articles_may_mention_deaths_in_their_text():
    from goodnews.scrape import grim_inside
    story = {"title": "New drug slows Alzheimer's decline in landmark trial", "source": "Medical Xpress",
             "checkedBy": "keywords", "body": "Alzheimer's disease causes a steady decline in memory and leads to "
             "thousands of deaths each year. A new drug slowed that decline by a third in a large trial."}
    assert not grim_inside(story)


def test_regions_come_from_the_article_not_look_alike_names():
    elk = dict(BASE, title="Elk head discovered", source="Comox Valley Record", region="Global",
               body="An elk head was reported to the British Columbia Conservation Officer Service on Sept. 26.")
    refine_region(elk)
    assert elk["region"] != "UK & Ireland"
    elephants = dict(BASE, title="This herd of rescue elephants 'adopted' a new little brother", source="Good Good Good",
                     region="Latin America", checkedBy="keywords",
                     body="Rangers from the Sheldrick Wildlife Trust and Kenya Wildlife Service were training.")
    refine_region(elephants)
    assert elephants["region"] == "Africa"


def test_near_duplicates_are_merged():
    titles = ["Tributes paid to Drogheda Animal Rescue's much-loved mascot Holly Bolly",
              "Tributes paid to much-loved Drogheda Animal Rescue mascot Holly Bolly",
              "Rooftop solar, home battery installs hit record highs in Australia",
              "Rooftop solar, home battery installs hit record highs",
              "Volunteers plant trees in Devon", "Volunteers plant trees in Cornwall"]
    kept = [s["title"] for s in drop_near_duplicates([{"kind": "article", "title": t} for t in titles])]
    assert kept == [titles[0], titles[2], titles[4], titles[5]]
