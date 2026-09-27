"""Keyword-based positivity scoring, topic and region tagging.

This is the no-API-key path. It's deliberately conservative for mainstream
sources: any "doom" word rejects the story outright, and it needs several
uplifting signals to get in. When ANTHROPIC_API_KEY is set, Claude does this
job instead (see classifier.py) and these functions are only used as a fallback.
"""

from __future__ import annotations

import re

CATEGORIES = [
    "Science", "Environment", "Health", "Animals",
    "Community", "Innovation", "Culture", "Sport",
]

REGIONS = [
    "Africa", "Asia", "Europe", "Latin America",
    "Middle East", "North America", "Oceania", "Global",
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
    "killed", "killing", "murder", "massacre", "rape", "suicide", "terror",
    "shooting", "shot dead", "stabbing", "genocide", "beheaded", "bombing",
    "dead bod", "death toll", "fatal", "slaughter", "abuse", "hostage",
    "war crime", "execution", "executed",
])

# Words that make mainstream stories too gloomy for the feed.
DOOM = _rx([
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
])

UPLIFT = _rx([
    "breakthrough", "cure", "cured", "rescue", "saved", "saves", "save!", "restor",
    "recover", "reunit", "donat", "volunteer", "celebrat", "first-ever", "first ever",
    "milestone", "thriv", "success", "record high", "record-breaking", "wins", "won!",
    "award", "hope", "kindness", "kind-hearted", "generous", "generosity", "heartwarming", "inspir",
    "protect", "conservation", "renewable", "clean energy", "solar", "wind farm",
    "reforest", "rewild", "rebound", "comeback", "bounce back", "discover", "innovat",
    "healed", "healing", "heals", "vaccine", "eradicat", "free!", "for free", "joy", "happy", "happiest",
    "smile", "delight", "adorable", "cute", "born", "baby", "hatch", "returns to",
    "spotted for the first time", "back from the brink", "good news", "uplifting",
    "helps", "helping", "gift", "surprise", "dream", "improv", "boost", "cleaner",
    "reduce emissions", "cut emissions", "planted", "trees", "bees", "wildlife",
    "sanctuary", "adopt", "graduat", "scholarship", "literacy", "peace", "ceasefire agreed",
    "lifesaving", "life-saving", "promising", "remarkable", "incredible", "amazing",
])

CATEGORY_KEYWORDS: dict[str, re.Pattern[str]] = {
    "Animals": _rx([
        "animal", "wildlife", "species", "dog", "puppy", "cat!", "cats!", "kitten", "bird",
        "whale", "dolphin", "turtle", "elephant", "tiger", "lion", "bear", "wolf", "wolves",
        "bee!", "bees!", "butterfl", "koala", "panda", "rhino", "gorilla", "otter", "beaver",
        "shark", "coral", "sanctuary", "zoo", "pet!", "pets!", "horse", "owl", "penguin",
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
        "artificial intelligence", "ai!", "battery", "prototype", "innovation", "design",
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
    "Europe": [
        "europe", "european", "uk", "u.k.", "britain", "british", "england", "english",
        "scotland", "scottish", "wales", "welsh", "ireland", "irish", "france", "french",
        "germany", "german", "spain", "spanish", "italy", "italian", "portugal", "netherlands",
        "dutch", "belgium", "switzerland", "swiss", "austria", "sweden", "swedish", "norway",
        "norwegian", "denmark", "danish", "finland", "finnish", "iceland", "poland", "polish",
        "czech", "slovakia", "hungary", "romania", "bulgaria", "greece", "greek", "croatia",
        "serbia", "slovenia", "estonia", "latvia", "lithuania", "london", "paris", "berlin",
        "madrid", "rome", "amsterdam", "copenhagen", "stockholm", "dublin", "edinburgh",
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


WESTERN_REGIONS = {"Europe", "North America", "Oceania"}

# Outlets whose stories are almost always about one non-Western region.
_NON_WESTERN_OUTLETS: dict[str, re.Pattern[str]] = {
    region: re.compile(r"\b(?:" + pattern + r")", re.IGNORECASE)
    for region, pattern in {
        "Asia": r"the better india|times of india|hindustan times|ndtv|india today|the hindu\b|indian express|"
                r"news18|deccan|theprint|the print|scroll\.in|livemint|economic times|tribune india|firstpost|"
                r"wion|dawn\b|express tribune|geo news|daily star|straits times|south china morning post|scmp|"
                r"inquirer|jakarta|bangkok post|vnexpress|the nation thailand|korea herald|japan times|china daily",
        "Africa": r"allafrica|punch ng|vanguard|premium times|daily nation|the citizen|news24|iol\b|"
                  r"ghanaweb|the east african|mail & guardian",
        "Middle East": r"al jazeera|gulf news|khaleej|arab news|the national\b|times of israel|jerusalem post",
        "Latin America": r"mercopress|buenos aires times|rio times|mexico news daily|tico times",
    }.items()
}


def region_for_source(source: str) -> str | None:
    """Region implied by the outlet itself (e.g. an Indian newspaper), if any."""
    for region, rx in _NON_WESTERN_OUTLETS.items():
        if rx.search(source or ""):
            return region
    return None


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


def passes_keyword_filter(title: str, summary: str, trusted: bool) -> bool:
    if trusted:
        return not is_hard_blocked(title)
    text = f"{title}\n{summary}"
    if is_hard_blocked(text) or DOOM.search(text):
        return False
    return positivity(title, summary) >= 3


def guess_category(title: str, summary: str) -> str:
    best, best_hits = "Community", 0
    for category, rx in CATEGORY_KEYWORDS.items():
        hits = 2 * len(rx.findall(title)) + len(rx.findall(summary))
        if hits > best_hits:
            best, best_hits = category, hits
    return best


def guess_region(title: str, summary: str) -> str:
    best, best_hits = "Global", 0
    for region, rx in _REGION_RX.items():
        hits = 2 * len(rx.findall(title)) + len(rx.findall(summary))
        if hits > best_hits:
            best, best_hits = region, hits
    return best
