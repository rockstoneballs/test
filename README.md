# ☀️ Sunnyside: only good news

Sunnyside is a news app and website that only shows good news, meant to be the
first thing you open in the morning. It works like Reddit: communities, Hot / New / Top
sorting, upvotes, comment counts, and card or compact layouts. But everything in it
is good news, wholesome memes or cute animals. There's also a **Kitten of the Day**
and a **Puppy of the Day**.

* **Android app:** `android/` (Kotlin + Jetpack Compose)
* **Website:** `web/` (plain HTML/CSS/JS, no build step)
* **Scraper:** `scraper/` (Python). It runs on GitHub Actions every 30 minutes and
  publishes `feed.json`, which both the app and the website read.

```
 ┌──────────────────────────────────┐  every 30 min  ┌──────────────────────────────┐
 │ GitHub Actions: scrape.yml       │ ─────────────▶ │ GitHub Pages                 │
 │  • 30+ good-news & world RSS     │   publishes    │  index.html  ← the website   │
 │  • Google News searches          │                │  feed.json   ← the data      │
 │  • Reddit, Lemmy, Mastodon       │                └──────┬───────────────┬───────┘
 │  • positivity filter (Claude)    │                       │               │
 │  • kitten + puppy of the day     │                  Android app      web browsers
 └──────────────────────────────────┘
```

## Communities

| Community | What's in it | Where it comes from |
|---|---|---|
| s/WholesomeMemes 😂 | Feel-good memes | r/wholesomememes, r/wholesome, Lemmy |
| s/Aww 🥹 | Cute animal photos and videos | r/aww, r/Eyebleach, r/rarepuppers, r/IllegallySmolCats, Lemmy, Mastodon #CatsOfMastodon / #DogsOfMastodon / #Caturday |
| s/MadeMeSmile 😊 | People (and animals) being lovely | r/MadeMeSmile, r/HumansBeingBros, r/AnimalsBeingBros, Lemmy, Mastodon #wholesome |
| s/Science, s/Environment, s/Health, s/Animals, s/Community, s/Innovation, s/Culture, s/Sport | Good-news stories, sorted by topic | Good-news outlets, world news filtered for positivity, Google News, r/UpliftingNews, r/goodnews |

Upvote and comment counts on posts from Reddit, Lemmy and Mastodon are real, and they're
refreshed on every scrape. When a news story was also shared on r/UpliftingNews, it
gets that thread's votes and a link to the discussion. Your own votes, saved posts
and joined communities stay on your device. There are no accounts yet.

**Hot** ranking (the same formula in `web/app.js` and `Ranking` in the app) =
uplift score + a capped boost from upvotes + your vote − 1 point per 6 hours of age.
The cap keeps news, which has no Reddit votes, from being buried under memes.

## Sources and filtering (`scraper/`)

* **Dedicated good-news outlets** are included as-is: Good News Network,
  Positive News, Reasons to be Cheerful, The Optimist Daily, YES!, The Guardian's
  *The Upside*, Good Good Good, Nice News, Inspire More, Squirrel News, The Better
  India and Sunny Skyz.
* **World and science news** only gets in if it passes the positivity filter.
  Sources: BBC, NPR, Al Jazeera, The Guardian, DW, France 24, CBC, ABC Australia,
  AllAfrica, ScienceDaily, Phys.org, ScienceAlert, New Atlas, NASA, Smithsonian,
  Mongabay, and Google News searches like "good news", "heartwarming" and
  "conservation success".
* **Positivity filter.** With `ANTHROPIC_API_KEY` set, Claude reads each new
  headline, decides whether it's really good news, scores how uplifting it is,
  tags the topic and region, and writes a short summary. Without a key, a strict
  keyword filter is used instead.
* **Memes and cute animals** come from Reddit, Lemmy and Mastodon. NSFW and spoiler posts
  are dropped, and so are posts below a per-community upvote threshold. Cute
  animals stay for 3 days, news for 7, and memes for up to 30 (they don't go stale).
* A dead source is logged and skipped. It never breaks a run.

## Setup

### 1. Publish the website and feed

1. **Settings → Pages → Build and deployment → Source: GitHub Actions.**
2. Merge to `main`. The **Scrape good news** workflow then runs every 30 minutes
   and deploys the site to `https://rockstoneballs.github.io/test/`.

Optional repository secrets (**Settings → Secrets and variables → Actions**):

| Secret | What it does |
|---|---|
| `ANTHROPIC_API_KEY` | Claude decides what counts as good news. Model `claude-opus-5`; override with `GOODNEWS_MODEL`. |
| `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET` | Reliable Reddit access. Reddit often blocks anonymous requests from GitHub's servers. Create a free "script" app at <https://www.reddit.com/prefs/apps>. Without these, Lemmy and Mastodon still supply memes and cute animals. |
| `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | Signs release APKs with your own key, so updates install over the previous version. |

On branches other than `main`, the scrape runs as a dry run. The result is
uploaded as a `site-preview` artifact, so you can check source changes before
merging.

### 2. Get the Android app

Every push builds the app (**Actions → CI → `sunnyside-apk` artifact**). On `main`
the APK is also published to the
[latest release](https://github.com/rockstoneballs/test/releases/latest). That's
what the website's **Get the app** button links to.

To build locally (you need JDK 17 and the Android SDK):

```bash
cd android
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

The app reads `https://rockstoneballs.github.io/test/feed.json`. To change that,
edit `sunnyside.feedUrl` in `android/gradle.properties`, or build with
`-Psunnyside.feedUrl=…`.

### Running things locally

```bash
cd scraper
pip install -r requirements-dev.txt
python -m pytest -q
python -m goodnews.scrape --out ../site            # --no-claude, --no-social, --no-pets to skip parts
cp -r ../web/. ../site/ && python -m http.server -d ../site 8000   # website at http://localhost:8000
```

## Ideas for what's next

* Real accounts, so votes and comments are shared between people (needs a
  backend such as Supabase or Firebase)
* Push notifications through FCM for breaking good news
* Personalised ranking based on what each person upvotes and saves
* Play Store release: a signing key, a privacy policy and store listing art
