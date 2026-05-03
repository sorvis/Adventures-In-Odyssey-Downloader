# Odyssey (Android)

Daily-check downloader and player for Adventures in Odyssey episodes from
oneplace.com. Optionally archives to and pulls back from a self-hosted NAS
service (`../archive-service`).

The app **works fully without a NAS** — daily download, in-app player,
retention-based pruning all run standalone. NAS-dependent features (browse,
search, archive push, pull-from-NAS) hide gracefully when no NAS is configured.

## Stack

Kotlin · Jetpack Compose · Hilt · Room · DataStore · WorkManager · OkHttp ·
kotlinx.serialization · Media3 (ExoPlayer + MediaSession). MinSDK 26, target 35.

## Modules

```
app/src/main/java/com/odyssey/
  app/                       Application, MainActivity, DI, Settings (DataStore)
  data/local/                Room: LocalEpisodeEntity, PlaybackPositionEntity
  scrape/                    OneplaceClient — bootstrap + JSON API client
  download/                  EpisodeDownloader — OkHttp w/ Range resume
  nas/                       NasClient — all calls return Result; not-configured handled
  player/                    OdysseyPlaybackService + PlayerController
  work/                      DailyCheck, Download, Archive, Retention workers + Scheduler
  ui/                        Compose nav + Recent / NAS / Player / Settings screens
```

## How it runs

1. `OdysseyApp.onCreate()` calls `WorkScheduler.ensureDailyCheck()` — schedules
   `DailyCheckWorker` to run once a day on any connected network.
2. `DailyCheckWorker` calls `OneplaceClient.newSince(lastSeen)`, inserts
   placeholder rows for new episodes, and chains a `DownloadEpisodeWorker` per
   episode (constraint: `UNMETERED`).
3. After download succeeds, `ArchiveEpisodeWorker` runs. If no NAS configured,
   it no-ops; otherwise it POSTs to `/episodes` with the audio + metadata.
4. `RetentionWorker` prunes downloads beyond the configured count. With NAS
   configured, only archived episodes are eligible to prune; standalone, oldest
   wins.

## Build

Open the `android/` directory in Android Studio (Hedgehog or newer). The
project uses Kotlin 2.0 + KSP + Hilt; Gradle 8.x. No `gradle-wrapper.jar` is
checked in — Studio will offer to generate it on first import.

## Known TODOs left in the scaffold

- ExoPlayer auth header for streaming NAS audio (`BrowseVm.stream`) needs an
  `OkHttpDataSource.Factory` with an auth interceptor wired into the player.
- NotificationChannel for `OdysseyPlaybackService` (Media3 will create one
  automatically but customizing it is nicer).
- App icon / launcher mipmap.
- Theme.kt with proper Material3 color scheme.
