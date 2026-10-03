# ☀️ Sunnyside: only good news

Sunnyside is a news app and website that only shows good news, meant to be the
first thing you open in the morning. It's a news site, not a social site: one feed
of good news from around the world, laid out like Reddit's feed (cards or a compact
list, **Top stories** or **Latest**), with wholesome memes and cute animals sprinkled
in, plus a random cat or dog photo every few posts. There are no votes, comments or
accounts.

* **Android app:** `android/` (Kotlin + Jetpack Compose)
* **Website:** `web/` (plain HTML/CSS/JS, no build step)
* **Scraper:** `scraper/` (Python). It runs on GitHub Actions every 30 minutes and
  publishes `feed.json`, which both the app and the website read.

```
 ┌──────────────────────────────────┐  every 30 min  ┌──────────────────────────────┐
 │ GitHub Actions: scrape.yml       │ ─────────────▶ │ GitHub Pages                 │
 │  • 30+ good-news & world RSS     │   publishes    │  index.html  ← the website   │
 │  • Google News searches          │                │  feed.json   ← the data      │
 │  • Reddit, Lemmy, 9GAG, Imgur    │                └──────┬───────────────┬───────┘
 │  • positivity filter (Claude)    │                       │               │
 │  • random cat and dog photos     │                  Android app      web browsers
 └──────────────────────────────────┘
```

## What's in the feed

Every post has a small topic tag, like Reddit's post flair:

| Tag | What it is | Where it comes from |
|---|---|---|
| 😂 Meme | Feel-good memes | r/wholesomememes, r/wholesome, Lemmy, 9GAG #wholesome, Imgur #wholesome |
| 😺 Cats & dogs | A random cat or dog photo after every six posts, named and captioned | The Cat API, Dog CEO (a new cat and dog every run; the latest 60 are kept) |
| 🥹 Cute | Cute animal photos and videos | r/aww, r/Eyebleach, r/rarepuppers, r/IllegallySmolCats, Lemmy, 9GAG #cute / #aww / #dogs / #cats, Imgur #aww / #cats / #dogs |
| 😊 Wholesome | People (and animals) being lovely | r/MadeMeSmile, r/HumansBeingBros, r/AnimalsBeingBros, Lemmy |
| 🔭 Science, 🌿 Environment, 💚 Health, 🐾 Animals, 🤝 Kindness, 💡 Innovation, 🤖 AI for good, 🎨 Culture | Good-news stories | Good-news outlets, world news filtered for positivity, Google News, r/UpliftingNews, r/goodnews |

Memes and animal photos link back to the original post, as credit. Saved posts stay
on your device.

**Top stories** (the same logic in `web/app.js` and `Ranking` in the app) ranks by
how uplifting a post is, minus 1 point per 6 hours of age. For memes and animal
photos, popularity on the source site also helps pick the best ones; it's never
shown. News and fun posts are ranked separately, then interleaved: three news
stories, then one meme or animal post. **Latest** is simply newest first.

## Sources and filtering (`scraper/`)

* **Dedicated good-news outlets** are included as-is: Good News Network,
  Positive News, Reasons to be Cheerful, The Optimist Daily, YES!, The Guardian's
  *The Upside*, Good Good Good, Nice News and Squirrel News.
* **World and science news** only gets in if it passes the positivity filter.
  Sources: BBC (world, science, England, Scotland, Wales, Northern Ireland, Newsround),
  The Guardian (UK, Scotland, Wales, environment, science), Sky News, RTÉ, TheJournal.ie,
  BreakingNews.ie, the Irish Examiner, The Independent's *Happy News*, NPR, DW,
  France 24, CBC, ABC Australia, RNZ, ScienceDaily,
  Phys.org, ScienceAlert, New Atlas, NASA, Smithsonian, Mongabay, and Google News
  searches ("conservation success", "charity raises", "volunteers"…) in its US, UK,
  Canadian, Australian, Irish and New Zealand editions.
* **Progress on hard problems.** Good news about really bad things gets special treatment:
  new treatments for cancer, Alzheimer's, dementia, Parkinson's and other diseases, and
  progress on climate change and nature (emissions falling, clean-energy records, species
  recovering). Their headlines necessarily mention death, disease or warming, so a headline
  that pairs a hard problem with real progress is judged without those words, unless the
  problem is getting worse ("cancer deaths rise despite…"). These stories rank highly.
  Sources include Cancer Research UK, Alzheimer's Research UK, ScienceDaily Health, Medical
  Xpress, Carbon Brief, Canary Media and targeted Google News searches.
* **🤖 AI for good.** Stories about AI helping people or the planet: spotting disease,
  discovering drugs and materials, protecting wildlife. AI industry news (funding, products,
  jobs, chips, lawsuits, risks and controversies) is left out. Sources include MIT News,
  Google DeepMind and targeted searches.
* **UK & Ireland first.** Most readers are in the UK and Ireland. Stories from there get
  their own 📍 UK & Ireland tag (from the places they mention, a UK or Irish local feed,
  or a `.uk`/`.ie` website) and a small boost in **Top stories**, worth six hours of
  freshness. Google News is also searched in its UK and Irish editions.
* **Western focus.** Most readers are in the UK, Europe, North America and Oceania,
  so at most about 10% of news stories come from Asia, Africa, Latin America or the
  Middle East, and they rank below Western stories in **Top stories**. A story's
  region comes from the outlet (e.g. an Indian newspaper, or a `.in` website) or the
  places it mentions. Worldwide stories, memes and cute animals aren't limited.
* **Positivity filter.** With `ANTHROPIC_API_KEY` set, Claude reads each new
  headline, decides whether it's really good news, scores how uplifting it is,
  tags the topic and region, and writes a short summary. Without a key, a strict
  keyword filter is used instead.
* **Memes and cute animals** come from Reddit, Lemmy, 9GAG and Imgur. NSFW and spoiler posts
  are dropped, and so are posts below a per-source upvote threshold. Cute
  animals stay for 3 days, news for 7, and memes for up to 60 (they don't go stale).
* **Left out on purpose:** posts not written in English, celebrity and showbiz news,
  anything about royalty or monarchies, politics, money and markets (pay rises, tax,
  share prices, lawsuits and settlements), all sport, and clickbait: teaser and hype
  headlines ("will melt your heart", "you won't believe"), listicles, questions,
  advice pieces, appeals and notices, and tabloid or viral-video sites. These rules apply to every
  source, including posts already in the feed (`unwanted()` in `scraper/goodnews/scrape.py`,
  word lists in `scraper/goodnews/keywords.py`).
* **Read it on Sunnyside first.** Each news story keeps its opening paragraphs (up
  to 200 words), taken from the outlet's feed when it publishes full text, otherwise
  from the article page (`scraper/goodnews/articles.py`). The website's post page and
  the app's story screen show them, credited, with a **Continue reading** button to the
  original. Pages marked `nosnippet` are respected, and Google News links are resolved
  to the publisher's own page. With `ANTHROPIC_API_KEY` set, Claude also writes a short
  "In short" summary from the article.
* **9GAG and Imgur are vetted by their tags.** Uploaders put popular tags on unrelated
  videos, so a post needs an animal tag (cute animals) or a wholesome tag (memes), and none
  from a blocklist (politics, people and bodies, celebrities, comedy, "wtf", news…). Posts
  without tags, and uploaders repeating one set of tags across several posts, are left
  out. Tags are saved with each post and re-checked every run (`vet_social()` in
  `scraper/goodnews/social.py`).
* **Clips** (9GAG and Imgur animations, Reddit videos) are stored as direct MP4 links.
  The website plays them inline, muted and looping, while they're on screen; the app plays
  them on the post page, with a button to turn the sound on.
* 9GAG has no official API; the scraper reads the same public JSON its tag pages use,
  so it may break or be blocked at any time (it's then skipped).
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
| `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET` | Reliable Reddit access. Reddit often blocks anonymous requests from GitHub's servers. Create a free "script" app at <https://www.reddit.com/prefs/apps>. Without these, Lemmy and 9GAG still supply memes and cute animals. |
| `IMGUR_CLIENT_ID` | Turns on Imgur as a source. Register a free app at <https://api.imgur.com/oauth2/addclient> ("anonymous usage"). Note: Imgur isn't available to visitors in the UK, so its images won't load there. |
| `FEEDBACK_URL` (an Actions **variable**, not a secret) | Where the website's and app's feedback form sends messages. See [Feedback](#feedback). |
| `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | Signs release APKs and the Play Store bundle with your upload key, so updates install over the previous version. See [`android/play/README.md`](android/play/README.md). |

On branches other than `main`, the scrape runs as a dry run. The result is
uploaded as a `site-preview` artifact, so you can check source changes before
merging.

### Feedback

The website (💬 in the top bar, and **Report** on every post) and the app (**Settings →
Send feedback**, and 🚩 on every post) have a feedback form. Readers can send an idea, a
problem or a source to add, or report a post as not good news, clickbait, wrong or broken.

Every post also has a one-tap **☁️ Downer** button for "this isn't good news". It hides
the post for that reader straight away (with Undo), and, once `FEEDBACK_URL` is set,
sends you a report with the post's title and link. Without a form service, downers only
hide the post: a GitHub issue can't be filed quietly in the background.

Until a form service is set up, the form opens a pre-filled GitHub issue (labelled
`feedback`), which needs a GitHub account. To take feedback from anyone:

1. Create a free form at <https://formspree.io> (any service that accepts a JSON `POST`
   works). Copy its URL, e.g. `https://formspree.io/f/abcdwxyz`.
2. In **Settings → Secrets and variables → Actions → Variables**, add `FEEDBACK_URL`
   with that URL.

The next website publish picks it up. The app needs a rebuild (any push to `main`).
Messages then arrive by email. They include the reported post, and a reply address if
the reader gave one. A hidden honeypot field filters out most spam bots.

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

### 3. Publish on Google Play

See [`android/play/README.md`](android/play/README.md) for the step-by-step guide. It covers
the store listing, graphics, privacy policy, Play Console answers, the upload key, and the
**Publish to Google Play** workflow, which builds, signs and uploads the app bundle.

### Running things locally

```bash
cd scraper
pip install -r requirements-dev.txt
python -m pytest -q
python -m goodnews.scrape --out ../site            # --no-claude, --no-social, --no-pets to skip parts
cp -r ../web/. ../site/ && python -m http.server -d ../site 8000   # website at http://localhost:8000
```

## Ideas for what's next

* Push notifications through FCM for breaking good news
* Personalised ranking based on what each person reads and saves
* Play Store release: a signing key, a privacy policy and store listing art
