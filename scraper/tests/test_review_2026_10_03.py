"""Live-feed review of 3 Oct 2026: headlines that weren't good news, and good ones that must stay."""
import pytest

from goodnews import keywords
from goodnews.scrape import unwanted

BASE = {"kind": "article", "summary": "", "community": "Community", "sourceHomepage": ""}

NOT_GOOD_NEWS = [
    ('Caergwrle church solar panels face refusal two years after conservation area halt', 'Wrexham.com'),
    ('Oxford officials urge water conservation as Kerr Lake levels drop', 'ABC11 News'),
    ('Massive whale sanctuary rejected again, but Brazil vows to never stop trying', 'ABC News (Australia)'),
    ('UAE experts call for wider early screening for diabetes, cancer and other chronic diseases', 'Gulf News'),
    ("'Keep oil and gas in the ground': Zack Polanski demands 'clean energy future' for the UK", 'BusinessGreen'),
    ("Bayelsa@30: Bokoru Faults Lavish Celebration as Obuebite Hails State's Milestone", 'thesouthernexaminer.com'),
    ("'Lifeless' baby took two gasps after delivery, inquest hears", 'ABC News (Australia)'),
    ('Rescue owner facing charges, animals still recovering', '28/22 News'),
    ('Mike Miller, stock car legend with 116 wins, dies at 80 after cancer battle', 'Motorcycle Sports'),
    ('Multicancer Early Detection Screening Does Not Reduce Overall Late-Stage Cancer Diagnosis', 'The ASCO Post'),
    ('Clues to Why a Breakthrough Pancreatic Cancer Drug Eventually Stops Working', 'The New York Times'),
    ('Dementia breakthroughs face diagnostic bottlenecks', 'Oman Observer'),
    ('Bowel cancer screening expanded - but 50 year olds face five year wait', 'BBC Northern Ireland'),
    ("Endangered salmon may soon swim freely up a Maine river, but a comeback isn't guaranteed", 'rutlandherald.com'),
    ('Despite big Texas solar purchase agreement, Meta still uses lots of gas', 'Canary Media'),
    ('Poundland management in talks to lead bid that could save 11,000 jobs', 'The Guardian UK'),
    ("UK's SSE posts 20% jump in first-half renewable energy output, reiterates outlook", 'AOL.ca'),
    ('Yinson Renewables Secures US$163 Million Financing For Mt Cass Wind Farm', 'BusinessToday Malaysia'),
    ('Sharing the Power Foundation Celebrates Five-Year Milestone and Announces 2026 Class of Clean Energy Fellows', 'EIN News'),
    ("CMarieF's Dementia Survival Guide for Caregivers Is Now", 'openPR.com'),
    ("G-SHOCK'S PINK RIBBON MODEL RETURNS: THE GMAS2100PR FUELS THE NEXT BREAST CANCER BREAKTHROUGH", 'Morningstar'),
    ('Fred Olsen Cruise Lines returns to Canada in 2028', 'Travel Weekly - Home'),
    ('Yeungjin College Records 7.4:1 Competition Rate in First Early Admissions', '아시아경제'),
    ('6 Late Late country music special talking points: Dolly Parton celebrated and Hall of Fame inductee', 'Irish Examiner'),
    ('Kiggans, EPA Deputy Administrator Fotouhi Celebrate $92 Million in Grant Awards for Conservation Efforts', 'House.gov'),
    ("From schools' sensation to senior Antrim star... Saffron teen reflects on a breakthrough 2026 in camogie", 'Belfast Telegraph'),
    ('NJDEP| Fish & Wildlife | Light Goose Conservation Season Update', 'New Jersey Department of Environmental Protection (.gov)'),
    ('TWS comments on proposed revisions to NRCS National Handbook of Conservation Practices', 'wildlife.org'),
    ("Learn Simple Ways to Save Water at Gilbert's Free Water Conservation Workshops", 'gilbertaz.gov'),
    ('Sequoia Park Zoo Opens Up Grant Money for Wildlife Conservation Projects', 'kymkemp.com'),
    ("Women's Ag Network Launches Second Round of Scholarship Opportunities to Support Women in Agriculture", 'American Ag Network'),
    ('Adopters needed for rescued poultry at Nantwich RSPCA wildlife centre', 'Nantwich News'),
    ('Henderson offers half-off dog adoptions in October for Adopt a Shelter Dog Month', 'KSNV'),
    ('Petsense Hosting Fall Adoptathon With Goal of 1,200 Pet Adoptions', 'Williamson Source'),
    ('Chester Zoo study finds reluctance to create wildlife-friendly gardens', 'Great British Life'),
    ('Breast Cancer Awareness Month: Why Early Detection, Screening And Awareness Matter', 'News Mobile'),
    ("Cancer treatments improved. Now let's find cancer earlier | Opinion", 'Times Record News'),
    ('Paramount, CBS News New York partner with Komen Foundation for NYC Race for the Cure for breast cancer', 'CBS News'),
    ('CodeWatch: Big wins for off-site construction and embodied carbon reduction', 'SmartBrief'),
    ('Solar Panel Recycling Breakthrough: Pyrolysis + Electric Arc Turns Old Panels Into Silicon Carbide', 'IndexBox'),
    ('WELSH SHEPHERD SPIRITS UNVEILS PAPER BOTTLE RANGE WITH RHINO CONSERVATION AT ITS HEART', 'Bar Magazine'),
    ('European Investment Bank Recognises PRECISIS for Innovation in Neurotechnology and Epilepsy Treatment', 'Med-Tech Insights'),
    ('Skin Rashes From Cancer Immunotherapy: New Expert Consensus Maps Diagnosis and Treatment', 'Bioengineer.org'),
    ('Dog rescued from Westport house fire returns home, faces months of recovery', 'WJAR'),
    ('Scientists discover huge new underwater beast as whale species found', 'Irish Mirror'),
    ('ASCO Genitourinary Cancers Symposium: Pioneering breakthroughs, optimizing patient outcomes', 'The Cancer Letter'),
    ('Seventeen rescued after fishing boat runs aground off Orkney', 'BBC Scotland'),
    ('Social media star named first female ambassador for Essex rescue', 'Braintree & Witham Times'),
    ('Jamie Oliver saves flight school that trained him', 'BBC England'),
    ("Conservationist Jeff Corwin explores rare North Carolina wildlife in new ABC series 'Jeff Corwin'", 'ABC7 San Francisco'),
    ("Microsoft's nightmare is back. LibreOffice has released a new update improving compatibility with Word", 'Lemmy · upliftingnews'),
    ('Discover Viova automotive AI voice agent at 2026 Auto Trade EXPO', 'Autotrade.ie'),
    ('Future of Energy Forum panel: How AI could expand energy access', 'Tulane University News'),
    ('Quantum Computing Could Cut Industrial Emissions Without an AI-Sized Footprint, BCG Finds', 'The Quantum Insider'),
    ('A sceptical guide to AI agents in drug discovery', 'Drug Target Review'),
    ('Study Finds Medicare AI Attribution Concentrated Among Small Group of Publishers', 'USA Today'),
    ('Proteina Enters AI Bio Foundry Services... Solving Drug Discovery Bottlenecks', '아시아경제'),
    ('Talus Bio looks beyond protein structures with new AI drug discovery model', 'FirstWord PHARMA'),
    ('How AI could transform patient safety', 'Chief Healthcare Executive'),
    ('From payphones to AI: UGA School of Medicine dean prepares future doctors for tech, rural care', 'The Business Journals'),
    ('Doctors, not AI, must have final say in treatment decisions – Expert', 'Newswav'),
    ('Donga ST and Galaxy Launch Joint Research on ADC Drug Discovery Based on AI Antibody Design', '아시아경제'),
    ("Novo Nordisk taps Anthropic's Claude to speed drug discovery", 'Drug Discovery News'),
    ('Barrett pushes for international AI agreement as health professionals back Doctors Not AI Act', 'Michigan Advance'),
    ('Weekly丨Advancing AI drug discovery & longevity science | Insilico Medicine at three premier global summits', 'EurekAlert!'),
    ('Novartis and CAS build reaction-data backbone for AI drug discovery', 'AllSci'),
    ('AI speech translator Latest Android App', 'vnua.edu.vn'),
]

STILL_GOOD = [
    'AI Helps Pathologists Count Tumor Cell Divisions Faster and More Consistently in Dogs',
    "Son who used AI to help save mum's life hopes case offers Parkinson's clues",
    'Researchers at SIU Carbondale use AI to detect bacteria more efficiently',
    "Drones, satellites and AI help scientists watch over Antarctica's smallest lifeforms",
    'FAU Study Uses Raman Spectroscopy and AI to Detect Skin Cancer',
    'New AI app helps people with ALS keep their own voices',
    'AI can detect Type 2 diabetes from 20 seconds of speech',
    'Sanfilippo Syndrome Gene Therapy FDA Approved for Childhood Dementia',
    'U.S. cancer death rates continue to fall, driven by declines in lung cancer',
    'New Radiation Implant Slashes Tumor Return in Brain Metastases',
    'Breakthrough weight-loss drug helps diabetes patients lose a quarter of their body weight',
    'Major Milestone Reached in Saving Rare Arran Whitebeam Tree from Extinction',
    'Cancer survivor meets donor who saved her life during Disney World 5K',
    'Wildlife arrives on time at rewilded chalk grasslands built along HS2 route',
    'Rescuers save raccoon after head gets stuck in tin can',
    'One million trees planted',
]


@pytest.mark.parametrize("title,source", NOT_GOOD_NEWS)
def test_not_good_news(title, source):
    story = dict(BASE, title=title, source=source)
    assert unwanted(story) or not keywords.passes_keyword_filter(title, "", False, 2)


@pytest.mark.parametrize("title", STILL_GOOD)
def test_still_good(title):
    assert not unwanted(dict(BASE, title=title, source="X")) and keywords.passes_keyword_filter(title, "", False)


def test_duplicate_radio_stations_and_shouting():
    from goodnews.scrape import drop_near_duplicates
    a = {"kind": "article", "title": "Animals rescued from Halesowen sanctuary | News - Magic Radio"}
    b = {"kind": "article", "title": "Animals rescued from Halesowen sanctuary | News - Hits Radio (Birmingham)"}
    assert len(drop_near_duplicates([a, b])) == 1
    assert keywords.strip_site_suffix(a["title"]) == "Animals rescued from Halesowen sanctuary"
    assert keywords.is_clickbait("WELSH SHEPHERD SPIRITS UNVEILS PAPER BOTTLE RANGE")


def test_canadian_cambridge_is_not_the_uk():
    from goodnews.scrape import refine_region
    s = dict(BASE, title="Friends of Dumfries Conservation Area make it their goal to revitalize beloved Cambridge park",
             source="CambridgeToday.ca", sourceHomepage="https://www.cambridgetoday.ca", region="UK & Ireland")
    refine_region(s)
    assert s["region"] == "North America"


def test_neglect_cases_behind_rescue_headlines():
    from goodnews.scrape import grim_inside
    assert grim_inside({"title": "Animals rescued from Halesowen sanctuary", "source": "Rayo", "checkedBy": "keywords",
                        "body": "RSPCA and West Midlands Police team up over concerns about neglect\n\nDogs, cats, "
                                "livestock, and various birds have been rescued from a sanctuary in Halesowen."})
