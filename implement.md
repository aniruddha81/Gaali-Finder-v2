# Task: Replace local-only audio storage with authenticated cloud upload (Appwrite + Google OAuth)

## Stack
- Native Android, Kotlin, Jetpack Compose.
- Backend: Appwrite (Auth, Storage, Databases, Functions).
- Use the official Appwrite Android SDK (`io.appwrite:sdk-for-android`).

## Context
Currently, when a user adds an audio file in the app, it is saved only to
the device's local storage — never uploaded to a server, and no other user
can see or hear it. This entire local-save flow must be REMOVED and
REPLACED with the system described below. This is a replacement, not an
additional option — do not leave the old local-save code path active.

## Goal
1. Add "Sign in with Google" authentication via Appwrite OAuth2.
2. Replace local audio saving with direct upload to Appwrite Storage.
3. All uploaded audio becomes visible/playable to all users of the app.
4. Enforce a **200 KB per-file limit** and a **10 MB total-storage limit
   for free-tier users** (no cap on file count, just total size).
5. Build a **premium user flag** now (schema + gating logic), toggleable
   manually today, wired to a real payment system later, which raises or
   removes these limits for premium users.
6. Add a like/dislike reaction system: mutually exclusive, toggleable,
   authenticated-only.

---

## 1. Authentication (Google OAuth via Appwrite)

- Use Appwrite's native OAuth2 provider for Google:
  `account.createOAuth2Session(activity, provider = OAuthProvider.GOOGLE, ...)`
  (or `createOAuth2Token` for the token flow) — do NOT integrate Firebase
  Auth separately. Appwrite handles the Google OAuth flow directly.
- After a successful session, call `account.get()` to retrieve the
  signed-in user's name/email and expose it via a shared
  `AuthViewModel` / `AuthRepository` (`StateFlow<User?>`) consumable by
  Compose screens.
- Two UI/permission states throughout the app:
  - **Authenticated**: can upload audio (subject to limits below), can
    like/dislike, can listen.
  - **Guest (unauthenticated)**: can only listen. Cannot upload. Cannot
    react.
- **Plus (upload) FAB/icon**:
  - Authenticated → opens the upload flow.
  - Guest → do NOT open the upload flow. Launch Google sign-in instead.
    After successful login, either auto-open the upload flow or require
    a second tap — pick whichever fits the existing navigation flow, but
    be consistent.
- **Like/dislike buttons for guests**: tapping either triggers the same
  Google sign-in prompt rather than silently failing.

---

## 2. Audio Upload — Replace Local Storage with Appwrite Storage

- **Remove** all code that currently persists picked/recorded audio to
  local device storage as the app's audio feed source.
- **New upload flow** (plus icon, authenticated users only):
  1. Get the selected/recorded audio file, compute its size in bytes.
  2. **Per-file check**: if `file.size > 200 KB (204,800 bytes)` → reject
     immediately, before checking total quota. Show: "This file is
     {X} KB — files must be 200 KB or smaller." Do not proceed to the
     total-quota check for an oversized file.
  3. **Total-quota check** (see Section 3): if the user is free-tier and
     `currentTotal + file.size > 10 MB` → block, show upgrade prompt.
     If the user is premium, apply the premium limit instead (Section 4).
  4. If both checks pass, upload to a dedicated Appwrite Storage bucket
     (e.g. `audio_files`) via `storage.createFile(...)`.
     - Also set the bucket's own `maximumFileSize` to 200 KB in Appwrite
       Storage bucket settings as a hard server-side backstop — don't
       rely on client-side size checking alone for the per-file limit
       either.
  5. On success, create a document in `audio_metadata` with:
     - `fileId` (String) — Appwrite Storage file ID
     - `uploaderId` (String) — Appwrite user ID
     - `uploaderName` (String) — display name at upload time
     - `fileSizeBytes` (Integer)
     - `createdAt` (Datetime)
     - `likeCount` (Integer, default 0)
     - `dislikeCount` (Integer, default 0)
- **Playback for all users**: feed (LazyColumn of cards) populated by
  querying `audio_metadata` (sorted by `createdAt` desc, or popularity —
  Section 5), streaming from Appwrite Storage via
  `storage.getFileView(bucketId, fileId)` into ExoPlayer/MediaPlayer —
  never from a local file path.
- **Audio card Composable**: must display `uploaderName`.

---

## 3. Storage Quota — Free Tier (200 KB/file, 10 MB total)

- Per-file cap: **200 KB (204,800 bytes)**, hard limit, applies to every
  user regardless of tier (premium tier changes the *total* cap in
  Section 4, not the per-file cap, unless you decide otherwise — see
  assumption below).
  `[ASSUMPTION: the per-file 200 KB cap applies to premium users too,
  since only the total limit was described as tier-dependent. If premium
  should also get a larger per-file cap, tell the agent the new number;
  otherwise this stays fixed at 200 KB for everyone.]`
- Total cap for free-tier users: **10 MB (10,485,760 bytes)**, summed
  across all of that user's `audio_metadata` documents' `fileSizeBytes`.
- **Client-side check**, before every upload attempt:
  1. Query all `audio_metadata` documents where `uploaderId` == current
     user's ID (via Appwrite Query filter).
  2. Sum `fileSizeBytes`.
  3. Look up the user's tier (Section 4). If free and
     `sum + file.size > 10 MB` → block upload, show: "You've reached your
     10 MB storage limit. Upgrade to premium for more space."
- **Server-side enforcement is mandatory**: an Appwrite Function
  (triggered on `audio_metadata` document creation, or invoked
  synchronously as a pre-upload check with a server-side API key) must
  re-validate both the per-file size and the total-sum-by-tier rule
  independently of the client. If it fails validation, delete the
  Storage file that was just uploaded and reject the metadata write.
  Do not ship client-only enforcement for either limit.

---

## 4. Premium Tier (schema + manual toggle now, payment integration later)

- Add an `isPremium` (Boolean, default `false`) field to the user's
  profile. Since Appwrite Auth's built-in `Users` don't hold custom app
  fields directly, store this in a separate `user_profiles` (or
  `user_plans`) Database collection keyed by `userId`, e.g.:
  - `userId` (String, unique)
  - `isPremium` (Boolean, default `false`)
  - `premiumStorageLimitBytes` (Integer, default e.g. `104_857_600` for
    100 MB — pick a placeholder number now; easy to change later)
  - `updatedAt` (Datetime)
- **Toggle mechanism for now**: since payment isn't integrated yet,
  `isPremium` should be toggleable directly in the Appwrite Console (by
  editing the document) or via a simple admin-only Function/script — do
  NOT build any in-app UI that lets a user set their own `isPremium` to
  `true` for free, since that would be a self-service bypass of the
  quota you're building. `[ASSUMPTION: no in-app upgrade purchase flow
  exists yet, so the toggle is admin-side only for now. When you
  integrate real payments later, the payment webhook/Function should be
  the only thing allowed to flip this flag.]`
- **Quota logic**: wherever the total-storage check happens (client and
  server Function), look up `isPremium` for the current user:
  - `isPremium == false` → total cap = 10 MB.
  - `isPremium == true` → total cap = `premiumStorageLimitBytes` from
    their `user_profiles` document (defaulting to the 100 MB placeholder
    above, or whatever value an admin has set for them).
- **"Upgrade" prompt UI**: when a free user hits their cap, show a
  dialog/snackbar with an "Upgrade" button. Since payment isn't wired up
  yet, this button should navigate to a placeholder screen (e.g.
  "Premium plans — coming soon") rather than a real checkout. Structure
  this screen/nav route so a real payment SDK can be dropped in later
  without restructuring the quota-check code.
- Appwrite permissions for `user_profiles`: read = the user's own
  document only (or admin), write = admin/Function-only (never
  user-writable), to keep the premium flag tamper-proof from the client.

---

## 5. Like/Dislike Reaction System (mutually exclusive, toggleable)

- Only authenticated users can react; guests trigger sign-in (Section 1).
- **Vote rules — exact behavior**:
  - A user's reaction state on a given audio file is exactly one of:
    `LIKE`, `DISLIKE`, `NONE` (no document = `NONE`).
  - Never both like and dislike active at once for the same user/file.
  - Tap like from `NONE` → `LIKE`. Tap like again from `LIKE` → `NONE`.
  - Tap like from `DISLIKE` → switches directly to `LIKE` in one action
    (removes dislike, adds like). Symmetric for dislike.
  - Users can change/remove their reaction any number of times.
- **Data model**: `audio_reactions` collection:
  - `audioId` (String, references `audio_metadata` document ID)
  - `userId` (String)
  - `type` (String enum: `like` | `dislike`) — document is deleted
    entirely when state returns to `NONE` rather than storing a "none"
    value.
  - Enforce a unique (`audioId`, `userId`) pair (unique index, or
    query-before-write guarded by the Function below).
- **Count consistency**: an Appwrite Function triggered on
  create/update/delete of `audio_reactions` documents recalculates and
  writes `likeCount`/`dislikeCount` on the parent `audio_metadata`
  document. Don't trust naive client-side increment/decrement calls —
  avoids race conditions under concurrent reactions.
- **Popularity metric**: use `likeCount - dislikeCount` (net score) to
  drive a "Most popular" sort/filter option in the feed alongside
  "Newest".

---

## 6. Appwrite Permissions Checklist

- `audio_files` bucket:
  - Read: any.
  - Create: authenticated users only.
  - Update/Delete: file owner or admin only.
  - `maximumFileSize`: set to 204,800 bytes (200 KB) at the bucket level.
- `audio_metadata` collection:
  - Read: any.
  - Create: authenticated users only.
  - Update: `likeCount`/`dislikeCount` mutated only by the server-side
    Function (Function-scoped API key), not directly client-writable.
  - Delete: owner or admin only.
- `audio_reactions` collection:
  - Read: authenticated-only (guests can't react anyway; default to
    authenticated-only unless you want public visibility of who liked
    what).
  - Create/Update/Delete: authenticated users only, restricted to
    documents where `userId` == their own ID.
- `user_profiles` collection:
  - Read: the user's own document, or admin.
  - Create/Update/Delete: admin/Function-only — never directly
    user-writable, so `isPremium` can't be self-granted.

---

## 7. Explicit Non-Goals / Out of Scope

- No real payment/subscription/billing implementation yet — only the
  `isPremium` flag, schema, and quota-branching logic, plus a placeholder
  "coming soon" upgrade screen. Actual payment SDK integration is a
  future task.
- No other OAuth providers (Apple, email/password, etc.) unless asked.
- Do not change unrelated existing features (other screens, navigation,
  theming) beyond what's needed to wire in the new data source, limits,
  and auth gating.

---

## Deliverables

1. Google OAuth login/logout via Appwrite, exposed through a
   ViewModel/StateFlow consumable by Compose.
2. Upload flow fully migrated from local storage to Appwrite Storage +
   `audio_metadata`; old local-save code removed entirely.
3. Per-file 200 KB limit enforced client-side AND via Appwrite bucket
   `maximumFileSize`.
4. Total-storage limit (10 MB free / configurable premium) enforced
   client-side AND via a server-side Appwrite Function.
5. `user_profiles` collection with `isPremium` + `premiumStorageLimitBytes`,
   admin-toggleable only, with quota logic branching on it.
6. Like/dislike system: mutually-exclusive, freely-toggleable per
   user/file, count-sync via server-side Function.
7. UI: uploader name on cards, guest-vs-authenticated gating on plus icon
   and reaction buttons, per-file-too-large message, total-quota-exceeded
   upgrade prompt (stub screen).

## Implementation Order
1. Appwrite Google OAuth (login/logout, session state in ViewModel).
2. Migrate upload flow: local storage → Appwrite Storage + `audio_metadata`.
3. Wire feed playback to Appwrite Storage instead of local files; add
   uploader name to cards.
4. Add per-file 200 KB check (client + bucket setting).
5. Add `user_profiles` collection + `isPremium` flag (admin-toggle only).
6. Add total-quota check branching on `isPremium` (client-side, then the
   server-side Function).
7. Add like/dislike reactions (`audio_reactions` + Function for count
   sync) with mutually-exclusive toggle logic.

Flag any `[ASSUMPTION: ...]` item above that conflicts with the existing
codebase's architecture (DI setup, existing player implementation,
existing navigation graph) before proceeding, rather than silently
working around it.