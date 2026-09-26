# ☀️ Sunnyside: only good news

Sunnyside is a news app that only shows good news. It's meant to be the first
thing you open in the morning, instead of the usual doom.

It works like any modern news app: a lead story, top stories, topic filters,
"around the world" coverage, search, saved stories and sharing. It also has a
**Kitten of the Day** and **Puppy of the Day**, and an optional morning
notification when the day's good news is ready.

Android comes first. The feed is a plain JSON file, so a website or iOS app can
use the same feed later.

## How it works

```
 ┌────────────────────────────┐   every 2h    ┌──────────────────────┐
 │ GitHub Actions: scrape.yml │ ────────────▶ │ GitHub Pages         │
 │  scraper/ (Python)         │   publishes   │  feed.json           │
 │  • 13 RSS feeds            │               └──────────┬───────────┘
 │  • positivity filter       │                          │ downloads
 │  • topic + region tagging  │               ┌──────────▼───────────┐
 │  • kitten + puppy of day   │               │ Android app          │
 └────────────────────────────┘               │  android/ (Compose)  │
                                              └──────────────────────┘
```

### Scraper (`scraper/`)

* **Sources** (`goodnews/sources.py`). Dedicated good-news outlets (Good News
  Network, Positive News, Reasons to be Cheerful, The Optimist Daily, YES!,
  The Guardian's *The Upside*, Good Good Good) plus general and science
  outlets (BBC, NPR, Al Jazeera, ScienceDaily, Mongabay) for wider global
  coverage.
* **Positivity filter**
  * **With `ANTHROPIC_API_KEY` set:** Claude reads each new headline and snippet,
    decides whether it's really good news, scores how uplifting it is (0–10),
    tags the topic and region, and writes a short summary
    (`goodnews/classifier.py`). This catches cases keywords miss, like "40
    people died despite rescue efforts".
  * **Without a key:** conservative keyword scoring (`goodnews/keywords.py`).
    Stories from general outlets need several uplifting signals and no "doom"
    words to get in.
* **Kitten and Puppy of the Day** (`goodnews/pets.py`). Photos come from The
  Cat API and the Dog CEO API. Each pet gets a name and caption picked from the
  date. They're chosen once per UTC day, so every user sees the same ones. The
  last 14 days are kept for the "previous cuties" gallery.
* Each run merges with the previously published feed, so stories stay for 7
  days. It skips duplicates and doesn't send the same story to Claude twice.

### Android app (`android/`)

Kotlin, Jetpack Compose, Material 3, Room, WorkManager, DataStore, Coil and
OkHttp. Min SDK 26 (Android 8.0).

* **Today:** greeting, topic chips, lead story, cuteness break (kitten +
  puppy), top stories, an "Around the world" carousel with one story per
  region, then more stories. Pull to refresh.
* **Explore:** search, browse by region or topic.
* **Saved:** bookmarked stories, kept even after they leave the feed.
* **Story page:** summary, "mood boost" rating, related stories, share, and
  "Read the full story" in an in-app browser tab.
* **Pets page:** today's kitten or puppy with a share button, plus a gallery
  of previous days.
* **Settings:** morning briefing notification on/off and time (default
  7:00), light/dark/system theme, list of sources.
* Works offline from its cache and refreshes in the background every 3
  hours.
* If the published feed can't be reached (for example before Pages is set
  up), the app reads the dedicated good-news RSS feeds directly and fetches a
  kitten and puppy itself. So a fresh install is never empty.

## Getting it running

### 1. Get the APK

Every push runs the **CI** workflow, which builds the app. Open the run in the
repo's **Actions** tab and download the `sunnyside-apk` artifact. Install
`app-debug.apk` on an Android phone (you'll need to allow installs from
unknown sources).

To build locally you need JDK 17 and the Android SDK:

```bash
cd android
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

### 2. Publish the feed (one-time setup)

1. **Settings → Pages → Build and deployment → Source: GitHub Actions.**
2. *(Optional, recommended)* **Settings → Secrets and variables → Actions →
   New repository secret** `ANTHROPIC_API_KEY`. This turns on Claude
   filtering (default model `claude-opus-5`). To use a different model, set
   the `GOODNEWS_MODEL` environment variable in `scrape.yml`.
3. Merge to `main`. The **Scrape good news** workflow runs on every push to
   `scraper/` and then every two hours. You can also start it by hand from the
   Actions tab.

The app reads `https://rockstoneballs.github.io/test/feed.json` by default. To
point it somewhere else, change `sunnyside.feedUrl` in
`android/gradle.properties`, or pass
`-Psunnyside.feedUrl=https://…/feed.json` when building.

> GitHub Pages on a private repository needs a paid GitHub plan. You can make
> the repo public, or host `feed.json` anywhere else (S3, Cloudflare R2,
> Netlify, …) and point `sunnyside.feedUrl` at it.

### Running the scraper locally

```bash
cd scraper
pip install -r requirements-dev.txt
python -m pytest -q
python -m goodnews.scrape --out ../site            # add --no-claude to force keyword mode
```

## Ideas for what's next

* A web version at the same Pages site, reading the same `feed.json`
* Push notifications through FCM instead of a local daily schedule
* Personalised "For you" ranking based on the topics people read and save
* Play Store release: a real signing key, a privacy policy and store listing
  art
