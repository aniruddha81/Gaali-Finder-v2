# Appwrite + Google OAuth setup

Everything the app needs on the server side, in the order it has to be done. The Android code
is already written and building; nothing here requires touching Kotlin.

Work through sections 1–8 in order. Section 9 is how you grant premium.

---

## 0. What you already have

`local.properties` already defines `APPWRITE_PROJECT_ID` and `APPWRITE_BUCKET_ID`, so the
project and bucket exist. You will be **reusing the project**, **reconfiguring the bucket**, and
**adding a database plus three collections and two functions**.

---

## 1. Register the Android platform in Appwrite

**Before OAuth will work at all**: Appwrite validates the package name of whatever app is
calling it, and rejects requests from apps it doesn't recognise as a platform yet.

**Overview → your project → Platforms → Add platform → Android app.**

- Name: anything recognisable, e.g. `Gaali Finder`.
- Package name: `com.aniruddha81.gaalifinderv2` (from `applicationId` in `app/build.gradle.kts`).

If you already added this platform when you first wired up the shared catalogue, skip this —
but it's worth confirming the package name matches exactly, since a mismatch here is a common
cause of OAuth silently failing.

---

## 2. Google OAuth2 provider

The app never talks to Google directly — Appwrite runs the whole OAuth dance. So Google needs to
trust *Appwrite's* callback URL, not the app's.

### 2a. Google Cloud Console

1. Go to <https://console.cloud.google.com/> → create (or pick) a project.
2. **APIs & Services → OAuth consent screen**
   - User type: **External**.
   - Fill in app name, support email, developer email.
   - Scopes: the defaults (`email`, `profile`, `openid`) are all that is needed.
   - While the app is in **Testing**, only accounts listed under **Test users** can sign in.
     Add your own Google account there, or hit **Publish app** for anyone to sign in.
3. **APIs & Services → Credentials → Create Credentials → OAuth client ID**
   - Application type: **Web application** ← *not* "Android". This trips people up: the OAuth
     flow happens in a browser tab pointed at Appwrite's server, so from Google's point of view
     the client is a web app.
   - **Authorised redirect URI** — take this value from the Appwrite console in step 2b; it
     looks like:

     ```
     https://fra.cloud.appwrite.io/v1/account/sessions/oauth2/callback/google/<YOUR_PROJECT_ID>
     ```

   - Save, then copy the **Client ID** and **Client Secret**.

### 2b. Appwrite console

1. **Auth → Settings → OAuth2 Providers → Google** → toggle **Enabled**.
2. Paste the **App ID** (Google's Client ID) and **App Secret** (Google's Client Secret).
3. Appwrite shows the exact **Redirect URI** on this same screen — copy it back into Google
   (step 2a) if you have not already. They must match character for character.

### 2c. Android callback (already wired, but know why)

`AndroidManifest.xml` declares `io.appwrite.views.CallbackActivity` with the scheme
`appwrite-callback-<projectId>`, injected from your `APPWRITE_PROJECT_ID` via a manifest
placeholder. The Appwrite AAR does **not** declare this activity itself — without it the browser
has nowhere to hand the session back and sign-in appears to hang on the consent screen.

Nothing to do here as long as `APPWRITE_PROJECT_ID` is set correctly.

---

## 3. Storage bucket

**Storage → your `audio_files` bucket → Settings**:

| Setting | Value | Why |
| --- | --- | --- |
| Maximum file size | **204800** bytes (200 KB) | Hard server-side backstop for the per-file cap |
| Allowed file extensions | `mp3, m4a, ogg, wav, aac, opus, flac` | Optional but sensible |
| Encryption / Antivirus | your preference | Note: antivirus is unavailable above 20 MB anyway |

**Permissions** on the bucket:

| Role | Permission |
| --- | --- |
| `Any` | **Read** |
| `Users` | **Create** |

Do **not** grant blanket Update/Delete. The app attaches per-file permissions at upload time so
only the uploader can modify or delete their own file.

---

## 4. Database

**Databases → Create database.** Name it whatever you like (e.g. `gaali`). Copy its **Database
ID** — this becomes `APPWRITE_DATABASE_ID`.

Create three collections inside it.

### 4a. `audio_metadata`

Every collection you create already has built-in columns — `$id`, `$createdAt`, `$updatedAt` are
shown in the Console's table view; `$permissions` also exists on every document but isn't a
visible column there. **Don't add a `createdAt` attribute of your own.** The app and both
Functions sort and read off the built-in `$createdAt` instead, so there's nothing to declare for
it below — only add the attributes actually listed here.

> **Read this before creating any attribute below.** The Console's "Create column" dialog for a
> **Text** attribute has no Size field any more — it fixes every Text column at a flat maximum
> of 16,383 characters, full stop. That is fine for a column you never index, but every id/type
> column below (`uploaderId`, `audioId`, `userId`, `type` in the other two collections) needs an
> index, and MariaDB caps an index at 767 bytes. At 4 bytes per character that is **191
> characters** — so a 16,383-character column cannot be indexed at all: you get *"Index length
> is longer than the maximum: 767"* the moment you try.
>
> The fix is to create those specific attributes with the **CLI or API** instead of the Console
> dialog, since `size` is still a real parameter there — the Console UI just stopped exposing it.
> Install the CLI once (`npm install -g appwrite-cli`, then `appwrite login` and `appwrite init`
> pointed at this project), then run, substituting your database id:
>
> ```bash
> appwrite databases create-string-attribute \
>   --database-id <DATABASE_ID> --collection-id audio_metadata \
>   --key uploaderId --size 64 --required true
> ```
>
> Everything that is **never indexed** (`fileId`, `fileName`, `uploaderName` below, and the
> Integer/Boolean columns everywhere) is unaffected by this and can be created normally through
> the Console — the flat 16,383-character size only matters for the columns you put in an index.

Attributes:

| Key | Type | Size | Required | Default | Create via |
| --- | --- | --- | --- | --- | --- |
| `fileId` | Text | — (default) | ✅ | — | Console |
| `fileName` | Text | — (default) | ✅ | — | Console |
| `uploaderId` | Text | **64** | ✅ | — | **CLI/API** — this one is indexed |
| `uploaderName` | Text | — (default) | ✅ | — | Console |
| `fileSizeBytes` | Integer | — | ✅ | — | Console |
| `likeCount` | Integer | — | ❌ | `0` | Console |
| `dislikeCount` | Integer | — | ❌ | `0` | Console |

Indexes (these matter for performance and for the quota query):

| Key | Type | Attributes |
| --- | --- | --- |
| `idx_created` | key | `$createdAt` (DESC) |
| `idx_uploader` | key | `uploaderId` (ASC) |

Permissions:

| Role | Permissions |
| --- | --- |
| `Any` | **Read** |
| `Users` | **Create** |

Leave collection-level Update/Delete unchecked. **Enable Document Security** on this collection —
the app writes per-document permissions granting delete to the uploader only, and those are only
honoured when document security is on. Note that with document security enabled, `likeCount` and
`dislikeCount` become writable only by the API key the count-sync Function uses, which is exactly
what you want: clients cannot inflate their own counts.

### 4b. `audio_reactions`

Every attribute here gets indexed (see the size warning under 4a), so create **all three** with
the CLI/API rather than the Console dialog:

```bash
appwrite databases create-string-attribute \
  --database-id <DATABASE_ID> --collection-id audio_reactions \
  --key audioId --size 64 --required true

appwrite databases create-string-attribute \
  --database-id <DATABASE_ID> --collection-id audio_reactions \
  --key userId --size 64 --required true

appwrite databases create-string-attribute \
  --database-id <DATABASE_ID> --collection-id audio_reactions \
  --key type --size 16 --required true
```

Attributes:

| Key | Type | Size | Required |
| --- | --- | --- | --- |
| `audioId` | Text | 64 | ✅ |
| `userId` | Text | 64 | ✅ |
| `type` | Text | 16 | ✅ |

> `type` is `like` or `dislike`. A cleared reaction **deletes the document** rather than storing
> a "none" value, so there is no third case to model.

Indexes: create all three. Verified directly against a live project via the CLI — Appwrite does
**not** block reusing an attribute across separate indexes, so `idx_audio` and `idx_user` create
fine alongside `idx_pair` despite each repeating one of its columns.

| Key | Type | Attributes | Why |
| --- | --- | --- | --- |
| `idx_pair` | **unique** | `audioId` (ASC), `userId` (ASC) | Enforces one reaction per user per clip |
| `idx_audio` | key | `audioId` (ASC) | The count-sync Function queries by clip |
| `idx_user` | key | `userId` (ASC) | `reactionsOf()` loads one user's reactions on every sync |

Permissions:

| Role | Permissions |
| --- | --- |
| `Users` | **Read, Create** |

**Enable Document Security.** The app writes per-document update/delete permissions scoped to the
reacting user, so nobody can delete anyone else's vote.

### 4c. `user_profiles`

No custom `updatedAt` here either — the built-in `$updatedAt` column already tells you when an
admin last touched a document, which is the only kind of write this collection ever gets.

`userId` is indexed (see the size warning under 4a), so create it via CLI/API:

```bash
appwrite databases create-string-attribute \
  --database-id <DATABASE_ID> --collection-id user_profiles \
  --key userId --size 64 --required true
```

`isPremium` and `premiumStorageLimitBytes` are never indexed and can be created normally through
the Console.

Attributes:

| Key | Type | Size | Required | Default |
| --- | --- | --- | --- | --- |
| `userId` | Text | 64 | ✅ | — |
| `isPremium` | Boolean | — | ❌ | `false` |
| `premiumStorageLimitBytes` | Integer | — | ❌ | `104857600` |

Index:

| Key | Type | Attributes |
| --- | --- | --- |
| `idx_user` | **unique** | `userId` (ASC) |

Permissions — **this one is deliberately restrictive**:

| Role | Permissions |
| --- | --- |
| `Users` | **Read** |

**No Create, Update, or Delete for anyone.** Only an API key (the Console, or a Function) can
write here. That is what stops a user from granting themselves premium and bypassing the quota
you just built. The app never attempts to write this collection.

> If you would rather each user only read *their own* profile rather than all of them, enable
> Document Security and set read permission per-document to `user:<id>` when you create it. The
> app works either way — it filters by `userId` regardless.

---

## 5. API key for the Functions

**Overview → Integrations → API Keys → Create API key.**

This step needs the Console — the Appwrite CLI (checked directly against v1.9.6) can only create
**ephemeral** keys, which expire after at most an hour and are not viable for a Function that
needs to keep working indefinitely.

Name it `functions-server`. Check these scopes:

- `databases.read`, `databases.write`
- `collections.read`, `collections.write`
- `documents.read`, `documents.write`
- `files.read`, `files.write`

> **Ignore the "Deprecated" badge on these.** The Console also offers a newer `documentsdb.*`
> family (`documentsdb.read`, `documentsdb.documents.write`, …) that looks like the modern
> replacement, but it authorizes a *different* API surface. Both Functions here use
> `node-appwrite`'s classic `Databases` service (`createDocument`, `updateDocument`,
> `listDocuments`, `deleteDocument`) — verified directly against the live API: a key scoped only
> with `documentsdb.*` gets `401 general_unauthorized_scope: missing scopes (["documents.write"])`
> on exactly that call, while the "deprecated" `documents.write` works. Deprecated here means
> "being phased out in favour of the newer TablesDB API," not "non-functional" — for code written
> against classic Databases, as this app is, the deprecated-labelled scopes are the correct ones.

Copy the secret — it is shown once. This is a **server** key: it must never be put in
`local.properties` or shipped in the app.

---

## 6. Function: `validate-upload` (quota enforcement)

Source: `appwrite/functions/validate-upload/`

This is the mandatory server-side re-validation. The client checks both limits before uploading,
but a client check is only a courtesy — anyone can call the API directly with a session token.

**Functions → Create function**

- Name: `validate-upload`
- Runtime: **Node.js 18+**
- Entrypoint: `src/main.js`
- Build command: `npm install`

**Settings → Events**, add:

```
databases.*.collections.<AUDIO_METADATA_COLLECTION_ID>.documents.*.create
```

Replace `<AUDIO_METADATA_COLLECTION_ID>` with the real id.

**Settings → Environment variables:**

| Key | Value |
| --- | --- |
| `APPWRITE_API_KEY` | the key from section 5 |
| `APPWRITE_DATABASE_ID` | your database id |
| `APPWRITE_AUDIO_METADATA_COLLECTION_ID` | `audio_metadata` |
| `APPWRITE_USER_PROFILES_COLLECTION_ID` | `user_profiles` |
| `APPWRITE_BUCKET_ID` | your bucket id |

(`APPWRITE_FUNCTION_API_ENDPOINT` and `APPWRITE_FUNCTION_PROJECT_ID` are injected by Appwrite.)

**What it does.** On every new `audio_metadata` document it independently re-checks:

1. the per-file 200 KB cap;
2. that the recorded `fileSizeBytes` **matches the actual stored file** — otherwise a client
   could under-report a size and get free storage;
3. the user's tier from `user_profiles` (absent ⇒ free);
4. the total across all their clips against that tier's limit.

If anything fails it deletes both the metadata document and the Storage file, so a rejected
upload never occupies quota. Appwrite events fire *after* the write, so enforcement is
delete-after rather than refuse-before — which is why the client also checks first, to avoid the
round trip in the normal case.

Deploy with the CLI:

```bash
cd appwrite/functions/validate-upload
appwrite functions createDeployment \
  --functionId <FUNCTION_ID> --entrypoint src/main.js \
  --code . --activate true
```

---

## 7. Function: `sync-reaction-counts`

Source: `appwrite/functions/sync-reaction-counts/`

Same creation steps, then **Settings → Events**, add all three:

```
databases.*.collections.<AUDIO_REACTIONS_COLLECTION_ID>.documents.*.create
databases.*.collections.<AUDIO_REACTIONS_COLLECTION_ID>.documents.*.update
databases.*.collections.<AUDIO_REACTIONS_COLLECTION_ID>.documents.*.delete
```

Environment variables:

| Key | Value |
| --- | --- |
| `APPWRITE_API_KEY` | the key from section 5 |
| `APPWRITE_DATABASE_ID` | your database id |
| `APPWRITE_AUDIO_METADATA_COLLECTION_ID` | `audio_metadata` |
| `APPWRITE_AUDIO_REACTIONS_COLLECTION_ID` | `audio_reactions` |

**What it does.** It **recounts** the reaction documents for the affected clip and writes
`likeCount` / `dislikeCount`. It never increments or decrements — two users reacting at the same
moment would both read the same old value and write the same new one, losing a vote. Counting is
idempotent, so a duplicate event is harmless. It also prunes any duplicate `(audioId, userId)`
rows a race might leave behind, newest-wins.

---

## 8. `local.properties`

Add these three lines (keep the two you already have):

```properties
APPWRITE_DATABASE_ID=<your database id>
APPWRITE_AUDIO_METADATA_COLLECTION_ID=audio_metadata
APPWRITE_AUDIO_REACTIONS_COLLECTION_ID=audio_reactions
APPWRITE_USER_PROFILES_COLLECTION_ID=user_profiles
```

The last three default to those literal names if you omit them, so you only *need*
`APPWRITE_DATABASE_ID` — but set them explicitly if you named your collections differently.

`local.properties` is git-ignored, so none of this reaches the repo. Rebuild after editing:
Gradle bakes these into `BuildConfig`.

---

## 9. Granting premium (no payment integration yet)

There is deliberately **no in-app way** to set `isPremium` — that would be a self-service bypass
of the quota. To grant premium today:

1. **Databases → `user_profiles` → Create document.**
2. `userId` = the user's Appwrite user id (find it under **Auth → Users**).
3. `isPremium` = `true`.
4. `premiumStorageLimitBytes` = whatever you want, e.g. `104857600` (100 MB).
5. Save. The app picks it up on next sign-in or app start.

A user with **no** `user_profiles` document is free tier — that is the safe default, and it is
also what the code falls back to if the profile read fails for any reason.

When you wire up real payments later, the payment webhook/Function should become the **only**
thing that flips this flag. The quota code branches on `isPremium` already, so nothing above the
data layer needs to change.

---

## 10. Verifying it works

1. **Guest**: launch without signing in. You should see the catalogue and be able to play
   clips. Tapping the plus button or a like/dislike should launch Google sign-in.
2. **Sign in**: the account icon in the top bar turns to the primary colour; the FAB reads
   "Upload".
3. **Per-file limit**: try uploading a file over 200 KB. Expect *"'name' is N KB — files must be
   200 KB or smaller."* and no upload.
4. **Bucket backstop**: temporarily raise the client limit? Don't — instead confirm the bucket's
   `maximumFileSize` is 204800 in the console. That is the layer that catches a modified client.
5. **Quota**: set a test user's limit low (e.g. `premiumStorageLimitBytes` with `isPremium` true,
   or just upload until 10 MB) and confirm the upgrade dialog appears.
6. **Reactions**: like a clip, then like it again (clears), then dislike (switches directly).
   Counts should settle to the Function's values within a second.
7. **Server enforcement**: this is the one worth testing deliberately. Use the Appwrite Console
   to create an `audio_metadata` document by hand with a `fileSizeBytes` of `999999`. The
   `validate-upload` Function should delete it within a few seconds. Check **Functions →
   validate-upload → Executions** for the log line.

---

## Troubleshooting

**Sign-in opens a browser then returns to a blank screen / nothing happens.**
The callback activity is not matching. Confirm `APPWRITE_PROJECT_ID` in `local.properties` is
exactly right, and rebuild — the manifest scheme is generated from it.

**Sign-in returns "Invalid redirect URI".**
The URI in Google Cloud Console does not match the one Appwrite shows under Auth → Settings →
Google. They must be identical.

**"This app is blocked" / "Access denied" on the Google consent screen.**
Your Google OAuth app is in Testing and the account is not in the Test users list. Add it, or
publish the app.

**Uploads succeed but vanish a moment later.**
`validate-upload` is rejecting them. Check its execution logs — the rejection reason is logged
(`file-too-large`, `size-mismatch`, `quota-exceeded`, …).

**Like counts never change.**
`sync-reaction-counts` is not firing or cannot write. Confirm all three events are registered and
that the API key has `documents.write`.

**Catalogue is empty and pull-to-refresh says the library isn't set up.**
`APPWRITE_DATABASE_ID` is missing from `local.properties`, or the build predates adding it.
