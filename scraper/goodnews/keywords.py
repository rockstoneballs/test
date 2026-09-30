"""Keyword-based positivity scoring, topic and region tagging.

This is the no-API-key path. It's deliberately conservative for mainstream
sources: any "doom" word rejects the story outright, and it needs several
uplifting signals to get in. When ANTHROPIC_API_KEY is set, Claude does this
job instead (see classifier.py) and these functions are only used as a fallback.
"""

from __future__ import annotations

import re
from urllib.parse import urlsplit

CATEGORIES = [
    "Science", "Environment", "Health", "Animals",
    "Community", "Innovation", "AI", "Culture", "Sport",
]

REGIONS = [
    "UK & Ireland", "Europe", "North America", "Oceania",
    "Africa", "Asia", "Latin America", "Middle East", "Global",
]


def _rx(words: list[str]) -> re.Pattern[str]:
    # Prefix match by default, so "rescue" matches "rescued"/"rescuers".
    # A trailing "!" means whole word only: "war!" matches "war" but not "warm".
    def one(w: str) -> str:
        return re.escape(w[:-1]) + r"\b" if w.endswith("!") else re.escape(w)
    return re.compile(r"\b(?:" + "|".join(one(w) for w in words) + r")", re.IGNORECASE)


# Words that sink a story no matter where it came from (checked in titles of
# trusted sources, and titles + summaries of mainstream sources).
HARD_BLOCK = _rx([
    "killed", "killing", "murder", "massacre", "rape", "suicide", "suicidal", "terror",
    "shooting", "shot dead", "stabbing", "genocide", "beheaded", "bombing",
    "dead bod", "death toll", "fatal", "slaughter", "abuse", "hostage",
    "war crime", "execution", "executed", "body found", "body was found", "baby's body", "bodies found",
    "remains found", "human remains",
])

# Money, markets, disputes and admin notices: not what anyone means by good news, and
# they often borrow "good news" headlines. Rejected in titles from every source.
_MONEY_WORDS = [
    "stock market", "stocks!", "shares!", "shareholder", "investor", "sensex", "nifty", "ipo!", "gdp",
    "economy", "interest rate", "mortgage", "pension", "salary", "salaries", "da hike", "pay commission",
    "epfo", "tax!", "taxes", "budget", "loan", "emi!", "gold price", "petrol price", "fuel price",
    "lottery", "jackpot", "crypto", "bitcoin", "profits!", "revenue", "earnings", "billion-dollar",
    "settlement", "lawsuit", "refund", "compensation", "payout", "unfair", "underpaid", "wage theft",
    "landlord", "renters", "house prices", "rent!", "rents!", "sued", "sues!", "sue!",
    "admit card", "exam result", "board result", "recruitment", "vacancy", "vacancies", "scheme",
    "tender!", "tenders!", "contract!", "contracts!", "acquisition", "acquires", "merger", "manufactur",
    "invests", "invest!", "portfolio", "transaction", "esg!", "insurance", "reimburs", "partners with",
    "cash raise", "fresh cash", "funding round", "secures $", "raises $", "interim results",
]
MONEY = _rx(_MONEY_WORDS)

# Words that make mainstream stories too gloomy for the feed.
_DOOM_WORDS = [
    "war!", "wars!", "warfare", "attack", "strike", "missile", "drone strike", "invasion", "troops",
    "dies", "died", "death", "dead", "deadly", "crash", "collapse", "disaster",
    "crisis", "fear", "warn", "threat", "scandal", "corrupt", "arrest", "charged",
    "jail", "prison", "sentenced", "lawsuit", "sued", "protest", "riot",
    "clash", "violence", "violent", "injur", "wound", "victim", "flood", "wildfire",
    "earthquake", "hurricane", "storm", "drought", "famine", "outbreak", "pandemic",
    "recession", "layoff", "job cuts", "inflation", "tariff", "sanction", "election",
    "trump", "putin", "hamas", "gaza", "ukraine", "israel", "taliban", "isis",
    "cancer risk", "decline", "plunge", "slump", "worst", "fail", "shortage",
    "controvers", "row over", "backlash", "condemn", "accus", "fraud", "hacked", "explos", "bomb",
    "leak", "extinct", "toxic", "pollut", "poison", "overdose", "missing",
    # Conflict, crime and hardship.
    "fighting", "fight!", "detain", "amid!", "conflict", "tension", "militant", "rebel", "unrest",
    "curfew", "evacuat", "court case", "court ruling", "judge rules", "judge orders", "banned", "boycott",
    "outrage", "slams", "criticis", "feud", "spat", "suffer", "struggl", "poverty", "grief", "mourn",
    "tragic", "tragedy", "funeral", "abandoned", "starv", "hunger", "refugee", "migrant", "deport",
    "shooting", "gun!", "guns!", "stabbed", "killed", "murder", "assault", "cyberattack", "scam",
    "fined", "die!", "bodies", "body of", "landslide", "mudslide", "avalanche", "drown", "hunting", "hunter!",
    "hunters", "bleach", "heatwave", "heat wave", "warming", "worsen", "loss!", "losses", "losing",
    "lose!", "shrink", "less time", "nightmare",
    # Sad endings, disputes and nuisances that slipped through as "good news".
    "put to sleep", "euthanis", "euthanas", "incurable", "tribute", "in memory of", "memorial", "anger", "angry",
    "despicable", "shuts down", "shut down", "closes", "closure", "closing down", "apologis", "apologiz",
    "nodule", "seabed mining", "deep-sea mining", "deep sea mining",
    "objects to", "objection", "petition", "urged to", "below average", "hornet", "invasive", "damaged",
    "toppled", "drifting", "dumped", "forced to", "counting the cost", "carcass", "severed", "decapitat",
    "remains!", "head discovered", "head found", "prompts report", "investigat", "poach", "mining", "stolen",
    "theft", "vandal", "horrendous", "horrific", "appalling", "cull!", "culled", "into river", "into a river",
    "into the river", "plunge", "dental desert", "heat up", "heats up", "heating up", "accelerat", "faster than expected",
] + _MONEY_WORDS
DOOM = _rx(_DOOM_WORDS)

# Politics and politicians: never Sunnyside material, from any source.
POLITICS = _rx([
    "modi!", "bjp!", "mann ki baat", "trump!", "biden!", "kamala harris", "starmer!", "sunak!", "farage!",
    "putin!", "netanyahu", "zelensky", "xi jinping", "white house", "downing street", "kremlin",
    "parliament", "senate!", "senator", "congressman", "congresswoman", "prime minister", "chief minister",
    "union minister", "minister!", "ministers!", "ministry", "government", "govt!", "election", "politic",
    "republican", "democrat", "labour party", "conservative party", "tory", "tories",
    "governor", "lawmaker", "legislat", "chief whip", "candidate", "rightwing", "right-wing", "leftwing",
    "left-wing", "nra!", "eu commission", "european commission", "rfk", "kennedy",
    # UK and Irish politicians who often appear in "good news" headlines.
    "burnham", "sadiq khan", "rachel reeves", "miliband", "streeting", "rayner", "lammy", "badenoch", "swinney",
    "sarwar", "taoiseach", "tánaiste", "tanaiste", "micheál martin", "micheal martin", "simon harris", "mcdonald!",
])

# Named politicians and parties: a story whose text is about them is politics, whatever
# its headline says. (Unlike POLITICS, fine to check in article text.)
POLITICIANS = _rx([
    "trump!", "biden!", "kamala harris", "vance!", "obama!", "starmer!", "sunak!", "farage!", "badenoch", "reform uk",
    "putin!", "netanyahu", "zelensky", "xi jinping", "modi!", "bjp!", "macron!", "merz!", "albanese!", "carney!",
    "poilievre", "luxon!", "republicans", "democrats", "maga!", "labour party", "tory", "tories",
])

# Sad turns that show up in a story's text rather than its headline.
GRIM_TEXT = _rx([
    "passed away", "devastating news", "tragically", "lost her life", "lost his life", "lost their lives",
    "funeral", "was diagnosed with terminal", "terminally ill", "memorial service",
])

UPLIFT = _rx([
    "breakthrough", "cure", "cured", "rescue", "saved", "saves", "save!", "restor",
    "recover", "reunit", "donat", "volunteer", "celebrat", "first-ever", "first ever",
    "milestone", "thriv", "success", "record high", "record-breaking", 
    "award", "kindness", "kind-hearted", "generous", "generosity", "inspir",
    "protect", "conservation", "renewable", "clean energy", "solar", "wind farm",
    "reforest", "rewild", "rebound", "comeback", "bounce back", "discover", "innovat",
    "healed", "healing", "heals", "vaccine", "eradicat", "for free", "joy", "happy", "happiest",
    "smile", "delight", "born", "baby", "hatch", "returns to",
    "spotted for the first time", "back from the brink",
    "helps", "helping", "cleaner",
    "reduce emissions", "cut emissions", "planted", "trees", "bees", "wildlife",
    "sanctuary", "adopt", "graduat", "scholarship", "literacy",
    "lifesaving", "life-saving", "promising",
])

CATEGORY_KEYWORDS: dict[str, re.Pattern[str]] = {
    "Animals": _rx([
        "animal", "wildlife", "species", "dog", "puppy", "cat!", "cats!", "kitten", "bird",
        "whale", "dolphin", "turtle", "elephant", "tiger", "lion", "bear", "wolf", "wolves",
        "bee!", "bees!", "butterfl", "koala", "panda", "rhino", "gorilla", "otter", "beaver",
        "shark", "coral", "sanctuary", "zoo", "pet!", "pets!", "horse", "owl", "penguin", "frog", "amphibian",
        "reptile", "hippo", "leopard", "monkey", "ape!", "apes!",
    ]),
    "Environment": _rx([
        "climate", "emission", "renewable", "solar", "wind power", "wind farm", "carbon",
        "forest", "tree", "ocean", "river", "plastic", "recycl", "rewild", "conservation",
        "green", "nature", "biodiversity", "clean energy", "electric vehicle", "ev!", "evs!",
        "pollution", "wetland", "reef", "national park", "sustainab",
    ]),
    "Health": _rx([
        "health", "vaccine", "cancer", "disease", "patient", "hospital", "doctor", "nurse",
        "medical", "medicine", "drug", "therapy", "treatment", "cure", "mental health",
        "surgery", "malaria", "hiv", "diabetes", "alzheimer", "wellbeing", "well-being",
    ]),
    "Science": _rx([
        "scientist", "research", "study", "discover", "astronom", "space", "nasa", "planet",
        "telescope", "physics", "fossil", "dinosaur", "archaeolog", "species discovered",
        "genome", "dna", "quantum", "universe", "galaxy", "moon", "mars",
    ]),
    "Innovation": _rx([
        "invent", "technology", "tech", "robot", "startup", "engineer", "3d print", "app!", "apps!",
        "battery", "prototype", "innovation", "design",
    ]),
    "Community": _rx([
        "community", "volunteer", "neighbo", "donat", "charity", "school", "student",
        "teacher", "homeless", "kindness", "family", "families", "village", "refugee",
        "housing", "free meals", "food bank", "library", "strangers", "local",
    ]),
    "Culture": _rx([
        "art!", "arts!", "artwork", "music", "film", "movie", "book", "museum", "festival", "artist", "concert",
        "theatre", "theater", "poet", "dance", "heritage", "photograph", "author",
    ]),
    "Sport": _rx([
        "sport", "football", "soccer", "olympic", "paralympic", "marathon", "athlete",
        "tennis", "cricket", "rugby", "basketball", "medal", "champion", "cyclist", "swimmer",
        "premier league", "nfl!", "nba!", "mlb!", "nhl!", "world cup", "golf", "formula 1", "f1!",
        "grand prix", "boxing", "ufc!", "wimbledon", "tournament", "playoff", "quarterback",
        "striker", "goalkeeper", "midfielder", "league", "cup final", "semi-final", "hat-trick",
        "touchdown", "slam dunk", "stadium", "transfer window", "super bowl", "wrestl",
        "afl!", "aflw!", "nrl!", "nrlw!", "a-league", "gaa!", "hurling", "hockey", "baseball", "softball",
        "volleyball", "netball", "ncaa", "wnba", "mls!", "fifa", "uefa", "ipl!", "nascar", "indycar", "pga!",
        "lpga", "ryder cup", "six nations", "tour de france", "grand final", "semifinal",
        "innings", "wicket", "halftime", "half-time", "off the mark", "win over", "matildas",
        "socceroos", "wallabies", "all blacks", "lionesses", "gymnast", "sprinter", "skier", "snowboard",
        "vs!", "vs.", "game highlights", "match highlights", "espn", "clinch", "division title", "stood down as", "years in charge", "as manager", "new manager", "managerial", "county board", "all-ireland", "inter-county",
    ]),
}

# Not what Sunnyside is for: celebrity gossip and royalty/monarchy news.
OFF_TOPIC = _rx([
    # Royalty and monarchy
    "king charles", "queen camilla", "queen elizabeth", "royal family", "royals!", "royal visit",
    "the royal", "monarch", "prince!", "princes!", "princess", "duke of", "duchess", "kate middleton",
    "princess of wales", "prince of wales", "meghan markle", "prince harry", "prince william",
    "buckingham palace", "kensington palace", "windsor castle", "coronation", "jubilee", "sandringham",
    "king felipe", "queen letizia", "king willem", "crown prince", "emperor naruhito", "throne",
    # Celebrity and showbiz
    "celebrity", "celebrities", "celeb!", "celebs!", "a-list", "hollywood", "red carpet", "oscar",
    "grammy", "golden globe", "emmy", "met gala", "box office", "kardashian", "taylor swift",
    "beyonc", "kanye", "rihanna", "justin bieber", "selena gomez", "ariana grande", "harry styles",
    "kim k", "paparazzi", "influencer", "tiktok star", "youtuber", "reality tv", "reality star",
    "love island", "bachelorette", "strictly come dancing", "dancing with the stars", "x factor",
    "britain's got talent", "america's got talent", "showbiz", "pop star", "popstar", "movie star",
    "film star", "singer", "rapper", "actress", "actor!", "actors!", "star-studded",
    "royal ascot", "engaged to", "wedding of", "baby bump", "net worth",
    "bafta", "rock star", "rockstar", "boy band", "girl band", "frontman", "gig!", "gigs!", "world tour",
    "u2!", "bono!", "coldplay", "ed sheeran", "oasis!", "bafta-nominated", "pageant", "top-performing songs",
])

# Country / place names -> region. Order matters only for readability.
_REGION_PLACES: dict[str, list[str]] = {
    "Africa": [
        "africa", "nigeria", "kenya", "ethiopia", "ghana", "south africa", "uganda", "tanzania",
        "rwanda", "senegal", "egypt", "morocco", "algeria", "tunisia", "zimbabwe", "zambia",
        "malawi", "mozambique", "botswana", "namibia", "cameroon", "congo", "sudan", "somalia",
        "madagascar", "mali", "niger", "angola", "sierra leone", "liberia", "gabon", "benin",
        "burkina faso", "ivory coast", "côte d'ivoire", "nairobi", "lagos", "cape town",
    ],
    "Asia": [
        "asia", "china", "chinese", "japan", "japanese", "india", "indian", "pakistan",
        "bangladesh", "indonesia", "philippines", "vietnam", "thailand", "malaysia",
        "singapore", "korea", "korean", "nepal", "sri lanka", "myanmar", "cambodia", "laos",
        "mongolia", "taiwan", "hong kong", "bhutan", "kazakhstan", "uzbekistan", "tokyo",
        "beijing", "mumbai", "delhi", "bangkok", "himalaya", "borneo", "sumatra",
        # India, in more detail (stories often name a city or state, not the country).
        "bengaluru", "bangalore", "chennai", "hyderabad", "kolkata", "pune", "ahmedabad",
        "jaipur", "lucknow", "kochi", "kerala", "tamil nadu", "karnataka", "maharashtra",
        "gujarat", "rajasthan", "uttar pradesh", "bihar", "odisha", "assam", "telangana",
        "andhra pradesh", "madhya pradesh", "himachal", "uttarakhand", "jharkhand", "goa",
        "rupee", "rupees", "lakh", "crore", "iit", "isro", "modi",
    ],
    # Before Europe, so a story naming both counts as UK & Ireland.
    "UK & Ireland": [
        "uk", "u.k.", "britain", "british", "england", "scotland", "scottish", "wales", "welsh",
        "ireland", "northern ireland", "nhs", "rnli", "national trust", "london", "manchester",
        "birmingham", "liverpool", "leeds", "sheffield", "bristol", "newcastle", "nottingham", "leicester",
        "brighton", "oxford", "cambridge", "yorkshire", "lancashire", "cumbria", "lake district",
        "cornwall", "cornish", "devon", "dorset", "somerset", "kent", "sussex", "essex", "norfolk", "suffolk",
        "hampshire", "surrey", "cotswolds", "peak district", "snowdonia", "eryri", "glasgow", "edinburgh",
        "aberdeen", "dundee", "inverness", "scottish highlands", "hebrides", "orkney", "shetland", "cardiff",
        "swansea", "belfast", "derry", "dublin", "cork", "galway", "limerick", "waterford", "kilkenny",
        "county kerry", "county mayo", "donegal", "wicklow", "connemara", "sligo",
    ],
    "Europe": [
        "europe", "european", "france", "french",
        "germany", "german", "spain", "spanish", "italy", "italian", "portugal", "netherlands",
        "dutch", "belgium", "switzerland", "swiss", "austria", "sweden", "swedish", "norway",
        "norwegian", "denmark", "danish", "finland", "finnish", "iceland", "poland", "polish",
        "czech", "slovakia", "hungary", "romania", "bulgaria", "greece", "greek", "croatia",
        "serbia", "slovenia", "estonia", "latvia", "lithuania", "london", "paris", "berlin",
        "madrid", "rome", "amsterdam", "copenhagen", "stockholm",
    ],
    "Latin America": [
        "latin america", "south america", "mexico", "mexican", "brazil", "brazilian",
        "argentina", "chile", "colombia", "peru", "ecuador", "bolivia", "venezuela",
        "uruguay", "paraguay", "costa rica", "panama", "guatemala", "honduras", "nicaragua",
        "el salvador", "cuba", "jamaica", "haiti", "dominican republic", "puerto rico",
        "caribbean", "amazon rainforest", "galápagos", "galapagos", "patagonia",
    ],
    "Middle East": [
        "middle east", "saudi", "uae", "emirates", "dubai", "abu dhabi", "qatar", "oman",
        "kuwait", "bahrain", "jordan", "lebanon", "syria", "iraq", "iran", "turkey", "türkiye",
        "yemen", "palestin", "israel",
    ],
    "North America": [
        "united states", "u.s.", "usa", "america", "american", "canada", "canadian",
        "california", "texas", "new york", "florida", "washington", "chicago", "alaska",
        "hawaii", "oregon", "colorado", "michigan", "ohio", "virginia", "carolina",
        "georgia", "massachusetts", "boston", "seattle", "toronto", "vancouver", "montreal",
        "yellowstone", "appalachia",
    ],
    "Oceania": [
        "australia", "australian", "new zealand", "kiwi", "fiji", "samoa", "tonga",
        "papua new guinea", "pacific island", "great barrier reef", "sydney", "melbourne",
        "auckland", "tasmania", "queensland", "aotearoa",
    ],
}

_REGION_RX: dict[str, re.Pattern[str]] = {
    region: re.compile(r"\b(?:" + "|".join(re.escape(p) for p in places) + r")\b", re.IGNORECASE)
    for region, places in _REGION_PLACES.items()
}


def is_hard_blocked(text: str) -> bool:
    return bool(HARD_BLOCK.search(text))


def positivity(title: str, summary: str) -> int:
    """Crude uplift score: +1 per uplifting hit, -3 per doom hit (title weighs double)."""
    score = 0
    for text, weight in ((title, 2), (summary, 1)):
        score += weight * len(UPLIFT.findall(text))
        score -= 3 * weight * len(DOOM.findall(text))
    return score


WESTERN_REGIONS = {"UK & Ireland", "Europe", "North America", "Oceania"}
# Most readers are here, so their stories get a small boost in "Top stories".
HOME_REGION = "UK & Ireland"

# Outlets whose stories are almost always about one non-Western region, matched on the
# publisher's name or web domain (Google News gives us both).
_NON_WESTERN_OUTLETS: dict[str, list[str]] = {
    "Asia": [
        # India
        "better india", "times of india", "indiatimes", "hindustan times", "hindustantimes", "ndtv",
        "india today", "indiatoday", "the hindu", "thehindu", "indian express", "indianexpress",
        "new indian express", "newindianexpress", "news18", "business standard", "business-standard",
        "livemint", "mint", "moneycontrol", "financial express", "financialexpress", "economic times",
        "zee news", "zeenews", "deccan", "the quint", "thequint", "theprint", "the print", "scroll.in",
        "firstpost", "telegraph india", "telegraphindia", "tribune india", "tribuneindia", "outlook india",
        "outlookindia", "dna india", "dnaindia", "the statesman", "thestatesman", "free press journal",
        "freepressjournal", "jagran", "bhaskar", "amar ujala", "lokmat", "mathrubhumi", "manorama",
        "yourstory", "wion", "republic world", "republicworld", "times now", "timesnownews", "abp live",
        "abplive", "india.com", "news9", "oneindia", "siasat", "theweek.in",
        "sportstar", "indiatvnews", "india tv", "etv bharat", "etvbharat", "pune mirror", "mid-day",
        "mumbai mirror", "greater kashmir", "kashmir observer", "the shillong times",
        "sentinel assam", "northeast now", "eastmojo", "the logical indian", "vibes of india",
        # Rest of Asia
        "dawn.com", "dawn", "express tribune", "tribune.com.pk", "geo news", "geo.tv", "thedailystar.net", "bdnews24", "dhaka tribune", "dhakatribune", "the kathmandu post", "kathmandupost",
        "daily mirror sri lanka", "straits times", "straitstimes", "south china morning post", "scmp",
        "inquirer.net", "philippine daily inquirer", "rappler", "gma news", "gmanetwork", "the star malaysia", "thestar.com.my",
        "jakarta post", "thejakartapost", "bangkok post", "bangkokpost", "vnexpress", "the nation thailand",
        "nationthailand", "korea herald", "koreaherald", "korea times", "koreatimes", "japan times",
        "japantimes", "china daily", "chinadaily", "global times", "globaltimes", "xinhua", "cgtn",
    ],
    "Africa": [
        "allafrica", "punchng", "punch nigeria", "vanguardngr", "vanguard nigeria", "premium times", "premiumtimesng",
        "nation.africa", "daily nation", "citizen.digital", "news24", "iol", "timeslive",
        "ghanaweb", "myjoyonline", "the east african", "theeastafrican", "mail & guardian", "egypt today",
        "egypttoday", "ahram", "the guardian nigeria", "guardian.ng", "businessday ng", "the standard kenya",
    ],
    "Middle East": [
        "al jazeera", "aljazeera", "gulf news", "gulfnews", "khaleej", "arab news", "arabnews",
        "the national news", "thenationalnews", "times of israel", "timesofisrael", "jerusalem post",
        "jpost", "haaretz", "middle east eye", "the new arab", "daily sabah", "hurriyet",
    ],
    "Latin America": [
        "mercopress", "buenos aires times", "batimes", "rio times", "riotimesonline", "mexico news daily",
        "mexiconewsdaily", "tico times", "ticotimes",
    ],
}
_OUTLET_RX = {
    region: re.compile(r"(?<![a-z])(?:" + "|".join(re.escape(n) for n in names) + r")(?![a-z])", re.IGNORECASE)
    for region, names in _NON_WESTERN_OUTLETS.items()
}
_TLD_REGION = {
    **dict.fromkeys(["in", "pk", "bd", "lk", "np", "sg", "my", "ph", "id", "th", "vn", "cn", "hk", "tw", "kr", "jp"], "Asia"),
    **dict.fromkeys(["ng", "ke", "za", "gh", "ug", "tz", "et", "zw", "eg", "ma"], "Africa"),
    **dict.fromkeys(["ae", "qa", "sa", "kw", "om", "bh", "jo", "lb", "il", "tr", "ir", "iq"], "Middle East"),
    **dict.fromkeys(["br", "ar", "mx", "cl", "pe", "ve", "ec", "uy", "py", "bo", "cr", "cu"], "Latin America"),
}


def is_uk_ie_site(homepage: str) -> bool:
    host = urlsplit(homepage).netloc.lower() if homepage else ""
    return host.endswith((".uk", ".ie"))


def region_for_source(source: str, homepage: str = "") -> str | None:
    """Region implied by the outlet itself (e.g. an Indian newspaper), if any."""
    host = urlsplit(homepage).netloc.lower() if homepage else ""
    tld = host.rsplit(".", 1)[-1] if "." in host else ""
    if tld in _TLD_REGION:
        return _TLD_REGION[tld]
    for region, rx in _OUTLET_RX.items():
        if rx.search(source or "") or (host and rx.search(host)):
            return region
    return None


# --------------------------------------------------------------------------- clickbait

# Headlines written to make you click rather than to tell you what happened: teasers,
# hype, listicles, questions, advice pieces, "the internet is loving…", notices and deals.
_CLICKBAIT_RX = re.compile(
    r"you won'?t believe|will (?:make you|restore your|melt your|leave you|have you|blow your)|"
    r"\byou(?:r|'re|'ll|'ve)?\b|\bhere'?s (?:why|what|how|the|who|where)|\bthis is (?:why|what|how|the)\b|"
    r"\bthat'?s why\b|the reason why|what happened next|\bwait (?:until|till|for)\b|"
    r"\b(?:melts?|melting|warms?|warming|broke|breaks|won) (?:the )?(?:hearts?|internet)\b|"
    r"\binternet (?:is|can'?t|goes|loses|reacts)|\b(?:goes|went|going|gone) viral\b|\bviral\b|"
    r"\btiktok|\binstagram|\btwitter\b|\breddit|\bnetizens\b|social media users|"
    r"\bfans (?:are|react|go|can'?t)|\bpeople are (?:loving|obsessed|losing)|"
    r"\b(?:heartwarming|adorable|sweet|wholesome|emotional|touching|hilarious|epic) (?:moment|video|clip|reaction)|"
    r"\bjaw-?dropping|\bmind-?blowing|\bshocking|\bstunning|\bincredible\b|\bamazing\b|\bunbelievable|"
    r"\bmust-see|\bnot what you|\bno one (?:expected|saw)|\bnobody (?:expected|saw)|\bthe truth about\b|"
    r"^(?:watch|video|photos?|quiz|opinion|review|comment|analysis|explainer|podcast|live|sponsored)\s*[:|-]|"
    r"\bhow to\b|\btips\b|\bhacks?\b|\bdeals?\b|\bon sale\b|\bsale\b|% off|\bgift guide|\bbest .{0,30} to buy|"
    r"\bdiscount|\bcoupon|\bpromo code|\bsponsored\b|"
    r"\bseeks?\b|\bseeking\b|\bsign up\b|\bapplications? (?:are )?(?:now )?open|\bhow to apply|\btickets?\b|"
    r"\bcall for (?:volunteers|entries|applications)|\bwhat to know\b|\beverything (?:we|you) know|"
    r"\bjust (?:obliterated|destroyed|nailed|crushed|schooled|owned|shut down|broke the)\b|^they (?:said|told)\b|"
    r"\b(?:blows|blew|blowing) (?:up|away)\b|\bslays\b|\bslayed\b|\bnails it\b|\bwins the internet|"
    r"\b(?:volunteers?|help|helpers|donations?) (?:are |is )?(?:needed|wanted)\b|\bappeal for\b|\blooking for volunteers|"
    r"\bwhat to do (?:with|about|if|when)\b|\bsee the (?:winning|best|photos|pictures|images|shots)\b|"
    r"\bwhen it'?s on\b|\bhow to watch\b|\bwhere to watch\b|\bfull details\b|\bepisode\b|\bseason \d|"
    r"\b(?:needs?|calls? for|appeals? for|seeks?|seeking|looking for|recruit\w*|wanted)\b.{0,30}\bvolunteers?\b|"
    r"\bvolunteer (?:opportunit|push|drive|board)|\bappeal\b|\bshortlist|\bfinalists?\b|\bnominations?\b|"
    r"\baward recipients\b|\bto host\b|\bwill host\b|\bworkshop\b|\bopen house\b|"
    r"\b(?:festival|fest|fair|show|event|market|parade|concert|race|walk|run|gala|exhibition)s?\b.{0,30}\breturns?\b|"
    r"^an evening (?:at|with|of)\b|\b(?:is|are) looking for\b|\bawards 20\d\d\b|\b(?:underlines|reaffirms|renews) "
    r"(?:its |their )?commitment\b|^[A-Z][a-z]+ [A-Z][a-z]+:\s|\bstage \d\b.{0,30}\b(?:water|drought|restriction)|"
    r"\bwater restrictions\b|\bhosepipe\b|"
    r"\bthis weekend\b|\bupcoming\b|\bsave the date\b|\bget set\b|\bforever home|\bnew home for\b|"
    r"\blooking for (?:a )?(?:new |loving )?home|\bsearching for a new\b|\b(?:available|up) for adoption|"
    r"\badoption fees|\bready for a rescue|^interview\s*:|\bcolumn\b|\bsneak peek|\bphoto gallery|\[photo|"
    r"\bpictures\s*:|^in pictures\b|\bmeet the\b|\bsurvey\b|\bpoll\b|"
    r"\bhow (?:u\.s\.|uk|people|americans|britons) (?:adults )?(?:give|support)|"
    r"\bawarded\b.{0,40}\b(?:fund|funding|grant)|\b(?:fund|funding|grants?)\b.{0,20}\bawarded\b|\bfirst round of\b|"
    r"^\[[^\]]+\]|\bdoc talk\b|\bcommentary\b|^editorial\b|\beditorial\s*[|:]|\bop-ed\b|\bpodcast\b|\btranscript\b|"
    r"^the download\b|\bto watch\b|\bat [A-Z]{3,}\s?20\d\d\b|\bcongress\b|\bconference\b|\bto attend\b|"
    r"\bopens? applications\b|\bholding\b.{0,30}\b(?:training|event|meeting|session)|\bvolunteer training\b|"
    r"\btop[- ]performing\b|\bby decade\b|®|™|\([A-Z]{2,5}\)|\bfy\s?\d\d\b|\bq[1-4] (?:20)?\d\d\b|\?\s+[-–|]|"
    r"^good news in history\b|\bgrants?\s*:|\bfunding opportunit|\bcall for proposals|\bapply now\b",
    re.IGNORECASE,
)
_LISTICLE_RX = re.compile(
    r"^(?:the )?\d+\s+(?:\w+\s+){0,2}(?:things|ways|reasons|tips|times|signs|photos|pictures|pics|facts|places|"
    r"books|ideas|moments|stories|habits|foods|lessons|secrets|tricks|products|gifts|advances|breakthroughs|"
    r"trends|innovations|companies|startups)\b",
    re.IGNORECASE,
)


# Swearing, in any source (memes included): Sunnyside is a family-friendly morning read.
PROFANITY = re.compile(
    r"\bf[\*u@#]{1,3}c?k|\bfck|\bsh[\*i]t|\bb[\*i]tch|\bc[\*u]nt|\bwtf\b|\bassh[o0]le|\bdick(?:head)?\b|"
    r"\bbastard|\bpiss(?:ed)?\b|\bcrap\b|\bdamn\b|\bslut|\bwhore|\bnsfw\b",
    re.IGNORECASE,
)


_QUOTES = str.maketrans({"\u2018": "'", "\u2019": "'", "\u201c": '"', "\u201d": '"'})


def is_clickbait(title: str) -> bool:
    t = title.strip().translate(_QUOTES)
    return bool(
        _CLICKBAIT_RX.search(t) or _LISTICLE_RX.search(t)
        or "?" in t or t.endswith(("...", "…")) or "!" in t
    )


# Tabloids, viral-content sites and entertainment outlets: their good news is mostly
# clickbait. Matched against the outlet's name and website.
_TABLOID_RX = re.compile(
    r"daily ?mail|mail ?online|mirror\.co\.uk|\bthe mirror\b|daily mirror|\bexpress\.co\.uk|daily express|"
    r"thesun\.|the-sun\.com|\bthe sun\b|\bthe us sun\b|dailystar\.|daily star|dailyrecord|daily record|\bmetro\.co\.uk|^metro$|"
    r"ladbible|unilad|\bthe tab\b|newsweek|nypost|new york post|pagesix|\btmz\b|eonline|e! online|"
    r"people\.com|^people$|usmagazine|us weekly|hellomagazine|hello!|ok!|closer ?online|\bheat ?world|"
    r"boredpanda|bored panda|distractify|upworthy|inspiremore|inspire more|twistedsifter|someecards|"
    r"buzzfeed|\bparade\b|parade\.com|whimsy|shared\.com|diply|viralnova|the dodo|thedodo|"
    r"animalsaroundtheglobe|dogtime|pawtracks|countryliving|"
    r"business ?wire|pr ?newswire|globe ?newswire|accesswire|einpresswire|press release|investing\.com|"
    r"stt info|marketscreener|yougov|american hunter|\bwwd\b|36kr|vietnam\.vn|fundsforngos|tipranks|simply ?wall|"
    r"thebusinessdesk|biggo|scanx|chosunbiz|stock ?titan|investing news|arabian business|economy\.ac|"
    r"modern healthcare|socialsamosa|marketing-interactive|contentgrip|roastbrief|ecommercenews|6ix retail|lavx|"
    r"cyberkendra|insight eu|hypefresh|express computer|zawya|energyglobal|manila times|ritzau|about amazon|"
    r"pressnetwork|businessweekly|business standard|seeking ?alpha|motley fool|benzinga|zacks|fiercebiotech|"
    r"fierce pharma|biospace|pharmaphorum|oncodaily|finance\.|\bani news\b|globetrender|tvguide|tv guide|radiotimes|radio times|espn|sky sports|bbc sport|the athletic|yahoo|\bmsn\b|aol\.com|newsbreak|dailyhunt|\bnews18|wionews|\bzee ?news|\bindia\.com",
    re.IGNORECASE,
)


def is_tabloid(source: str, homepage: str = "") -> bool:
    host = urlsplit(homepage).netloc.lower() if homepage else ""
    return bool(_TABLOID_RX.search(source or "") or (host and _TABLOID_RX.search(host)))


# Place names that look royal but aren't.
_NOT_ROYAL = re.compile(r"prince edward island|prince rupert|prince george, b|queensland|kingston|kings cross", re.IGNORECASE)


def is_off_topic(title: str, summary: str = "") -> bool:
    """Celebrity or royalty news, which Sunnyside leaves out."""
    text = _NOT_ROYAL.sub(" ", f"{title}\n{summary}")
    return bool(OFF_TOPIC.search(text))


# --------------------------------------------------------------------------- language

_WORD_RX = re.compile(r"[^\W\d_]+", re.UNICODE)
_EN_WORDS = set("""
the a an and of to in is are was were be been for on with at by from this that these it its as has have had
will would can could you your our their they he she we not but or new first after how why what who when
into over up out about more than just all one two can't won't it's don't i my me us his her them there
""".split())
# Common words in other languages (words that are also English removed).
_OTHER_WORDS = {
    "de": "der das und ist nicht ein eine mit für auf dem des sich auch wird werden über zum zur bei nach vom sind noch wie".split(),
    "fr": "le la les et est une des du pour dans sur avec pas aux ce cette sont qui que au ont été leur".split(),
    "es": "el la los las y es una por con para del al que se su sus más como pero muy está son fue".split(),
    "it": "il lo gli e è una con del della che non sono più anche nel alla dei".split(),
    "pt": "o os e é um uma para com da dos das que não mais está são foi pelo".split(),
    "nl": "het een en van voor op niet zijn dat ook bij naar wordt deze".split(),
}
_ACCENTS = set("äöüßéèêëàâçñãõìíîïòóôùúûœ")


def is_english(text: str) -> bool:
    """Cheap language check for headlines and snippets: stopwords plus script."""
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return True
    non_latin = sum(1 for c in letters if ord(c) > 0x24F)
    if non_latin / len(letters) > 0.2:
        return False
    words = [w.lower() for w in _WORD_RX.findall(text)]
    en = sum(1 for w in words if w in _EN_WORDS)
    other = max(sum(1 for w in words if w in lang) for lang in _OTHER_WORDS.values())
    accents = sum(1 for c in letters if c.lower() in _ACCENTS)
    if other > en:
        return False
    if other == en and other > 0:
        return accents == 0
    return en > 0 or accents / len(letters) < 0.03


def is_sport(title: str, summary: str = "") -> bool:
    return bool(CATEGORY_KEYWORDS["Sport"].search(title)) or len(CATEGORY_KEYWORDS["Sport"].findall(summary)) >= 2


# --------------------------------------------------------------------------- AI for good

_AI_RX = re.compile(r"\b(?:AI|LLMs?|GenAI)\b|\bA\.I\.")
_AI_WORDS_RX = re.compile(
    r"artificial intelligence|machine learning|deep learning|neural network|large language model|"
    r"\bchatbot|alphafold|deepmind|computer vision|openai|chatgpt|anthropic|\bcopilot\b|deepfake|nvidia",
    re.IGNORECASE,
)
# What makes an AI story good news: AI helping people, science, health or the planet.
AI_HELPS = _rx([
    "help", "detect", "diagnos", "predict", "discover", "find!", "finds!", "found!", "spot", "speed", "faster",
    "save!", "saves!", "saving", "saved", "cure", "treat", "translat", "accessib", "blind", "deaf", "paralys",
    "restore", "protect", "track", "map!", "maps!", "mapping", "forecast", "early warning", "breakthrough",
    "advance", "new drug", "antibiotic", "protein", "enzyme", "wildlife", "conservation", "climate", "energy",
    "crop", "farm", "doctor", "nurse", "patient", "scientist", "research", "cancer", "disease", "rescue",
    "voice", "sight", "hearing", "speak", "walk", "clean", "reduce", "cut!", "cuts!",
])
# ...and what doesn't: the AI industry, its risks and its controversies.
AI_BAD = _rx([
    "deepfake", "job!", "jobs!", "layoff", "replac", "slop", "misinformation", "disinformation", "bubble", "hype",
    "scam", "fraud", "copyright", "plagiar", "cheat", "lawsuit", "sued", "regulat", "ban!", "bans!", "banned",
    "warn", "risk", "danger", "threat", "existential", "superintelligen", "surveillance", "facial recognition",
    "military", "weapon", "girlfriend", "boyfriend", "companion", "romance", "energy use", "power use",
    "power demand", "electricity demand", "data cent", "water use", "chip", "nvidia", "openai", "stock",
    "valuation", "funding round", "raises $", "investment", "investor", "startup", "ipo!", "billion", "trillion",
    "ceo!", "musk", "altman", "zuckerberg", "arms race", "race to", "bias", "hallucinat", "mistake", "error",
    "addict", "lonel", "harm", "abuse", "privacy", "hack", "cyber", "spy", "propaganda", "election",
    "brand", "marketing", "advertis", "shop", "retail", "commerce", "enterprise", "agentic", "deploy", "market!",
    "markets!", "security", "flaw", "vulnerab", "xss", "gaps", "favour", "favor", "citation", "overview", "seo!",
    "geo!", "tools!", "platform", "receptionist", "tracker", "secures", "logistics", "claim", "harder test",
    "problem", "rfk", "kennedy", "tyranny", "trend", "revive", "mammoth", "anniversary", "award", "partners",
    "invest", "customer", "sales", "productivity", "workforce", "employ", "cheaper", "profit",
])
# Who or what an AI story must be helping, for it to count as AI for good.
AI_BENEFITS = _rx([
    "patient", "doctor", "nurse", "hospital", "disease", "cancer", "tumour", "tumor", "diabetes", "stroke", "heart",
    "alzheimer", "dementia", "parkinson", "als!", "mnd!", "blind", "deaf", "paralys", "voice", "speech", "disab",
    "drug", "antibiotic", "protein", "enzyme", "molecul", "gene", "medic", "vaccine", "diagnos", "surgery", "sepsis",
    "species", "wildlife", "animal", "rhino", "whale", "bird", "forest", "conservation", "poach", "climate",
    "weather", "flood", "wildfire", "earthquake", "crop", "farm", "food", "water", "energy", "solar", "emission",
    "carbon", "pollution", "scientist", "science", "mathemat", "rescue", "children", "language", "translat",
])


def mentions_ai(title: str) -> bool:
    return bool(_AI_RX.search(title) or _AI_WORDS_RX.search(title))


def is_ai_for_good(title: str, summary: str = "") -> bool:
    """An AI story about AI helping people or the planet, not the industry or its risks."""
    return (mentions_ai(title) and bool(AI_HELPS.search(title)) and bool(AI_BENEFITS.search(title))
            and not AI_BAD.search(f"{title}\n{summary}") and not title.rstrip().endswith("?"))


# --------------------------------------------------------------------------- progress on hard problems
#
# Good news about really bad things: a cancer treatment, an Alzheimer's drug, emissions
# falling. Those headlines necessarily mention death, decline, disease or warming, which
# would otherwise sink them, so a story that pairs a hard problem with progress on it is
# judged without those words.

HARD_PROBLEMS = _rx([
    "cancer", "tumour", "tumor", "leukaemia", "leukemia", "lymphoma", "melanoma", "myeloma", "alzheimer",
    "dementia", "parkinson", "motor neurone", "mnd!", "als!", "multiple sclerosis", "huntington", "diabetes",
    "stroke", "heart disease", "heart failure", "sickle cell", "cystic fibrosis", "muscular dystrophy",
    "malaria", "hiv!", "aids!", "tuberculosis", "antibiotic resist", "antimicrobial resist", "superbug",
    "sepsis", "blindness", "paralys", "spinal cord", "epilepsy", "rare disease", "genetic disease", "brain injur",
    "climate", "emission", "carbon", "greenhouse", "global warming", "fossil fuel", "coal!", "deforestation",
    "extinct", "endangered", "coral", "ocean acid", "plastic pollution", "pollution", "air quality",
])
PROGRESS = _rx([
    "breakthrough", "cure", "cured", "treatment", "therapy", "therapies", "vaccine", "drug!", "drugs!", "trial",
    "approved", "approval", "slows", "slowed", "slowing", "halts", "halted", "reverse", "reverses", "reversed",
    "remission", "survival", "detect", "early diagnosis", "screening", "new test", "blood test", "gene therapy",
    "gene editing", "crispr", "immunotherapy", "restor", "record low", "fall!", "falls!", "fell!", "drop",
    "cut!", "cuts!", "slash", "phase out", "phase-out", "renewable", "solar", "wind power", "wind farm",
    "clean energy", "carbon capture", "rewild", "reforest", "recover", "rebound", "comeback", "protect",
    "saved", "saves", "milestone", "first time", "promising", "discover", "invent", "new way", "innovat",
    "success", "hope", "win!", "wins!", "record", "lowest", "back from the brink",
])
# Words that come with the territory in such stories.
_INHERENT = {
    "dies", "died", "death", "dead", "deadly", "die!", "decline", "crisis", "warming", "loss!", "losses",
    "losing", "lose!", "threat", "suffer", "struggl", "victim", "extinct", "toxic", "pollut",
    "outbreak", "pandemic", "cancer risk", "incurable",
}
DOOM_FOR_PROGRESS = _rx([w for w in _DOOM_WORDS if w not in _INHERENT])
HARD_BLOCK_FOR_PROGRESS = _rx(["killed", "murder", "massacre", "rape", "suicide", "terror", "shooting",
                               "stabbing", "genocide", "bombing", "death toll", "war crime", "body found"])


# The problem getting worse, however much progress is mentioned alongside it.
_WORSENING = re.compile(
    r"\b(?:deaths?|cases|emissions|infections|diagnoses|temperatures?|incidence|mortality)\b.{0,20}"
    r"\b(?:record high|highest|rise|rises|rising|risen|rose|increase[sd]?|increasing|surge[sd]?|soar(?:s|ed)?|climb(?:s|ed)?|jump(?:s|ed)?|"
    r"double[sd]?|spike[sd]?)\b|\b(?:rise|rising|increase|surge|spike|jump) in\b|\bdespite\b|\bsetback\b|"
    r"\bfail(?:s|ed|ure)?\b|\bnot enough\b|\bbut only\b|\bway off\b|\bstill off\b|\boff (?:its|their|the) "
    r"(?:\d{4} )?(?:climate )?targets?\b|\bbehind schedule\b|\breveals?\b.{0,40}\bdiagnos|\bdiagnosed with\b|"
    r"\bbattl(?:e|es|ing) (?:with )?\w*\s?cancer|\bdrops?\b.{0,30}\bdrug\b|\bless time\b|\bheat(?:s|ing)? up\b|\bworsen|\bshrink|"
    r"\bbleach|\bdie[sd]? off\b|\boff track\b|\bfalls? short\b|\bwithdrawn\b|\babandon",
    re.IGNORECASE,
)


def is_progress(title: str) -> bool:
    """A headline about progress on a hard problem (disease, climate, extinction)."""
    return bool(HARD_PROBLEMS.search(title) and PROGRESS.search(title) and not _WORSENING.search(title))


def passes_keyword_filter(title: str, summary: str, trusted: bool, min_positivity: int = 3) -> bool:
    if mentions_ai(title) and not is_ai_for_good(title, summary):
        return False  # AI stories only when AI is helping
    if HARD_PROBLEMS.search(title) and _WORSENING.search(title):
        return False  # a hard problem getting worse, whatever else the headline says
    if trusted:
        return not is_hard_blocked(title)
    text = f"{title}\n{summary}"
    if is_progress(title) or is_ai_for_good(title, summary):
        return not (HARD_BLOCK_FOR_PROGRESS.search(text) or DOOM_FOR_PROGRESS.search(text))
    if is_hard_blocked(text) or DOOM.search(text):
        return False
    return positivity(title, summary) >= min_positivity


_MEDICAL = _rx(["cancer", "tumour", "tumor", "leukaemia", "leukemia", "lymphoma", "melanoma", "alzheimer", "dementia",
                "parkinson", "motor neurone", "multiple sclerosis", "diabetes", "stroke", "heart", "sickle cell",
                "malaria", "hiv!", "tuberculosis", "sepsis", "blindness", "paralys", "epilepsy", "disease",
                "vaccine", "drug!", "drugs!", "therapy", "treatment", "patient"])
_CLIMATE = _rx(["climate", "emission", "carbon", "greenhouse", "global warming", "fossil fuel", "coal!",
                "renewable", "solar", "wind power", "wind farm", "clean energy", "deforestation", "pollution"])


def guess_category(title: str, summary: str) -> str:
    if is_ai_for_good(title, summary):
        return "AI"
    if is_progress(title):
        if _MEDICAL.search(title):
            return "Health"
        if _CLIMATE.search(title):
            return "Environment"
    best, best_hits = "Community", 0
    for category, rx in CATEGORY_KEYWORDS.items():
        hits = 2 * len(rx.findall(title)) + len(rx.findall(summary))
        if hits > best_hits:
            best, best_hits = category, hits
    return best


# Places elsewhere that share a UK or Irish name.
_NOT_UK = re.compile(
    r"british columbia|new south wales|british virgin|new england|new london|new hampshire|new jersey|\bkent(?= county)|\bscotland(?=,? county| county|,? (?:s\.?d|ct|conn|pa|tx|ga)\b)|"
    r"\b(?:dublin|london|birmingham|manchester|cambridge|oxford|kent|cork|belfast|newcastle|brighton|"
    r"bristol|leeds|glasgow|aberdeen|sheffield|norfolk|essex|devon|cornwall|durham)(?=\s*,\s*"
    r"(?:ohio|oh|calif|california|ca|ga|georgia|va|virginia|ky|kentucky|tx|texas|al|ala|alabama|nh|mass|"
    r"massachusetts|ma|conn|ct|ontario|ont|ms|miss|mississippi|wash|wa|me|maine|ny|pa|nc|sc|tn|fl|nsw|n\.s\.w)\b)",
    re.IGNORECASE,
)


def guess_region(title: str, summary: str) -> str:
    title, summary = _NOT_UK.sub(" ", title), _NOT_UK.sub(" ", summary)
    best, best_hits = "Global", 0
    for region, rx in _REGION_RX.items():
        hits = 2 * len(rx.findall(title)) + len(rx.findall(summary))
        if hits > best_hits:
            best, best_hits = region, hits
    return best
