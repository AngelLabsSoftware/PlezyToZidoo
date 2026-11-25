<p align="center">
  <img src="screenshots/icon.png" alt="PlezyToZidoo+" width="120"/>
</p>

<h1 align="center">PlezyToZidoo+</h1>

<p align="center">
  Enhanced native playback and Plex integration for Zidoo media players, with intro and credit skipping, Up Next autoplay, Plex artwork screensavers and flexible media path mapping.
</p>

<p align="center">
  <em>by Angel Labs</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-28%2B-34A853?style=flat-square&logo=android&logoColor=white" alt="Android 28+">
  <img src="https://img.shields.io/badge/ABI-armeabi--v7a-orange?style=flat-square" alt="armeabi-v7a">
  <img src="https://img.shields.io/badge/package-com.plezyplextozidoo-blue?style=flat-square" alt="com.plezyplextozidoo">
</p>

**PlezyToZidoo+** is a small Android app designed for the **Zidoo Z9X 8K**. It allows you to browse your Plex library using Plezy while enjoying the picture and sound quality of the Zidoo's native media player.

It also brings across some of the features you might miss when using an external player, including Skip Intro, Skip Credits, Up Next and synchronised playback progress.

As an added bonus, it includes a cinematic 4K screensaver using artwork and title logos from your own Plex library.

This is an independent project by **Angel Labs**. It is not affiliated with, endorsed by, or an official product of Plezy, Plex, or Zidoo. The GitHub repository is `AngelLabsSoftware/PlezyToZidoo` (private); the Android package id stays `com.plezyplextozidoo` so signed updates install over previous builds. Launcher icons use the circular Angel Labs mark with safe padding for Zidoo’s squircle masks; the settings header keeps the shaped logo.

## Why I made this

I wanted Plezy's Plex browsing experience, but with the Zidoo's native player handling the actual video and audio.

I'd previously tried Plezy with Jellyfin and liked its intro and credits skipping, but preferred the richer Plex home screen, collections and personalised discovery rows.

This project brings the two things together: Plezy for browsing Plex, and Zidoo for playback, without having to give up the convenience of modern streaming interfaces.

**Jellyfin is not required.**

The project started as something I wanted for my own home cinema and has gradually grown to include a few other features that make the Zidoo a nicer device to use.

## What it does

| Feature | Description |
|---|---|
| Native Zidoo playback | Browse in Plezy and play using the Zidoo's native media player. |
| Plex progress synchronisation | Keeps playback progress and watched status in sync with Plex. |
| Skip Intro | Skip television intros using Plex's existing markers. |
| Skip Credits | Skip credits without missing mid-credit or post-credit scenes identified by Plex. |
| Automatic skipping | Choose between manual buttons, a 3-second countdown or instant skipping. |
| Up Next | Offers the next episode when the current episode finishes. |
| 4K screensaver | Displays cinematic artwork from your own Plex library. |
| Transparent title logos | Uses original film and TV logos, with optional text fallback. |
| Path substitution | Maps Plex server file paths to locations the Zidoo can access. |
| Settings backup | Import and export your configuration. |

## Skip Intro and Skip Credits

One of the things I particularly wanted was the ability to skip intros and credits without sacrificing the benefits of the Zidoo player.

The app uses Plex's existing intro and credits markers and offers three ways to skip them.

| Mode | Behaviour |
|---|---|
| Manual Skip Button | A small Skip Intro or Skip Credits button appears. Press OK to skip or Back to dismiss it. |
| Auto-Skip with Countdown | A 3-second countdown appears before automatically skipping. Press OK to skip immediately or Back to cancel. |
| Instant Auto-Skip | Skips automatically without displaying a button. |

Intro and credits skipping can be enabled or disabled individually.

### Fast-forwarding and chapter skipping

You don't have to watch an entire film or episode for the skip buttons to work.

Fast-forward, rewind, jump to a chapter or resume playback within an intro or credits sequence and the skip functionality will become available once playback settles.

If you dismiss a skip button, it stays dismissed for that visit to the segment. Leave the segment and return to it and skipping becomes available again.

### What about Marvel films?

Not all credits are created equal!

Many films have additional scenes during or after the credits. Rather than simply ending playback when the credits begin, PlezyToZidoo+ skips individual credits segments.

If Plex has correctly identified multiple credits sequences, you can skip the first set of credits, enjoy the additional scene, then skip the next set of credits.

This has been tested with Avengers, successfully skipping to the Thanos scene and then to the final shawarma restaurant scene.

The accuracy of skipping depends on the markers provided by Plex.

## Up Next

When a TV episode finishes, the app can offer the next episode with a preview image and countdown.

You can start the next episode immediately or cancel and return to browsing.

Skipping credits will also bring up Up Next when it genuinely completes the episode. If there is an additional scene after the credits, playback continues so you don't miss it.

Up Next uses the same simple monochrome styling as the skip controls, designed to be easy to read and navigate from the sofa.

## Plex Artwork Screensaver

I wanted a screensaver that made the most of a large OLED television without having to maintain a separate collection of wallpapers.

The result is a cinematic screensaver that pulls background artwork and transparent title logos directly from your Plex library.

Artwork fills the screen, with a very subtle background zoom and gentle crossfades between titles.

### Screensaver features

- Movies, TV shows or both.
- Optional filtering to titles originally released within the last two years.
- Genuine 4K background artwork by default.
- Optional lower-resolution artwork if your library has limited 4K images.
- Original transparent film and TV title logos.
- Optional text titles when a suitable logo is unavailable.
- Adjustable logo size.
- Adjustable artwork display and crossfade durations.
- Subtle background movement and logo repositioning.
- Built-in screensaver preview.
- Native Android screensaver integration.

By default, the screensaver only displays qualifying artwork with a usable transparent title logo. You can enable **Allow text title fallback** if you'd prefer a wider selection of titles.

Artwork is loaded directly from Plex, with upcoming images preloaded into memory for smooth transitions. There is no separate persistent artwork cache to maintain.

### Logo sizes

| Size | Maximum screen width | Alignment |
|---|---|---|
| Small | 25% | Left |
| Medium | 50% | Left |
| Large | 80% | Centre |

These are maximum sizes. Smaller logos are not unnecessarily enlarged, helping to preserve image quality.

### Setting up the screensaver

Open PlezyToZidoo+ and enter your Plex server address and token in the Plex connection settings.

For a typical local Plex server, the address will look something like:

```
http://192.168.1.10:32400
```

Remember to include the port number, normally **32400**.

Open the **Plex screensaver** settings and use Preview to check that everything is working.

To use it as your normal screensaver, open the Zidoo's native screensaver settings and select **Plex Artwork**.

You don't need to use the Zidoo Image Player or manually download any artwork.

## Installation

1. Download the latest signed APK from [GitHub Releases](https://github.com/AngelLabsSoftware/PlezyToZidoo/releases).
2. Install the APK on your Zidoo.
3. Open PlezyToZidoo+ and configure your path substitution settings.
4. In Plezy, configure the custom/external player package shown below.
5. Play a film or TV episode to check that everything is working.

The external player package is unchanged:

```
com.plezyplextozidoo
```

### Allowing the skip buttons to appear

The skip buttons need permission to appear over the native Zidoo player.

On the Zidoo Z9X 8K, the easiest way to grant this permission is when you first play something after installing the app.

1. Start playback from Plezy.
2. An Android settings panel should appear at the side of the screen if permission is required.
3. Scroll towards the bottom of the application list.
4. Select **PlezyToZidoo+**.
5. Enable permission to display over other applications.

This is required for the Manual and Countdown skip modes.

There is also an **Allow Skip overlay** option within the app settings.

### Updating the app

New releases signed with the same certificate can be installed directly over your existing installation, retaining your settings.

If you are upgrading from an older build signed with a different certificate, Android may require you to uninstall that version first.

Export your settings before uninstalling so you can restore them afterwards.

### Zidoo prerequisites

- Firmware that exposes the local player API at `127.0.0.1:9529`.
- Zidoo Play mode set to **Single file**.
- The Zidoo must be able to access the same media files indexed by Plex, using local paths, SMB or NFS as appropriate.

The app has been developed and tested on a Zidoo Z9X 8K. Compatibility with other Zidoo models is not guaranteed.

## App settings

Open PlezyToZidoo+ from the Zidoo launcher to configure the app. The settings screen is a single scrolling page with two columns (Playback / Up Next / Plex connection on the left; Plex screensaver / Path substitutions on the right) and Settings & diagnostics full-width below.

### Plex connection

| Setting | Description |
|---|---|
| Server URL | Your Plex server address, including the port. |
| Plex token | Your Plex authentication token, stored securely. Empty fields show **Not set**. |
| Test connection | Checks that the app can connect to your Plex server. |

Plezy normally supplies the server address and token when starting playback. These settings provide a fallback for playback and are also required for the standalone screensaver.

### Playback

| Setting | Description |
|---|---|
| Use Zidoo native player | Enabled by default. Uses the native Zidoo player rather than a generic Android player intent. |
| Show title when Zidoo player starts | Passes the film or episode title to the Zidoo player. |
| Skip intros | Enables intro skipping. |
| Skip credits | Enables credits skipping. |
| Skip behaviour | Choose Manual, Countdown or Instant skipping. |
| Allow Skip overlay | Opens the permission settings required for the skip buttons. |

### Path substitution

Plex and Zidoo don't necessarily see your media files using the same paths.

For example, Plex might report a file path beginning with `/media/`, while the Zidoo accesses the same file through `/data/`.

Path substitution allows you to translate one into the other.

| Setting | Description |
|---|---|
| Source path | The prefix of the file path reported by Plex. |
| Replacement path | The equivalent prefix accessible by the Zidoo. |
| SMB username/password | Optional credentials for SMB network shares. |

An example configuration:

```
/media/ → /data/
```

You can configure up to ten substitution rules to accommodate different libraries and storage locations. Unused additional rules show **Not set** until you enter a value — example paths are never treated as saved rules.

Your own configuration will depend on how your Plex server and Zidoo access your media.

### Import and export

You can back up and restore your settings using the Import/Export feature.

Settings are saved to:

```
Downloads/PlezyPlexToZidooSettings.txt
```

Passwords and tokens are excluded from exports by default. **Include passwords in export** resets to off after each export and whenever you open Settings.

When passwords are excluded, the export also strips Plex tokens and credentials embedded in URLs.

If you choose to include passwords, the exported file contains sensitive credentials in plain text. Keep it somewhere secure and don't share it.

### Debug data

The Debug data option displays useful information about the playback request and path substitution before starting playback.

Plex authentication tokens are redacted from the debug display.

This can be useful if you're having problems getting the Zidoo to locate your media files.

## How it works

For anyone interested in what's happening behind the scenes:

1. Plezy sends a playback request containing the Plex media URL.
2. PlezyToZidoo+ resolves the media metadata and retrieves available intro and credits markers.
3. Path substitution translates the media path into something the Zidoo can access.
4. Playback is handed over to the native Zidoo player.
5. The app monitors playback position using the Zidoo's local player API.
6. Skip controls appear when appropriate and playback progress is reported back to Plex.
7. When a TV episode finishes, the app can offer the next episode.

```
Plezy
  |
  v
PlezyToZidoo+ ---- Plex API
  |                  Metadata, markers,
  |                  progress and artwork
  v
Zidoo native player
```

The screensaver operates independently of Plezy playback, using the configured Plex server connection to retrieve artwork.

## Credit

This project is based on **[JellyfinToZidoo](https://github.com/magnetgrouplabs/JellyfinToZidoo)** by [magnetgrouplabs](https://github.com/magnetgrouplabs).

Much of the original groundwork, including launching the Zidoo player, monitoring playback, path substitution, skip controls, next-episode functionality and settings import/export, comes from that project.

PlezyToZidoo+ adapts that work to use the Plex HTTP API and Plezy's external player integration, with further development of the skip functionality, screensaver and user interface by **Angel Labs**.

Earlier lineage also includes [PlexToZidoo](https://github.com/bowlingbeeg/PlexToZidoo) by bowlingbeeg.

Thanks also to the developers of [Plezy](https://github.com/edde746/plezy) for creating such a lovely way to browse a media library.

## Build from source

```
export ANDROID_HOME=~/android-sdk
./gradlew assembleRelease
```

The APK can be found in:

```
app/build/outputs/apk/release/
```

To run the unit tests:

```
./gradlew test
```

Release builds are filtered to `armeabi-v7a`.

Signing information for local builds is available in [keystore/README.md](keystore/README.md).

## License

MIT — see [LICENSE](LICENSE).

Based on JellyfinToZidoo by magnetgrouplabs, with earlier lineage from PlexToZidoo by bowlingbeeg.
