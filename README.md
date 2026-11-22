<p align="center">
  <img src="screenshots/icon.png" alt="PlezyPlexToZidoo" width="120"/>
</p>

<h1 align="center">PlezyPlexToZidoo</h1>

<p align="center">
  <strong>Bridge Plezy (Plex) to native Zidoo playback with intro/credits skip, Up Next, and Plex timeline/scrobble.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-28%2B-34A853?style=flat-square&logo=android&logoColor=white" alt="Android 28+">
  <img src="https://img.shields.io/badge/ABI-armeabi--v7a-orange?style=flat-square" alt="armeabi-v7a">
</p>

Play media from **Plezy** on a Zidoo Z9X 8K and hand off to `com.zidoo.player` for full hardware decoding. Progress reports back to Plex via `:/timeline`; watched status scrobbles at ≥90%.

## Upstream credit

Refactored from [magnetgrouplabs/JellyfinToZidoo](https://github.com/magnetgrouplabs/JellyfinToZidoo). Zidoo launch, `127.0.0.1:9529` polling (including the `positon` seek typo), disarm-on-seek, Next Episode overlay, path substitution, and settings import/export are preserved. Jellyfin auth/API is replaced with the Plex HTTP API driven by Plezy `ACTION_VIEW` intents.

## Features

| Feature | Description |
|---------|-------------|
| **Plezy intent parse** | Reads `http://host:port/library/parts/{partId}/…/file.mkv?X-Plex-Token=…` |
| **partId → ratingKey** | Resolves via onDeck → recentlyAdded → library sections; caches in memory |
| **Markers** | Intro/credits from `/library/metadata/{ratingKey}?includeMarkers=1` |
| **Native Zidoo playback** | Launches `com.zidoo.player` after path substitution (e.g. `/media/` → `smb://…`) |
| **Timeline + scrobble** | `:/timeline` ~every 10s and on stop; scrobble at ≥90% |
| **Up Next** | Season-children next episode with countdown overlay |
| **Disarm-on-seek** | Manual seeking disables auto-skip |
| **Settings fallbacks** | Optional Plex URL + token when the intent omits them |
| **armeabi-v7a** | Release APK filtered for 32-bit Zidoo OS |

## Requirements

- Zidoo with firmware that exposes the local player API on `127.0.0.1:9529`
- Zidoo Play mode set to **Single file**
- Direct SMB (or NFS) access from the Zidoo to the same files Plex indexes
- Plezy (or another client) that launches an external player with a Plex parts URL

## Setup

1. Install the APK on the Zidoo.
2. Open **PlezyPlexToZidoo** → configure path substitution (Plex file path prefix → `smb://…`) and optional SMB credentials.
3. Optionally set fallback **Server URL** + **X-Plex-Token** (normally taken from the Plezy URI).
4. In Plezy, set this app as the external player.

## Build

```bash
export ANDROID_HOME=~/android-sdk   # or your SDK path
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/
```

Unit tests:

```bash
./gradlew test
```

## How it works

1. Plezy sends `ACTION_VIEW` with a Plex `/library/parts/{partId}/…` URL (+ token).
2. The app resolves `partId` → `ratingKey`, loads metadata and markers.
3. Path substitution maps the server file path to an SMB URI; Zidoo plays it.
4. A poller watches `getPlayStatus`, seeks intros/credits when armed, and reports timeline/scrobble to Plex.
5. On episode end (or credits), Up Next offers the next season child.

## License

See [LICENSE](LICENSE). Based on JellyfinToZidoo by magnetgrouplabs.
