# Release notes

## 2026.11.9 — Seek rearm for Skip Intro/Credits

- Skip Intro/Credits are **position-based**: after FF/RW/chapter/manual seek or resume settles inside a marker, the configured mode activates.
- Back/cancel dismisses the **current visit only**; leaving and returning rearms. App skip seeks are distinguished from user scrubbing (no Instant loops).
- Keeps 11.8 multi-segment credits + true-end Up Next and screensaver logo gate / text fallback.
- Version `2026.11.9` / `versionCode` `20261130`.

## 2026.11.8 — Credits segment skip + screensaver logo gate

- **Credits:** Skip Credits seeks past the **current** Plex credits/outro segment only (all markers retained). Manual / Countdown / Instant share that destination for films and TV. Instant no longer binge-closes at earliest credits start.
- **Up Next:** Only after true media end (credits seek at/near duration, or natural ≥90% watched). Mid/post-credit scenes preserved; Back cancels the current segment only.
- **Screensaver:** Default requires usable transparent clearLogo (preload+decode before transition). New **Allow text title fallback** (off by default) for understated white text titles. Keeps 11.7 Ken Burns (~1.05) and Small/Original logo size.
- Version `2026.11.8` / `versionCode` `20261129`.

## 2026.11.6 — Screensaver polish

- Includes 11.4/11.5 screensaver sections HTML/auth fixes (from open PR #8; not force-merged).
- Subtle continuous Ken Burns zoom (~2.5%) over each slide; smooth through crossfades.
- ClearLogo ~25% of prior size; screen-relative max; never upscaled past native pixels.
- Settings warns/offers `:32400` for bare LAN Server URLs — never silently rewrites custom ports or reverse-proxy URLs.
- Date display, 4K art rules, logo-only policy, transitions, playback/skip unchanged.

## 2026.11.2 — Token fallback + Skip overlay

- **PLE-7:** When path substitution does not match, fallback playback uses the original Intent.data URI (real `X-Plex-Token`) or injects the resolved token — never a literal `X-Plex-Token=<token>` placeholder. Debug UI redacts tokens as `X-Plex-Token=***`.
- **PLE-8:** Skip Intro/Credits `TYPE_APPLICATION_OVERLAY` pill over the Zidoo player with Settings **Skip Behavior**: Manual Skip Button (default), Auto-Skip with Countdown (3s, Back cancels), or Instant Auto-Skip. Requests overlay permission from launch/Settings. Overlay removed on seek past end, skip, player exit, or destroy.
- Optional Settings note: path tip `/media/` → `/data/`.
- Package `com.plezyplextozidoo`, signed `armeabi-v7a` release APK.

## 2026.11.1 — PlezyPlexToZidoo bootstrap

- Seeded from [magnetgrouplabs/JellyfinToZidoo](https://github.com/magnetgrouplabs/JellyfinToZidoo) (history preserved).
- Package / app id: `com.plezyplextozidoo`.
- Replaced Jellyfin API with Plex: Plezy URI parse, partId resolve, markers, timeline (~10s), scrobble ≥90%.
- Settings: fallback Plex URL + token (encrypted); retained path substitution, skip toggles, debug, import/export.
- Release ABI filter: `armeabi-v7a`.
