# Release notes

## 2026.11.15 — Launcher icon safe padding for Zidoo crop

- **Icons:** Regenerated launcher mipmaps, round icons, and adaptive foreground from Adam’s circular-03 crop, scaled to **0.75** of the orange canvas (more safe margin) so Zidoo home-tile and PackageInstaller squircles no longer kiss the horns/face. Settings header still uses the shaped logo.
- Package `com.plezyplextozidoo` and PP2Z signing unchanged; playback / settings behaviour unchanged.
- Version `2026.11.15` / `versionCode` `20261136`.

## 2026.11.14 — Launcher icon padding for Zidoo squircle

- **Icons:** Regenerated launcher mipmaps, round icons, and adaptive foreground from Adam’s circular logo crop (filled 1:1 — no extra inset) so Zidoo home-tile / PackageInstaller squircles keep his intended orange margin. Settings header still uses the shaped logo.
- Package `com.plezyplextozidoo` and PP2Z signing unchanged; playback / settings behaviour unchanged.
- Version `2026.11.14` / `versionCode` `20261135`.

## 2026.11.13 — PlezyToZidoo+ branding + settings refinements

- **Branding:** Display name **PlezyToZidoo+** by **Angel Labs** (package `com.plezyplextozidoo` unchanged — installs as an update). Settings header shows PlezyToZidoo+ / by Angel Labs; version top-right unchanged. Angel Labs orange+white logo replaces the purple triangle launcher / settings header icon (when supplied artwork is present).
- **Settings layout:** One scrolling page with two independently stacked columns — left: Playback, Up Next, Plex connection; right: Plex screensaver, Path substitutions; full-width Settings & diagnostics below. Keeps 11.12 TV focus rings; no layout jump on focus.
- **Additional path rules:** Unused rules show **Not set** placeholders (not example paths or password-mask dots). Saved values and stored passwords preserved; examples never treated as real rules.
- **Export security:** “Include passwords in export” defaults OFF; resets to OFF after each export **and** whenever Settings opens (safer sticky-opt-in UX). When passwords are excluded, Plex tokens / SMB passwords are omitted and embedded auth in URLs (`X-Plex-Token`, `user:pass@`) is stripped from the export file.
- Playback / Skip Intro/Credits (11.9 multi-segment) / Up Next / screensaver / path-substitution logic unchanged.
- Version `2026.11.13` / `versionCode` `20261134`.

## 2026.11.12 — Settings focus + additional rules UI

- Stronger TV remote focus: distinct unselected / selected / focused / selected+focused (segmented, buttons, fields); whole-row toggle highlight; focus rings not clipped; no layout jump on focus.
- Manage Additional Rules redesigned as charcoal cards (numbered rules 2–10, 2-column path + SMB fields, masked passwords); same preference keys.
- Dialog chrome aligned to dashboard monochrome.
- Playback / skip / Up Next / screensaver / path semantics unchanged.
- Version `2026.11.12` / `versionCode` `20261133`.

## 2026.11.11 — Settings dashboard + Up Next polish

- **Settings:** TV-oriented charcoal dashboard (two-column panels, segmented selectors, mono toggles/buttons). Existing preference keys preserved for upgrades.
- **Up Next:** Material purple tint removed (AppCompatButton + cleared backgroundTint). Content group ~50% screen height, centred. Configurable countdown 3/5/7/10s (default 7; independent of Skip’s 3s). Import/export includes the new pref.
- **Screensaver:** Library Movies/TV/Both; recent-titles (last 2 years); slide 20/30/45/60s; crossfade 1/2/3/5s; animation speed 1x/2x/3x (1x = prior Ken Burns pace).
- **Copy:** Fallback vs screensaver Plex credentials; generic path examples; Zidoo overlay vs screensaver instructions.
- Playback/skip/arming logic unchanged from 11.10.
- Version `2026.11.11` / `versionCode` `20261132`.

## 2026.11.10 — UI polish + README

- **Screensaver logos:** Small (25% left), Medium (50% left, default), Large (80% centred with ≥10% side padding); never upscale past native; tall-logo height cap.
- **Loading:** thin circular spinner on black (no “Loading…” text); errors still shown.
- **Up Next + Skip:** monochrome TV focus (black/white focused, pale grey/dark text unfocused, small corner radius — no pink/pills); centred Up Next composition with larger preview.
- **Overlay permission copy:** Zidoo-accurate first-play side-panel steps (distinct from screensaver settings).
- **README:** Adam’s full replacement.
- Keeps 11.9 seek-rearm skip + 11.8 multi-segment credits / true-end Up Next / logo-required screensaver.
- Version `2026.11.10` / `versionCode` `20261131`.

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
