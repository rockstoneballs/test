# Publishing Sunnyside on Google Play

Everything the store needs that can live in the repo is here. A few steps can only be done by the
account owner in the Play Console. They're marked **👤 you**.

| What | Where |
|---|---|
| Store listing text | [`listing/en-GB/`](listing/en-GB) (title, short and full description) |
| App icon (512×512) and feature graphic (1024×500) | [`graphics/`](graphics) |
| Phone screenshots | [`screenshots/`](screenshots), taken by the *Play Store screenshots* workflow |
| "What's new" text for each release | [`whatsnew/whatsnew-en-GB`](whatsnew/whatsnew-en-GB) (max 500 characters) |
| Answers for the App content forms | [`console-answers.md`](console-answers.md) |
| Privacy policy | <https://rockstoneballs.github.io/test/privacy.html> ([source](../../web/privacy.html)) |
| Build and upload | [`.github/workflows/play.yml`](../../.github/workflows/play.yml) |

## 1. Create a developer account 👤 you

1. Go to <https://play.google.com/console/signup> and pick **Yourself** (a personal account) or
   **An organisation**. An organisation account needs a D-U-N-S number, which is free but takes days to weeks.
2. Pay the one-off **US$25** fee, then verify your identity and your contact details. You'll also need
   to confirm you have an Android phone, using the Play Console app.
3. **Personal accounts only:** before an app can go to production you must run a **closed test with at
   least 12 testers who stay opted in for 14 days in a row**. Line up 12+ friends with Android phones
   now (their Google account emails). Step 5 covers this.

## 2. Create the upload key 👤 you

Google Play signs the app that people download (*Play App Signing*). You sign uploads with your own
**upload key**. Make it on your own computer (it needs Java's `keytool`). Never commit it or paste it into a chat:

```sh
keytool -genkeypair -v -keystore sunnyside-upload.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 sunnyside-upload.jks > sunnyside-upload.b64   # on macOS: base64 -i sunnyside-upload.jks -o sunnyside-upload.b64
```

Keep `sunnyside-upload.jks` and its passwords somewhere safe, like a password manager. If you lose it, Google
can reset the upload key, but that takes a support request.

Then add four repository secrets in GitHub (**Settings → Secrets and variables → Actions → New
repository secret**):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | contents of `sunnyside-upload.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `upload` |
| `ANDROID_KEY_PASSWORD` | the key password (the same as the keystore's unless you chose another) |

From then on, CI signs the release APK on the website with the same key. Phones that already
installed the old debug-signed APK will need to uninstall it once before updating.

## 3. Build the first bundle

In GitHub go to **Actions → Publish to Google Play → Run workflow**, on `main`, with the defaults. Without a
Play service account it just builds. Download **sunnyside-play-bundle** from the run's summary page and unzip
it to get `app-release.aab`.

## 4. Create the app in the Play Console 👤 you

1. **Create app**. App name *Sunnyside: Only Good News*, default language **English (United Kingdom)**,
   **App**, **Free**. Accept the declarations.
2. **Store presence → Main store listing**: paste the three files from `listing/en-GB/`, then upload the
   icon, the feature graphic and 2–8 phone screenshots. Category: **News & Magazines**.
3. **Policy → App content**: work through each section using [`console-answers.md`](console-answers.md).
   It includes the privacy policy URL, ads (none), content rating, target audience (adults), the news app
   declaration and Data safety.
4. **Test and release → Testing → Internal testing → Create new release**: upload `app-release.aab`.
   This is where the package name **`app.sunnyside.news`** becomes yours for good. If Play says the
   name is taken, tell me and I'll change `applicationId` in `android/app/build.gradle.kts`.
   Accept Play App Signing when asked.

## 5. Closed test, then production 👤 you

1. **Testing → Closed testing**: create a track, add your 12+ testers' emails, and roll out the same bundle.
   Send testers the opt-in link. They have to install the app from Play and stay opted in.
2. After 14 days, use **Dashboard → Apply for production**. Google asks a few questions about the test.
3. Once approved, promote the release to **Production**. The first review usually takes a few days.

## 6. Automatic uploads (optional)

To publish from GitHub instead of uploading by hand each time:

1. In [Google Cloud Console](https://console.cloud.google.com/), create a project (or reuse one) and enable
   the **Google Play Android Developer API**.
2. Under **IAM & Admin → Service accounts**, create a service account, then **Keys → Add key → JSON**.
3. In the Play Console, go to **Users and permissions → Invite new users**. Invite the service account's email
   and give it the Sunnyside app with **Release apps to testing tracks** (add **Release to production**
   if you want).
4. Add the JSON file's contents as the repository secret `PLAY_SERVICE_ACCOUNT_JSON`.

After that, **Actions → Publish to Google Play → Run workflow** builds, signs and uploads to the track you choose.
It uses the release notes in `whatsnew/whatsnew-en-GB`, so edit that file first. Use status **draft** to
review the release in the Console before it goes out. Every build gets a higher version code
automatically (minutes since 1970).

## Before you submit: things that can get the app rejected

- **Copyright in excerpts and pictures.** Sunnyside shows up to 200 words of each story and the
  publisher's image, always credited and linked. It also respects pages that opt out of snippets.
  Reviewers or publishers can still object. If Google cites intellectual property, the safe fix is
  shorter excerpts (`EXCERPT_WORDS` in `scraper/goodnews/articles.py`).
- **Memes and videos from Reddit, 9GAG and Imgur** are filtered, but not perfectly. A single offensive meme
  seen by a reviewer can affect the content rating. The report button in the app helps you catch them.
- **The name.** Check that no other Play app or trademark is already called "Sunnyside" in a way that
  could confuse people.
- **Keep the target API current.** Google raises the minimum every August. The app targets API 36 now.
