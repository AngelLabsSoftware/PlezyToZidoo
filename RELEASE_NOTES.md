# Release notes

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
