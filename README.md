<p align="center">
  <img src="screenshots/icon.png" alt="PlezyPlexToZidoo" width="120"/>
</p>

<h1 align="center">PlezyPlexToZidoo</h1>

<p align="center">
  Bridge <strong>Plezy</strong> (Plex) to the Zidoo native player on a Z9X 8K — with intro/credits skip, Up Next, and Plex progress reporting.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-28%2B-34A853?style=flat-square&logo=android&logoColor=white" alt="Android 28+">
  <img src="https://img.shields.io/badge/ABI-armeabi--v7a-orange?style=flat-square" alt="armeabi-v7a">
  <img src="https://img.shields.io/badge/package-com.plezyplextozidoo-blue?style=flat-square" alt="com.plezyplextozidoo">
</p>

PlezyPlexToZidoo is a small Android app you install on a **Zidoo Z9X 8K**. When Plezy starts playback, this app takes the Plex media URL, maps the file path to something the Zidoo can open locally, and hands off to **`com.zidoo.player`** for full hardware decoding. While you watch, it keeps Plex in sync (timeline / watched status) and can skip intros, skip credits, and offer the next episode.

## Credit

This project is based on **[JellyfinToZidoo](https://github.com/magnetgrouplabs/JellyfinToZidoo)** by [magnetgrouplabs](https://github.com/magnetgrouplabs).

The Zidoo launch path, local player polling (`127.0.0.1:9529`), disarm-on-seek, Next Episode overlay, path substitution, and settings import/export come from that work. Jellyfin auth/API was replaced with the Plex HTTP API driven by Plezy `ACTION_VIEW` intents. Earlier lineage also includes [bowlingbeeg/PlexToZidoo](https://github.com/bowlingbeeg/PlexToZidoo).

## What it does

| Capability | In plain language |
|---|---|
| **Plezy → Zidoo handoff** | Accepts Plezy’s play URL and opens the file in Zidoo’s native player. |
| **Path substitution** | Rewrites the Plex server file path so Zidoo can find the same file (for example `/media/` → `/data/` on a Z9X). |
| **Intro / credits skip** | Uses Plex markers. Modes: Manual skip button, countdown auto-skip, or instant auto-skip. |
| **Skip overlay** | Floating “Skip Intro / Skip Credits” pill over the Zidoo player (needs Display over other apps). |
| **Up Next** | After credits (or end), offers the next episode in the season with a countdown. |
| **Plex timeline + scrobble** | Reports playback progress about every 10 seconds and on stop; marks watched around ≥90%. |
| **Disarm on seek** | If you scrub manually, auto-skip for that segment turns off. |
| **Token fallback + redaction** | Optional saved Plex URL/token when the Plezy URI has none; debug text hides tokens as `***`. |
| **armeabi-v7a** | Release APK is built for 32-bit Zidoo firmware. |

## Install

1. Download the signed APK from [Releases](https://github.com/nagel0r/PlezyPlexToZidoo/releases) (current: [v2026.11.2](https://github.com/nagel0r/PlezyPlexToZidoo/releases/tag/v2026.11.2)).
2. Install it on the Zidoo. If Android rejects the update after a keystore change, **uninstall the old build first**, then install again.
3. Open the app once and set path substitution (see below).
4. Grant **Display over other apps** (Android: *Allow display over other apps* / Settings → **Allow Skip overlay**) if you want Manual or Countdown skip modes.
5. In Plezy, set the custom / external player package to:

   ```text
   com.plezyplextozidoo
   ```

### Zidoo prerequisites

- Firmware that exposes the local player API at `127.0.0.1:9529`
- Zidoo Play mode set to **Single file**
- The Zidoo can reach the same media files Plex indexes (local path, SMB, or NFS after substitution)

## Settings (plain language)

Open **PlezyPlexToZidoo** from the launcher to configure it.

### Plex Server (fallbacks)

| Setting | What it means |
|---|---|
| **Server URL** | Optional backup Plex address, e.g. `http://192.168.1.10:32400`, used when the Plezy play URI does not include a host. |
| **X-Plex-Token** | Optional backup token when the Plezy URI has no `X-Plex-Token`. Stored encrypted. |
| **Test connection** | Checks the fallback URL + token against Plex `/identity`. |

Most of the time Plezy already sends URL + token; these fields are only backups.

### General

| Setting | What it means |
|---|---|
| **Debug data** | Shows a pre-play debug screen (paths, intent details) instead of starting immediately. Tokens in that screen are redacted (`X-Plex-Token=***`). |

### Player

| Setting | What it means |
|---|---|
| **Use zidoo native player** | On (default): hand off to `com.zidoo.player`. Off: use a generic view intent. |
| **Show title when zidoo player starts** | Passes the show/movie title into the Zidoo player UI. |
| **Skip intros** | When on, intro markers from Plex can be skipped (see Skip Behavior). |
| **Skip credits** | When on, credits markers can trigger skip / Up Next (see Skip Behavior). |
| **Skip Behavior** | How skipping works when intro/credits are armed: |
| | • **Manual Skip Button** (default) — shows a Skip Intro/Credits overlay; you press OK to skip, Back to dismiss. |
| | • **Auto-Skip with Countdown** — overlay counts down ~3 seconds then skips; Back cancels for that segment. |
| | • **Instant Auto-Skip** — seeks automatically with no overlay (previous behavior). |
| **Allow Skip overlay** | Opens Android’s “display over other apps” permission. Required for Manual and Countdown. Without it, the app falls back to instant skip for that segment. |

The overlay appears only while Zidoo is playing/paused and the playhead is inside the marker window (hidden ~1.5s before the marker end). It is removed on skip, seek past the end, player exit, or when the activity is destroyed.

### Path substitution

Plex often reports a server path such as `/media/…`. The Zidoo needs a path or URL it can open. Substitution rewrites the **prefix** of that path.

| Setting | What it means |
|---|---|
| **Part of the path to replace** | Prefix to match at the start of the Plex file path (comma-separate multiple prefixes in one rule). |
| **Replaced with** | What that prefix becomes (same number of comma-separated entries). |
| **SMB username / password** | Optional credentials if the rewritten path is `smb://…`. |

**Example that works on a Z9X:** `/media/` → `/data/`

You can set a second rule on the main screen and rules 3–10 under **More Substitution Rules**. If no rule matches, playback falls back to the original Plezy URI (with a real token, not a placeholder).

### Import / Export

| Setting | What it means |
|---|---|
| **Import / Export** | Reads/writes `PlezyPlexToZidooSettings.txt` in the Downloads folder. |
| **Include passwords in export** | Off by default. When on, the export file contains the Plex token and SMB passwords in plain text — treat that file as secret. |

## Typical first-run checklist

1. Install the release APK and uninstall any older differently signed build if needed.
2. Settings → path substitution: `/media/` → `/data/` (or your own mapping).
3. Settings → **Allow Skip overlay** → grant Display over other apps.
4. Settings → **Skip Behavior** = Manual Skip Button (or Countdown / Instant as you prefer).
5. Plezy → custom player package `com.plezyplextozidoo`.
6. Play an episode that has intro markers and confirm the skip overlay (or instant seek) behaves as expected.

## How it works (short)

1. Plezy sends `ACTION_VIEW` with a Plex `/library/parts/{partId}/…` URL (and usually a token).
2. The app resolves `partId` → `ratingKey`, loads metadata and intro/credits markers.
3. Path substitution maps the server file path; Zidoo plays it via `com.zidoo.player`.
4. A poller watches Zidoo play status, handles skip behavior, and reports timeline/scrobble to Plex.
5. Near credits / end, Up Next can offer the next season episode.

```
Plezy  --VIEW URI-->  PlezyPlexToZidoo  --path sub-->  com.zidoo.player
                              |                              |
                              +-- Plex API (markers/timeline) +-- 127.0.0.1:9529 status
```

## Build from source

```bash
export ANDROID_HOME=~/android-sdk   # or your SDK path
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/
```

Unit tests:

```bash
./gradlew test
```

Release builds are filtered to **armeabi-v7a**. Signing details for local/sideload builds are in [`keystore/README.md`](keystore/README.md).

## License

MIT — see [LICENSE](LICENSE).

Based on [JellyfinToZidoo](https://github.com/magnetgrouplabs/JellyfinToZidoo) by magnetgrouplabs, with earlier lineage from [PlexToZidoo](https://github.com/bowlingbeeg/PlexToZidoo) by bowlingbeeg.
