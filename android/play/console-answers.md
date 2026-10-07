# Play Console answers

What to enter in the Play Console's **App content** and **Store settings** pages for Sunnyside.
These match what the app does today: AdMob ads, optional accounts on Firebase, and an ad-free Google Play
subscription (see [MONETISATION.md](MONETISATION.md)). If the app changes (analytics, a crash reporter,
new data), update both these answers and the [privacy policy](../../web/privacy.html).

## Privacy policy

`https://rockstoneballs.github.io/test/privacy.html`

## App access

**All or some functionality is restricted**, then add one instruction:

> Signing in is optional and only used to keep an ad-free subscription across devices. Reviewers can sign
> in with any Google account, or with any email address using "Email me a link", under Settings → Sign in.
> The ad-free subscription can be bought by a license tester.

## Account deletion

- Can users create an account? **Yes**.
- In-app deletion: Settings → Delete account.
- Web link: `https://rockstoneballs.github.io/test/delete-account.html`

## Ads

**Yes, my app contains ads** (Google AdMob, between posts on the home feed; none for subscribers).

## Content rating (IARC questionnaire)

- Category: **News, education or reference**, or the closest match the questionnaire offers.
  The app shows real news headlines and excerpts.
- Violence, sexuality, drugs, gambling, profanity: **No**. Sunnyside's filters remove violent,
  sexual, profane and grim stories. Answer honestly if anything slips through often enough to change that.
- User-generated content / users can interact or share content with each other: **No**. Readers can't
  post or message each other; posts come from publishers and public meme/animal pages, picked by our filters.
- Shares location: **No**. Digital purchases: **Yes** (the ad-free subscription). Ads: **Yes**.

Expect a rating around PEGI 3 / Everyone. Memes from Reddit, 9GAG and Imgur are filtered, but if Google
reviewers find edgy content they may rate it higher.

## Target audience and content

- Target age groups: **18 and over** (you may add 13–17). **Do not** select under-13 groups. That
  would bring in the Families policy and its stricter rules for ads, data and content. With ads,
  those rules would also limit AdMob to "families-certified" ad networks.
- Appeals to children: **No**.

## News apps declaration

Answer **Yes, it's a news app**. Google then asks for:

- **Publisher/contact details:** a contact email (Google shows this publicly on the listing), plus the
  website `https://rockstoneballs.github.io/test/`.
- **Sources:** Sunnyside is an aggregator. Every post is credited to and links to its original publisher,
  and the app's Settings screen lists the sources.

Google may also ask you to verify the developer account to a higher standard for news apps. Follow
whatever it asks for.

## Data safety

Answer from the privacy policy. AdMob's collection is documented by Google:
<https://developers.google.com/admob/android/privacy/play-data-disclosure>. Check it for changes before you submit.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all user data collected by your app encrypted in transit? | **Yes** (HTTPS only) |
| Do you provide a way for users to request that their data is deleted? | **Yes**. In the app, and at the account deletion URL above. |

Data types to declare:

| Data type | Collected / shared | Required? | Why |
|---|---|---|---|
| Personal info → **Name** | Collected | Optional | Account management |
| Personal info → **Email address** | Collected | Optional | Account management; Developer communications (feedback replies) |
| Personal info → **User IDs** | Collected | Optional | Account management |
| Financial info → **Purchase history** | Collected | Optional | App functionality (the ad-free subscription) |
| Location → **Approximate location** | Collected **and shared** (AdMob, from the IP address) | Required | Advertising or marketing; Analytics; Fraud prevention, security and compliance |
| Device or other IDs → **Device or other IDs** | Collected **and shared** (the advertising ID, with AdMob) | Required | Advertising or marketing; Analytics; Fraud prevention, security and compliance |
| App activity → **App interactions** | Collected **and shared** (ad views and taps, with AdMob) | Required | Advertising or marketing; Analytics |
| App activity → **Other user-generated content** | Collected | Optional | App functionality; Developer communications (feedback and reports) |
| App info and performance → **Crash logs**, **Diagnostics** | Collected **and shared** (AdMob SDK) | Required | Analytics; Fraud prevention, security and compliance |
| App info and performance → **Other app performance data** | Collected | Optional | Analytics (app and Android version sent with feedback) |

None of it is processed ephemerally. "Required" here means readers on the free, ad-supported version can't turn it
off (they can go ad-free instead). Consent choices in the UK and EEA limit how Google may use it.

Not collected: precise location, contacts, health, messages, photos, files, calendar, web browsing,
and installed apps. Saved posts, read history and settings never leave the device, so they're **not**
"collected" in Google's sense. Google Play handles payment details, not Sunnyside.

> Formspree (feedback) and Firebase (accounts) process data on Sunnyside's behalf. Google doesn't count service
> providers as "sharing". AdMob is different: Google uses ad data for its own purposes too, so it counts as shared.

## Government apps, financial features, health

**No** to all of them.

## Store settings

- App category: **News & Magazines**
- Tags: Good news, Positive news, Animals (whatever Play offers closest)
- Contact email: required and public. Use an address you're happy to publish (not your personal one if you'd rather not).
- Website: `https://rockstoneballs.github.io/test/`
