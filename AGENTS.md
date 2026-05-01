# AGENTS.md

Operational guide for AI agents working on this repo.

## Project

TVTron — Android IPTV player. Sideload-only (no Play Store). OTA updates ship via GitHub Releases on `alkevintan/tvtron`. Mirrors the architecture of sister project `RadioPlayer` (radio-gaga).

- **minSdk:** 21 (Lollipop) · **targetSdk/compileSdk:** 34
- **Lang:** Kotlin · **Build:** Gradle (Groovy DSL) · **JVM:** 17
- **Stack:** ExoPlayer 2.19.1 (core+ui+hls+dash+smoothstreaming+rtsp+okhttp ext), Room, OkHttp, Material Components, Coroutines, WorkManager, Picasso
- **Package:** `com.tvtron.player`

## Build & run

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew :app:assembleDebug   # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug    # install onto connected device/emulator
```

Release builds are not signed separately — they ship as debug-signed (see "Releases" below). Do not enable `signingConfigs.release` without coordinating keystore handover; OTA continuity depends on the same `~/.android/debug.keystore` that produced v0.1.0.

## Source layout

```
app/src/main/
├── AndroidManifest.xml
├── assets/cacerts.pem                       # Mozilla CA bundle for legacy TLS (pre-N)
├── java/com/tvtron/player/
│   ├── MainActivity.kt                      # channel browser, category chips, search
│   ├── TVTronApplication.kt
│   ├── data/                                # Playlist, Channel, Favorite, EpgChannel, EpgProgram + DAOs
│   ├── service/PlaybackService.kt           # ExoPlayer foreground service (HLS/DASH/SS/RTSP/progressive)
│   ├── ui/
│   │   ├── SplashActivity.kt
│   │   ├── PlayerActivity.kt                # fullscreen player, swipe-zap, retro vol OSD
│   │   ├── RetroVolumeBarView.kt            # 90s green segmented LED volume bar
│   │   ├── PlaylistManagerActivity.kt
│   │   ├── PlaylistEditActivity.kt
│   │   ├── ChannelAdapter.kt / PlaylistAdapter.kt
│   │   ├── SettingsActivity.kt
│   │   └── UpdateDialog.kt
│   ├── util/
│   │   ├── HttpClientFactory.kt             # OkHttp w/ legacy CA trust on pre-N
│   │   ├── M3uParser.kt / XmltvParser.kt
│   │   ├── PlaylistRepository.kt            # fetch + parse + write to Room
│   │   ├── SettingsManager.kt
│   │   ├── UpdateChecker.kt / UpdateInstaller.kt
│   ├── worker/
│   │   ├── PlaylistRefreshWorker.kt         # WorkManager periodic refresh
│   │   └── RefreshScheduler.kt
│   └── viewmodel/MainViewModel.kt
└── res/
    ├── layout/, values/, menu/, xml/file_paths.xml
```

## Format support

| Scheme/extension | Engine | Notes |
|---|---|---|
| `*.m3u8` | HlsMediaSource | full ABR + audio/text track switching |
| `*.mpd` | DashMediaSource | |
| `*.ism` / `/manifest` | SsMediaSource | Smooth Streaming |
| `rtsp://` | RtspMediaSource | |
| http(s) progressive (mp4, mpegts, etc.) | ProgressiveMediaSource | |
| `rtmp://`, `udp://`, multicast | **not supported in MVP** | needs FFmpeg or LibVLC extension |

DRM (Widevine) is not wired up. Add `MediaItem.DrmConfiguration` per channel if needed.

## Playlists & EPG

- A `Playlist` is one M3U source (URL or local `content://` URI). Channels are grouped per playlist; favorites are per-playlist.
- Auto-refresh modes: **OFF**, **ON_LAUNCH** (refreshed once when MainViewModel observes the list), **SCHEDULED** (every N hours via WorkManager periodic work; min 1h).
- EPG URL resolution: `Playlist.epgUrl` if non-blank, else `#EXTM3U url-tvg=...` from M3U header.
- XMLTV gz/gzip suffixes are auto-decompressed.
- EPG retention window default: 1 day back, 7 days forward. Configured in Settings; pruning happens during parse (programs outside the window are dropped before insert).

## Player UX gotchas

- `PlayerActivity` keeps a binder to `PlaybackService`. The `PlayerView` is attached directly to `service.exoPlayer` so swapping channels reuses the same player instance.
- Volume hardware keys are intercepted in `onKeyDown` — they always control STREAM_MUSIC and trigger `RetroVolumeBarView.show(...)`. Channel zap is on **D-pad up/down** and **vertical fling** on the player surface.
- Aspect modes map: FIT→`RESIZE_MODE_FIT`, FILL→`RESIZE_MODE_ZOOM`, 16:9→`FIXED_WIDTH`, 4:3→`FIXED_HEIGHT`. Default is configurable in Settings.
- Audio/subtitle pickers use ExoPlayer's `Tracks.Group` + `TrackSelectionOverride`; not all M3U8 multi-audio streams expose a meaningful label — fall back to language code.

## OTA update flow

Identical to RadioPlayer:

1. App polls `https://api.github.com/repos/alkevintan/tvtron/releases/latest` (manual via Settings; auto-on-launch wiring to be added).
2. Tag must look like `vX.Y.Z`. `tag_name` minus leading `v` becomes `versionName` for comparison.
3. First `.apk` asset on the release is downloaded via `DownloadManager` to `Download/updates/TVTron-update.apk` in app-private external storage.
4. APK opened via `FileProvider` (authority `${applicationId}.updates`) with `ACTION_VIEW` → system installer.
5. Drafts and prereleases are skipped by `UpdateChecker.fetchLatest`.

The owner/repo is read from `res/values/strings.xml` (`update_github_owner`, `update_github_repo`). Empty values disable update checks gracefully.

**Signature continuity is critical.** Android refuses upgrades when keystore differs. All releases must be built on the same machine that produced v0.1.0.

## Releases

Use the helper script — do not run the steps by hand:

```bash
scripts/make-release.sh 0.1.1
scripts/make-release.sh 0.1.1 --notes "Fixed channel zap on first launch"
scripts/make-release.sh 0.1.1 --notes-file CHANGES.md
scripts/make-release.sh 0.1.1 --prerelease       # tagged but skipped by UpdateChecker
```

What it does:
1. Validates: on `main`, clean tree, in sync with origin, tag/release don't exist, semver format
2. Bumps `versionName` to the given value, increments `versionCode` by 1 in `app/build.gradle`
3. `./gradlew :app:assembleDebug`
4. Commits the bump as `chore: release vX.Y.Z`, creates annotated tag, pushes both
5. `gh release create` with the renamed APK (`TVTron-X.Y.Z.apk`) attached
6. If `--notes` not given, generates from `git log <prev-tag>..HEAD`

## Conventions

- Conventional commits: `feat:`, `fix:`, `chore:`, `refactor:`, `docs:`. Scope optional.
- Persisted user settings go through `SettingsManager` only — no scattered `getSharedPreferences` calls.
- All HTTP calls use `HttpClientFactory.get(context)` so legacy-Android CA trust is consistent.
- New activities go under `ui/`, background work under `service/` or `worker/`, helpers under `util/`.

## Gotchas

- `PlaybackService` uses the deprecated standalone `com.google.android.exoplayer:exoplayer-*:2.19.1` artifacts (matches sister project RadioPlayer). Migration to `androidx.media3` is a separate piece of work — be aware that `Player.Listener` and `MediaSource` package paths will change.
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` is required on Android 14+ for the playback service to start; manifest is already set.
- The launcher mipmaps under `mipmap-{m,h,x,xx,xxx}dpi/` are placeholder PNGs copied from RadioPlayer for legacy density (API < 26). Replace with TVTron-branded raster icons before the first public release.
- `PlaylistRefreshWorker` is the only ON_LAUNCH refresh entry today; if you add a true app-launch hook, ensure it doesn't double-fire with the WorkManager scheduled job.
- `PlayerActivity` runs in `sensorLandscape`. It does **not** support PiP (deferred).
