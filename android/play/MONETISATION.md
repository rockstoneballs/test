# Accounts, ads and the ad-free subscription

How Sunnyside makes money, and how to switch each part on. The code is all in place. What's left are
accounts and settings that only the owner can create. They're marked **👤 you**.

| Part | What it does | Runs on |
|---|---|---|
| **Ads** | A banner between posts on the home feed: after the 4th post, then after every 8th. Rated PG at most, with a "Go ad-free" link. | Google AdMob |
| **Ad-free subscription** | `ad_free`, about £1.99 a month. Removes every ad. | Google Play Billing |
| **Accounts** | Optional sign-in with Google, Apple or an email link. Keeps ad-free on all your devices. Readers can delete their account in Settings. | Firebase Authentication |
| **Server** | Checks each purchase with Google Play, marks the account ad-free, and follows renewals, cancellations and refunds. | Firebase Cloud Functions + Firestore ([`firebase/`](../../firebase)) |

Until each part is set up, the app still works:
- **No AdMob ids:** release builds (Play, and the APK on the website) show no ads and don't offer
  ad-free. Debug builds show Google's *test* ads, which earn nothing.
- **No Firebase settings:** sign-in is hidden. The subscription still works on that phone alone.
- **Not installed from Google Play:** the subscription can't be bought. This is true of the APK on the website, for example.

The website stays ad-free for now. Ads on the web need a custom domain (Google AdSense won't accept a
`github.io` address) and card payments need Stripe. Both can be added later.

**What you earn:** at £1.99 a month, a UK subscriber brings in about **£1.41 a month**. That's after 20%
VAT (£1.66) and Google's 15% subscription fee. Ad income per reader is much lower, typically pennies a month.

---

## 1. Firebase (accounts and the server) 👤 you

1. Go to <https://console.firebase.google.com> and **Add project**: `sunnyside` or similar. Analytics isn't needed.
2. Upgrade to the **Blaze (pay as you go)** plan. Cloud Functions need it. At Sunnyside's size it should
   stay within the free allowance, but set a **budget alert** (for example £5) in Google Cloud Billing anyway.
3. **Firestore Database → Create database**: production mode, location **europe-west2 (London)**.
4. **Authentication → Get started → Sign-in method**, and turn on:
   - **Google.** Note the *Web client ID* it shows under "Web SDK configuration".
   - **Email/Password**, with **Email link (passwordless sign-in)** turned on.
   - **Apple.** This needs an Apple Developer account (US$99 a year): create a Services ID and a key, and
     set the return URL Firebase shows you. If you'd rather not, leave Apple off and the button simply fails
     with a message. Ask me to hide it.
   - Under **Settings → Authorized domains**, add `rockstoneballs.github.io`. Email links go through the
     website's [sign-in page](../../web/signin.html).
5. **Project settings → Your apps → Add app → Android**, package `app.sunnyside.news`. Add these SHA-1 and SHA-256
   certificate fingerprints:
   - your **upload key**: `keytool -list -v -keystore sunnyside-upload.jks -alias upload`
   - the **app signing key**, from Play Console → Test and release → App integrity → App signing

   Google sign-in only works in builds signed with a key whose fingerprint is listed here.
   Download `google-services.json`; you only need three values from it (step 7). Don't commit it.
6. **Deploy access for GitHub.** In [Google Cloud IAM → Service accounts](https://console.cloud.google.com/iam-admin/serviceaccounts),
   create `github-deploy` with these roles:
   - **Firebase Admin**
   - **Cloud Functions Admin**
   - **Service Account User**
   - **Cloud Build Editor**
   - **Artifact Registry Administrator**
   - **Pub/Sub Admin**
   - **Eventarc Admin**

   Then **Keys → Add key → JSON**. If a deploy fails because a permission is missing, the error names the role to add.
7. In GitHub, go to **Settings → Secrets and variables → Actions** and add:

   | Name | Kind | Value |
   |---|---|---|
   | `FIREBASE_SERVICE_ACCOUNT` | secret | the JSON key file's contents |
   | `FIREBASE_PROJECT_ID` | variable | `project_id` in google-services.json |
   | `FIREBASE_ANDROID_APP_ID` | variable | `mobilesdk_app_id` (looks like `1:1234:android:abcd`) |
   | `FIREBASE_API_KEY` | variable | `current_key` under `api_key` |
   | `FIREBASE_WEB_CLIENT_ID` | variable | the Web client ID from step 4 (ends `.apps.googleusercontent.com`) |

   The last three are identifiers, not passwords: Google designs them to ship inside the app.
8. **Actions → Firebase → Run workflow** on `main`. It tests the server code, then deploys the functions and the
   database rules. It also runs by itself whenever `firebase/` changes on main.

## 2. The subscription in Play Console 👤 you

You need the app created in Play Console first (see [README.md](README.md)), and a build uploaded to a testing
track. Play only sells subscriptions to apps installed from Play.

1. **Monetise with Play → Payments profile**: set one up. This is where Google pays you.
2. **Monetise with Play → Products → Subscriptions → Create subscription**:
   - Product ID **`ad_free`**. It must be exactly this; the app and server look for it.
   - Name: *Sunnyside ad-free*.
   - Add a **base plan**: ID `monthly`, auto-renewing, billing period 1 month, price **£1.99**. Play suggests
     prices for other countries; set the US to $1.99 if you like. **Activate** it.
3. **Let the server read purchases.**
   - In Google Cloud, enable the **Google Play Android Developer API** for the Firebase project.
   - In Play Console go to **Users and permissions → Invite new users**. Invite the functions' service account,
     `PROJECT_NUMBER-compute@developer.gserviceaccount.com`. The project number is in Firebase project settings.
   - On the Sunnyside app, give it **View financial data** and **Manage orders and subscriptions**.
4. **Real-time notifications.** These keep accounts right when people renew, cancel or get refunds.
   - In Google Cloud **Pub/Sub**, open the topic **`play-billing`**. The first Firebase deploy creates it.
   - Add the principal `google-play-developer-notifications@system.gserviceaccount.com` with the
     **Pub/Sub Publisher** role.
   - In Play Console go to **Monetise with Play → Monetisation setup → Real-time developer notifications**. Set the
     topic to `projects/YOUR_PROJECT_ID/topics/play-billing`, choose *Subscriptions, voided purchases and all one-time
     products*, and **Send test notification**. The Firebase function log shows "Play test notification received".
5. **Settings → License testing**: add your own Google account. Your test purchases are then free, and
   subscriptions renew every few minutes, so you can watch the whole cycle.

## 3. AdMob (the ads) 👤 you

1. Sign up at <https://admob.google.com> with the same Google account, and add a payments profile.
2. **Apps → Add app → Android**. Choose "No" to "published on Play" if it isn't live yet; link it to the
   store listing later.
3. **Ad units → Add → Banner**, named *Feed*. The app shows it as an inline adaptive banner.
4. Add two GitHub Actions **variables**:

   | Name | Value |
   |---|---|
   | `ADMOB_APP_ID` | the app ID, `ca-app-pub-…~…` |
   | `ADMOB_FEED_AD_UNIT` | the Feed ad unit ID, `ca-app-pub-…/…` |

5. **Privacy & messaging → European regulations → Create message** for the app, and publish it. Google requires
   this to show ads in the UK and EEA. The app shows it on first launch to readers who see ads, and links to it
   from Settings → Ad privacy choices.
6. **Blocking controls**, to keep ads on-brand for a good-news app:
   - **Content rating:** *G* or *PG*. The app also asks for PG at most.
   - **Sensitive categories:** block Gambling, Dating, Politics, Get rich quick, Alcohol, Weight loss, and any others you'd
     rather not show next to good news.
7. **app-ads.txt.** AdMob asks for this file at the root of the website listed on your Play page. A project site like
   `rockstoneballs.github.io/test/` can't provide that. Either:
   - create a repository called `rockstoneballs.github.io` containing just the `app-ads.txt` file AdMob gives you, or
   - give Sunnyside a custom domain.

   Ads work without it, but fewer advertisers bid.

Ads appear in release builds as soon as both variables are set. Never tap your own real ads: AdMob bans
accounts for it. Use a debug build, which shows test ads, or add your phone as a test device in AdMob.

## 4. Play Console declarations 👤 you

The ads and the subscription change some answers in [`console-answers.md`](console-answers.md). It's already
updated:
- *Contains ads:* **Yes**.
- *In-app purchases:* **Yes**. These show on the store listing.
- **Data safety:** the advertising ID, approximate location, app interactions and diagnostics are collected by AdMob
  and shared with Google for advertising. Name, email address and user ID are collected for accounts. Purchase
  history is collected for the subscription.
- **Account deletion URL:** <https://rockstoneballs.github.io/test/delete-account.html>.

## How it fits together

```
 Reader taps "Go ad-free"
        │  (signed in first, so the subscription follows them)
        ▼
 Google Play purchase sheet ── purchase tagged with a hash of the reader's account id
        │
        ▼
 App ──purchase token──▶ verifyPlaySubscription (Cloud Function)
                              │  asks Google Play: is it real, active, theirs?
                              │  acknowledges it (or Play refunds it after 3 days)
                              ▼
                      Firestore users/{uid}: adFree = true, adFreeUntil
                              ▲
 Google Play ──renewed / cancelled / refunded──▶ playBillingNotifications (Pub/Sub)

 App hides ads when Play's record on the phone OR users/{uid} says ad-free.
```

Firestore rules only let readers *read* their own record. Only the server can write it, so nobody can make
themselves ad-free by editing it.
