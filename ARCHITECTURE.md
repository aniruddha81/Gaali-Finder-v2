# Gaali Finder — architecture

A soundboard: clips sync down from a shared Appwrite bucket, the user can also import their own,
and everything plays from the device's private storage.

## Layers

Dependencies point inwards. `ui` knows `domain`; `data` implements `domain`; `domain` knows
nothing about Room, Appwrite or Compose.

```
ui/          Compose screens + ViewModels. Renders state, emits actions.
  home/        HomeScreen, HomeViewModel, HomeUiState, HomeAction
  common/      Reusable widgets (play/stop glyph, waveform, formatting)
  theme/       Colour, type, shape

domain/      The app's vocabulary. Pure Kotlin, no Android imports.
  model/       AudioClip, ClipOrigin, ClipFilter, ClipSort
  repository/  AudioClipRepository interface + its request/outcome types

data/        Implements the domain contracts.
  local/       Room entity, DAO, database, migrations
  remote/      Appwrite data source
  storage/     File writes into app-private storage, duration probing
  mapper/      Entity <-> domain translation
  repository/  AudioClipRepositoryImpl — orchestrates the three sources above

core/        Cross-cutting concerns.
  result/      DataResult<T> — success or a typed error
  error/       AppError — the closed set of failures, each with user-facing copy
  media/       AudioPlayer interface + MediaPlayer-backed controller
  connectivity/ Network availability
  dispatcher/  Injectable dispatcher qualifiers
  util/        File-name handling

di/          Hilt modules
```

## The rules that keep it honest

**The database is the single source of truth.** `observeClips()` returns a `Flow` off Room.
Writes change a row; the flow re-emits. Nothing re-fetches after a mutation.

**Failure is in the signature.** Anything that can fail returns `DataResult<T>`, not a value plus
a silent `try/catch`. `AppError` is a sealed class, so the UI maps each case to copy and the
compiler catches a new one being added.

**`CancellationException` is never swallowed.** `runCatchingResult` and `Throwable.toAppError()`
rethrow it, so cancelling a coroutine does not look like a failure.

**One player, owned by the graph.** `AudioPlayer` is an application-scoped singleton, so exactly
one clip can play, playback survives recomposition and rotation, and the activity stops it when
it leaves the foreground.

**State in, actions out.** `HomeScreen` takes a `HomeUiState`, an effect `Flow` and one
`(HomeAction) -> Unit`. Dialog visibility lives in the state, so it survives configuration change.
One-shot messages travel as `HomeEffect` so they cannot re-fire on recomposition.

**Interfaces at every seam.** Repository, player, storage, metadata reader, remote source and
connectivity are all interfaces bound in `BindingsModule`, so tests substitute fakes rather than
standing up Room, the filesystem or the network.

## Configuration

Appwrite credentials come from `local.properties` (git-ignored) or the environment, and are
exposed as `BuildConfig` fields:

```properties
APPWRITE_ENDPOINT=https://fra.cloud.appwrite.io/v1
APPWRITE_PROJECT_ID=your_project_id
APPWRITE_BUCKET_ID=your_bucket_id
```

Leaving them unset is supported: the app runs fully offline and skips the catalogue sync instead
of failing.

Firebase needs `app/google-services.json`, which is also git-ignored.

## Database

Version 3. Migrations are manual and additive; schemas are exported to `app/schemas/`.

| Version | Change |
|---------|--------|
| 1 → 2 | `isNew` badge flag |
| 2 → 3 | `durationMs`, `sizeBytes`, `addedAt`, plus indices on `fileName` and `source` |

The `source` column is stringly-typed for backwards compatibility: `"local"` means the user
imported it, anything else is the Appwrite file id. `data/mapper` is the only code that knows
this; everything above it sees a typed `ClipOrigin`.

New clips are written to `filesDir/clips/`. Clips saved by earlier versions sit directly in
`filesDir` and are resolved where they already are, so nothing has to be moved.

## Tests

`./gradlew :app:testDebugUnitTest` — 50 JVM tests covering file-name handling, the
search/filter/sort pipeline, playback-state maths, entity mapping, and the ViewModel's
error paths against fakes.

`./gradlew :app:connectedDebugAndroidTest` — Room migration tests (needs a device or emulator).
