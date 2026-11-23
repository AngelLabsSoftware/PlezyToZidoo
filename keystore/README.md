# Private sideload keystore

`pp2z-private-release.jks` is the **durable private sideload** signing key for
local and Zidoo installs (starting **2026.11.3**). It is **not** a Play Store
upload key.

- Alias: `pp2z` (override with `keyAlias` in properties)
- Passwords: set locally in gitignored `keystore/keystore.properties` (never commit)
- The `.jks` file is gitignored (`*.jks` / `*.keystore`)
- **Do not regenerate** this key for later 11.3+ builds — Android upgrades require the same certificate

## Local properties (required for sideload signing)

Copy the example and fill in values that match the durable JKS:

```bash
cp keystore/keystore.properties.example keystore/keystore.properties
# Edit keystore/keystore.properties — set storePassword, keyPassword, keyAlias
```

`app/build.gradle` reads those properties only when
`keystore/pp2z-private-release.jks` and `keystore/keystore.properties` both exist.
No password literals are stored in Gradle or this README.

## Signing priority (`app/build.gradle`)

1. **CI / durable secrets** — env vars `PP2Z_KEYSTORE_PATH`, `PP2Z_KEYSTORE_PASSWORD`,
   `PP2Z_KEY_ALIAS`, `PP2Z_KEY_PASSWORD` (GitHub Actions secrets below take this path)
2. **Local gitignored files** — `keystore/pp2z-private-release.jks` + `keystore/keystore.properties`
3. **Android debug keystore** — local installs only (wrong cert for Z9X upgrades)

## GitHub Actions secrets (durable CI path)

Repo secrets (names only — never commit values):

| Secret name | Contents |
|-------------|----------|
| `PP2Z_KEYSTORE_BASE64` | Base64 of `pp2z-private-release.jks` |
| `PP2Z_KEYSTORE_PASSWORD` | JKS store password |
| `PP2Z_KEY_ALIAS` | Key alias (`pp2z`) |
| `PP2Z_KEY_PASSWORD` | Key password |

`.github/workflows/release.yml` decodes the base64 keystore into
`$RUNNER_TEMP/release.jks` and sets `PP2Z_KEYSTORE_PATH` for `assembleRelease`.
If any secret is missing, CI skips signed publish (tests still run).

### Upload once (repo admin)

```bash
# From a machine that has the durable JKS + keystore.properties (never paste into chat):
gh secret set PP2Z_KEYSTORE_BASE64 -R nagel0r/PlezyPlexToZidoo < pp2z-private-release.jks.b64
gh secret set PP2Z_KEYSTORE_PASSWORD -R nagel0r/PlezyPlexToZidoo --body "$(grep '^storePassword=' keystore.properties | cut -d= -f2-)"
gh secret set PP2Z_KEY_ALIAS -R nagel0r/PlezyPlexToZidoo --body pp2z
gh secret set PP2Z_KEY_PASSWORD -R nagel0r/PlezyPlexToZidoo --body "$(grep '^keyPassword=' keystore.properties | cut -d= -f2-)"
```

Cloud agents often cannot set secrets (403). Prefer uploading from Adam’s machine
using the durable keystore copy kept outside git.

## Generate a NEW key only if starting a fresh install lineage

```bash
mkdir -p keystore
# Choose your own store/key passwords; put the same values in keystore.properties.
keytool -genkeypair -v \
  -keystore keystore/pp2z-private-release.jks \
  -storetype JKS \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias pp2z \
  -dname 'CN=PlezyPlexToZidoo Private Sideload, OU=Private, O=nagel0r, L=Local, ST=NA, C=US'
```

`keytool` will prompt for store and key passwords interactively.
**Regenerating invalidates upgrades** from any APK signed with the previous key —
users must uninstall first (see the Zidoo reinstall guide).
